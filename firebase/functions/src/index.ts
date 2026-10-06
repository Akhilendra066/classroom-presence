import {initializeApp, getApps} from "firebase-admin/app";
import {getAuth} from "firebase-admin/auth";
import {getFirestore, Timestamp} from "firebase-admin/firestore";
import {onCall, HttpsError, CallableRequest} from "firebase-functions/v2/https";
import {onSchedule} from "firebase-functions/v2/scheduler";
import {createHash} from "node:crypto";
import {z} from "zod";
import {aggregate, batchSchema, beaconSchema, checkpointSchema, configSchema, evaluate, requireMember, validateCheckpointTiming, Config, Beacon, Point} from "./policy";
if(!getApps().length) initializeApp();
const db=getFirestore(), region="asia-south1", graceMs=15*60*1000;
const identifier=z.string().regex(/^[A-Za-z0-9_-]{1,128}$/);
const response=(value:unknown)=>({json:JSON.stringify(value)});
type Session = {id:string,classId:string,title:string,roomId:string,startMs:number,endMs:number,config:Config,beacons:Beacon[],state:string,teacherUids:string[],participantUids:string[],finalAfterMs:number,demo:boolean};
function identity(req:CallableRequest) {
 if(!req.auth) throw new HttpsError("unauthenticated","Sign in first");
 const role=req.auth.token.role;
 if(role!=="STUDENT" && role!=="TEACHER") throw new HttpsError("permission-denied","Account role not provisioned");
 return {uid:req.auth.uid,role};
}
function teacher(req:CallableRequest) {const who=identity(req); if(who.role!=="TEACHER") throw new HttpsError("permission-denied","Teacher role required"); return who;}
function parseJson<S extends z.ZodTypeAny>(raw:unknown,schema:S):z.output<S> {if(typeof raw!=="string" || raw.length>600000) throw new HttpsError("invalid-argument","Invalid JSON payload"); return schema.parse(JSON.parse(raw));}
function endpoint(handler:(req:CallableRequest)=>Promise<unknown>) {
 return onCall({region},async req=>{try{return response(await handler(req));}catch(e){
  if(e instanceof HttpsError) throw e;
  if(e instanceof z.ZodError || e instanceof SyntaxError) throw new HttpsError("invalid-argument","Payload failed validation");
  console.error(e); throw new HttpsError("internal","Operation failed. Retry or contact the administrator.");
 }});
}
async function getSession(id:string,uid:string,target?:string):Promise<Session> {
 const doc=await db.doc(`classSessions/${id}`).get();
 if(!doc.exists) throw new HttpsError("not-found","Session missing");
 const session=doc.data() as Session;
 try {requireMember(uid,session,target);} catch {throw new HttpsError("permission-denied","Session membership required");}
 return session;
}
export const listClasses=endpoint(async req=>{
 const who=identity(req),field=who.role==="TEACHER"?"teacherUids":"studentUids";
 const snap=await db.collection("classes").where(field,"array-contains",who.uid).limit(100).get();
 return snap.docs.map(d=>({id:d.id,title:d.get("title"),roomId:d.get("roomId"),teacherUids:d.get("teacherUids")}));
});
export const listSessions=endpoint(async req=>{
 const who=identity(req),field=who.role==="TEACHER"?"teacherUids":"participantUids";
 const snap=await db.collection("classSessions").where(field,"array-contains",who.uid).orderBy("startMs","desc").limit(100).get();
 return snap.docs.map(d=>d.data()).sort((a,b)=>b.startMs-a.startMs);
});
export const startSession=endpoint(async req=>{
 const who=teacher(req),classId=identifier.parse(req.data.classId);
 const sessionRef=db.collection("classSessions").doc();
 return db.runTransaction(async tx=>{
  const classRef=db.doc(`classes/${classId}`),cl=(await tx.get(classRef)).data();
  if(!cl || !cl.teacherUids?.includes(who.uid)) throw new HttpsError("permission-denied","Assigned class required");
  if(cl.activeSessionId) {const previous=(await tx.get(db.doc(`classSessions/${cl.activeSessionId}`))).data(); if(previous?.state==="ACTIVE" && previous.endMs>Date.now()) throw new HttpsError("failed-precondition","This class already has an active session");}
  const roomRef=db.doc(`classrooms/${cl.roomId}`),room=(await tx.get(roomRef)).data();
  if(!room || !room.teacherUids?.includes(who.uid)) throw new HttpsError("permission-denied","Room assignment required");
  const conf=(await tx.get(roomRef.collection("presenceConfigs").doc(String(room.activeConfigVersion)))).data();
  const config=configSchema.parse(conf?.config);
  if(!config.calibrated) throw new HttpsError("failed-precondition","Calibrate the room before starting a production session");
  const beacons=z.array(beaconSchema).length(4).parse(room.beacons);
  const participants=z.array(identifier).max(200).parse(cl.studentUids ?? []);
  if(new Set(participants).size!==participants.length) throw new HttpsError("failed-precondition","Duplicate enrollment");
  const now=Date.now(),end=now+config.totalSlots*config.intervalMs;
  const session:Session={id:sessionRef.id,classId,title:cl.title,roomId:cl.roomId,startMs:now,endMs:end,config,beacons,state:"ACTIVE",
   teacherUids:cl.teacherUids,participantUids:participants,finalAfterMs:end+graceMs,demo:false};
  tx.set(sessionRef,session); tx.update(classRef,{activeSessionId:session.id});
  for(const uid of participants) {tx.set(sessionRef.collection("participants").doc(uid),{uid}); tx.set(sessionRef.collection("attendance").doc(uid),{uid,summary:aggregate([],config),final:false,updatedAtMs:now});}
  return session;
 });
});
export const endSession=endpoint(async req=>{
 const who=teacher(req),id=identifier.parse(req.data.sessionId);
 await db.runTransaction(async tx=>{
  const ref=db.doc(`classSessions/${id}`),data=(await tx.get(ref)).data() as Session|undefined;
  if(!data?.teacherUids.includes(who.uid)) throw new HttpsError("permission-denied","Assigned teacher required");
  if(data.state!=="ACTIVE") return;
  const end=Math.min(data.endMs,Date.now()); tx.update(ref,{state:"ENDED",endMs:end,finalAfterMs:end+graceMs});
 });return {ok:true};
});
export const submitCheckpoint=endpoint(async req=>{
 const who=identity(req); if(who.role!=="STUDENT") throw new HttpsError("permission-denied","Student required");
 const point=parseJson(req.data.checkpointJson,checkpointSchema);
 if(point.uid!==who.uid) throw new HttpsError("permission-denied","Own checkpoint only");
 const session=await getSession(point.sessionId,who.uid,who.uid);
 if(point.configVersion!==session.config.version || point.slot>=session.config.totalSlots) throw new HttpsError("invalid-argument","Configuration or slot mismatch");
 const allowed=new Set(session.beacons.map(b=>b.espId));
 if([...point.batch.wifi.observations,...point.batch.ble.observations].some(o=>!allowed.has(o.espId))) throw new HttpsError("invalid-argument","Unknown beacon");
 const result=evaluate(point.batch,session.config,session.beacons);
 const accepted={...point,result};
 const hash=createHash("sha256").update(JSON.stringify(accepted)).digest("hex");
 const ref=db.doc(`classSessions/${session.id}/checkpoints/${who.uid}_${point.slot}`);
 await db.runTransaction(async tx=>{
  const existing=await tx.get(ref);
  if(existing.exists) {if(existing.get("payloadHash")!==hash) throw new HttpsError("already-exists","Conflicting checkpoint retry"); return;}
  const current=(await tx.get(db.doc(`classSessions/${session.id}`))).data() as Session;
  try {validateCheckpointTiming(point.slot,point.capturedAtMs,current.startMs,current.endMs,current.config.intervalMs,Date.now(),graceMs);}
  catch {throw new HttpsError("failed-precondition","Checkpoint outside collection/upload window. Teacher review required.");}
  if(current.state!=="ACTIVE" && current.state!=="ENDED") throw new HttpsError("failed-precondition","Session closed for uploads");
  tx.create(ref,{...accepted,payloadHash:hash,receivedAtMs:Date.now()});
 });
 await refreshAttendance(session,who.uid,false);
 return {ok:true,status:result.status};
});
async function refreshAttendance(session:Session,uid:string,final:boolean) {
 // Read checkpoints inside the transaction so concurrent uploads cannot publish an older summary.
 await db.runTransaction(async tx=>{
  const ref=db.doc(`classSessions/${session.id}/attendance/${uid}`);
  const records=await tx.get(db.collection(`classSessions/${session.id}/checkpoints`).where("uid","==",uid));
  const previous=(await tx.get(ref)).data();
  const summary=aggregate(records.docs.map(d=>d.data() as Point),session.config);
  tx.set(ref,{uid,summary,final:final || previous?.final===true,overrideOutcome:previous?.overrideOutcome ?? null,updatedAtMs:Date.now()});
 });
}
export const listAttendance=endpoint(async req=>{
 const who=identity(req),session=await getSession(identifier.parse(req.data.sessionId),who.uid);
 if(who.role==="TEACHER" && session.teacherUids.includes(who.uid)) return (await db.collection(`classSessions/${session.id}/attendance`).get()).docs.map(d=>d.data());
 const own=await db.doc(`classSessions/${session.id}/attendance/${who.uid}`).get(); return own.exists?[own.data()]:[];
});
export const listCheckpoints=endpoint(async req=>{
 const who=identity(req),uid=identifier.parse(req.data.uid),session=await getSession(identifier.parse(req.data.sessionId),who.uid,uid);
 return (await db.collection(`classSessions/${session.id}/checkpoints`).where("uid","==",uid).get()).docs.map(d=>d.data()).sort((a,b)=>a.slot-b.slot);
});
export const overrideAttendance=endpoint(async req=>{
 const who=teacher(req),uid=identifier.parse(req.data.uid),id=identifier.parse(req.data.sessionId);
 const session=await getSession(id,who.uid,uid);
 if(!session.teacherUids.includes(who.uid)) throw new HttpsError("permission-denied","Assigned teacher required");
 const outcome=z.enum(["ELIGIBLE","BELOW_THRESHOLD","INSUFFICIENT_COVERAGE"]).parse(req.data.outcome),reason=z.string().trim().min(10).max(1000).parse(req.data.reason);
 if(Date.now()<session.endMs) throw new HttpsError("failed-precondition","End the session before making a final correction");
 await db.runTransaction(async tx=>{
  const ref=db.doc(`classSessions/${id}/attendance/${uid}`),previous=(await tx.get(ref)).data();
  if(!previous) throw new HttpsError("not-found","Attendance missing");
  tx.create(db.collection(`classSessions/${id}/auditEvents`).doc(),{actorUid:who.uid,targetUid:uid,previous,newOutcome:outcome,reason,atMs:Date.now()});
  tx.update(ref,{overrideOutcome:outcome,updatedAtMs:Date.now()});
 });return {ok:true};
});
export const roomConfig=endpoint(async req=>{
 const who=teacher(req),id=identifier.parse(req.data.roomId),room=(await db.doc(`classrooms/${id}`).get()).data();
 if(!room?.teacherUids?.includes(who.uid)) throw new HttpsError("permission-denied","Room assignment required");
 return (await db.doc(`classrooms/${id}/presenceConfigs/${room.activeConfigVersion}`).get()).get("config");
});
export const roomBeacons=endpoint(async req=>{
 const who=teacher(req),id=identifier.parse(req.data.roomId),room=(await db.doc(`classrooms/${id}`).get()).data();
 if(!room?.teacherUids?.includes(who.uid)) throw new HttpsError("permission-denied","Room assignment required");
 return z.array(beaconSchema).length(4).parse(room.beacons);
});
export const publishConfig=endpoint(async req=>{
 const who=teacher(req),id=identifier.parse(req.data.roomId),config=parseJson(req.data.configJson,configSchema);
 if(!config.calibrated || config.intervalMs!==300000 || config.totalSlots!==12 || config.minValid!==8 || config.insidePercent!==60) throw new HttpsError("invalid-argument","Production sessions require the approved 60-minute attendance policy");
 const samples=parseJson(req.data.samplesJson,z.array(z.object({label:z.string().min(1).max(100),inside:z.enum(["true","false"]),batch:z.string().max(500000)}).strict()).min(6).max(60));
 if(samples.filter(s=>s.inside==="true").length<3 || samples.filter(s=>s.inside==="false").length<3) throw new HttpsError("invalid-argument","Collect at least three inside and three outside recordings");
 await db.runTransaction(async tx=>{
  const roomRef=db.doc(`classrooms/${id}`),room=(await tx.get(roomRef)).data();
  if(!room?.teacherUids?.includes(who.uid)) throw new HttpsError("permission-denied","Room assignment required");
  if(config.version!==room.activeConfigVersion+1) throw new HttpsError("failed-precondition","Configuration changed. Refresh and try again");
  const beacons=z.array(beaconSchema).length(4).parse(room.beacons);
  const summaries=samples.map(s=>({label:s.label,inside:s.inside==="true",result:evaluate(batchSchema.parse(JSON.parse(s.batch)),config,beacons)}));
  if(summaries.some(s=>s.result.status==="INSUFFICIENT_DATA")) throw new HttpsError("invalid-argument","Calibration recordings need both radios and fresh evidence");
  tx.create(roomRef.collection("presenceConfigs").doc(String(config.version)),{config,createdBy:who.uid,createdAt:Timestamp.now(),calibration:summaries});
  tx.update(roomRef,{activeConfigVersion:config.version});
 });return {ok:true};
});
export const finalizeSessions=onSchedule({schedule:"every 5 minutes",region},async()=>{
 const now=Date.now();
 const active=await db.collection("classSessions").where("state","==","ACTIVE").where("endMs","<=",now).limit(100).get();
 for(const d of active.docs) await db.runTransaction(async tx=>{const latest=(await tx.get(d.ref)).data(); if(latest?.state==="ACTIVE") tx.update(d.ref,{state:"ENDED"});});
 const ended=await db.collection("classSessions").where("state","==","ENDED").where("finalAfterMs","<=",now).limit(100).get();
 for(const d of ended.docs) {
  const session=d.data() as Session;
  // Close ingestion first, then final summaries become stable. Retry incomplete finalization safely.
  await d.ref.update({state:"FINALIZING"});
  for(const uid of session.participantUids) await refreshAttendance(session,uid,true);
  await d.ref.update({state:"FINALIZED"});
 }
 const retry=await db.collection("classSessions").where("state","==","FINALIZING").limit(100).get();
 for(const d of retry.docs) {const session=d.data() as Session; for(const uid of session.participantUids) await refreshAttendance(session,uid,true); await d.ref.update({state:"FINALIZED"});}
});

async function assignedClass(req:CallableRequest,classId:string) {
 const who=teacher(req),cl=(await db.doc(`classes/${classId}`).get()).data();
 if(!cl?.teacherUids?.includes(who.uid)) throw new HttpsError("permission-denied","Assigned class required");
 return cl;
}
export const listRoster=endpoint(async req=>{
 const classId=identifier.parse(req.data.classId),cl=await assignedClass(req,classId);
 const uids:string[]=cl.studentUids ?? [],records=[];
 for(let offset=0;offset<uids.length;offset+=100) {
  const result=await getAuth().getUsers(uids.slice(offset,offset+100).map(uid=>({uid})));
  records.push(...result.users.map(user=>({uid:user.uid,name:user.displayName??"",email:user.email??""})));
 }
 return records;
});
export const enrollStudent=endpoint(async req=>{
 const who=teacher(req),classId=identifier.parse(req.data.classId);
 await assignedClass(req,classId);
 const email=z.string().email().max(254).parse(req.data.email);
 let student;
 try {student=await getAuth().getUserByEmail(email);} catch {throw new HttpsError("not-found","Student account not found. Ask the administrator to provision it.");}
 if(student.customClaims?.role!=="STUDENT" || student.disabled) throw new HttpsError("failed-precondition","An enabled, provisioned student account is required");
 await db.runTransaction(async tx=>{
  const ref=db.doc(`classes/${classId}`),cl=(await tx.get(ref)).data();
  if(!cl?.teacherUids?.includes(who.uid)) throw new HttpsError("permission-denied","Assigned class required");
  const members:string[]=cl.studentUids??[];
  if(members.includes(student.uid)) return;
  if(members.length>=200) throw new HttpsError("failed-precondition","Class roster limit reached");
  tx.update(ref,{studentUids:[...members,student.uid]});
  tx.set(ref.collection("members").doc(student.uid),{uid:student.uid,role:"STUDENT",enrolledBy:who.uid,enrolledAtMs:Date.now()});
 });return {ok:true};
});
export const removeEnrollment=endpoint(async req=>{
 const who=teacher(req),classId=identifier.parse(req.data.classId),uid=identifier.parse(req.data.uid);
 await db.runTransaction(async tx=>{
  const ref=db.doc(`classes/${classId}`),cl=(await tx.get(ref)).data();
  if(!cl?.teacherUids?.includes(who.uid)) throw new HttpsError("permission-denied","Assigned class required");
  tx.update(ref,{studentUids:(cl.studentUids??[]).filter((id:string)=>id!==uid)});
  tx.delete(ref.collection("members").doc(uid));
 });return {ok:true};
});

const {test}=require('node:test'); const assert=require('node:assert/strict');
const {initializeApp,getApps}=require('firebase-admin/app'); const {getFirestore}=require('firebase-admin/firestore'); const {getAuth}=require('firebase-admin/auth');
const {defaultConfig}=require('../lib/policy');
const enabled=Boolean(process.env.FIRESTORE_EMULATOR_HOST && process.env.FIREBASE_AUTH_EMULATOR_HOST && process.env.TEST_FUNCTIONS_EMULATOR);
const project='demo-classroom-presence';
async function account(name,role){
 const email=`${name}-${Date.now()}@example.test`,password='TestPassword123!';
 const result=await fetch(`http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/accounts:signUp?key=demo`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({email,password,returnSecureToken:true})}).then(r=>r.json());
 if(result.error) throw Error(JSON.stringify(result.error));
 await getAuth().setCustomUserClaims(result.localId,{role});
 const signed=await fetch(`http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=demo`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({email,password,returnSecureToken:true})}).then(r=>r.json());
 return {uid:result.localId,token:signed.idToken,email};
}
async function call(user,name,data={}){
 const response=await fetch(`http://127.0.0.1:5001/${project}/asia-south1/${name}`,{method:'POST',headers:{'Content-Type':'application/json',Authorization:`Bearer ${user.token}`},body:JSON.stringify({data})});
 const body=await response.json(); return {status:response.status,error:body.error,value:body.result?JSON.parse(body.result.json):null};
}
function scan(values){return {outcome:'SUCCESS',observations:values.map((rssiDbm,i)=>({espId:`CLASSROOM_ESP_${i+1}`,rssiDbm,elapsedMs:1000})),detail:''};}
function batch(values){return {wifi:scan(values),ble:scan(values),evaluatedElapsedMs:1000};}
test('Real callable endpoints enforce roles, membership, configuration, recomputation and audit',{skip:!enabled,timeout:120000},async()=>{
 if(!getApps().length) initializeApp({projectId:project});
 const db=getFirestore(); const teacher=await account('teacher','TEACHER'),student=await account('student','STUDENT'),other=await account('other','STUDENT'),stranger=await account('stranger','STUDENT');
 const classId='integration-class',roomId='ROOM_A101';
 const beacons=[1,2,3,4].map(number=>({espId:`CLASSROOM_ESP_${number}`,number,wifiSsid:`CP_ROOM_A101_ESP_${number}`}));
 await db.doc(`classes/${classId}`).set({title:'Integration class',roomId,teacherUids:[teacher.uid],studentUids:[student.uid,other.uid]});
 await db.doc(`classrooms/${roomId}`).set({teacherUids:[teacher.uid],beacons,activeConfigVersion:1});
 await db.doc(`classrooms/${roomId}/presenceConfigs/1`).set({config:defaultConfig});
 assert.equal((await call(student,'startSession',{classId})).status,403);
 assert.equal((await call(teacher,'startSession',{classId})).status,400); // uncalibrated gate
 assert.equal((await call(stranger,'listClasses')).value.length,0);
 const config={...defaultConfig,version:2,calibrated:true};
 const samples=Array.from({length:6},(_,i)=>({label:i<3?'Inside center':'Outside hallway',inside:String(i<3),batch:JSON.stringify(batch(Array(4).fill(i<3?-55:-95)))}));
 assert.equal((await call(student,'publishConfig',{roomId,configJson:JSON.stringify(config),samplesJson:JSON.stringify(samples)})).status,403);
 assert.equal((await call(teacher,'publishConfig',{roomId,configJson:JSON.stringify(config),samplesJson:JSON.stringify(samples)})).status,200);
 const started=await call(teacher,'startSession',{classId}); assert.equal(started.status,200,JSON.stringify(started)); const session=started.value;
 assert.equal((await call(teacher,'startSession',{classId})).status,400); // duplicate active session
 assert.equal((await call(stranger,'listSessions')).value.length,0);
 assert.equal((await call(stranger,'listAttendance',{sessionId:session.id})).status,403);
 const point={sessionId:session.id,uid:student.uid,slot:0,capturedAtMs:Date.now(),configVersion:2,batch:batch(Array(4).fill(-95)),result:{status:'INSIDE'},algorithmVersion:1,demo:false};
 assert.equal((await call(other,'submitCheckpoint',{checkpointJson:JSON.stringify(point)})).status,403);
 const accepted=await call(student,'submitCheckpoint',{checkpointJson:JSON.stringify(point)}); assert.equal(accepted.status,200,JSON.stringify(accepted)); assert.equal(accepted.value.status,'OUTSIDE');
 assert.equal((await call(student,'submitCheckpoint',{checkpointJson:JSON.stringify(point)})).status,200); // identical retry
 const conflict={...point,batch:batch(Array(4).fill(-55))};
 assert.equal((await call(student,'submitCheckpoint',{checkpointJson:JSON.stringify(conflict)})).status,409);
 assert.equal((await call(student,'submitCheckpoint',{checkpointJson:JSON.stringify({...point,demo:true})})).status,400);
 assert.equal((await call(student,'submitCheckpoint',{checkpointJson:JSON.stringify({...point,configVersion:1})})).status,400);
 assert.equal((await call(other,'listCheckpoints',{sessionId:session.id,uid:student.uid})).status,403);
 const mine=(await call(student,'listAttendance',{sessionId:session.id})).value;assert.equal(mine.length,1);assert.equal(mine[0].uid,student.uid);assert.equal(mine[0].summary.outside,1);
 const evidence=await call(teacher,'listCheckpoints',{sessionId:session.id,uid:student.uid});assert.equal(evidence.value[0].result.status,'OUTSIDE');
 assert.equal((await call(student,'endSession',{sessionId:session.id})).status,403);
 assert.equal((await call(teacher,'endSession',{sessionId:session.id})).status,200);
 assert.equal((await call(student,'overrideAttendance',{sessionId:session.id,uid:student.uid,outcome:'ELIGIBLE',reason:'Observed student in classroom'})).status,403);
 assert.equal((await call(teacher,'overrideAttendance',{sessionId:session.id,uid:student.uid,outcome:'ELIGIBLE',reason:'Observed student in classroom'})).status,200);
 const audits=await db.collection(`classSessions/${session.id}/auditEvents`).get();assert.equal(audits.size,1);assert.equal(audits.docs[0].get('actorUid'),teacher.uid);
 const overridden=(await call(student,'listAttendance',{sessionId:session.id})).value[0];assert.equal(overridden.overrideOutcome,'ELIGIBLE');assert.equal(overridden.summary.outside,1); // evidence preserved
 assert.equal((await call(student,'listRoster',{classId})).status,403);
 assert.equal((await call(teacher,'listRoster',{classId})).value.length,2);
 assert.equal((await call(student,'enrollStudent',{classId,email:stranger.email})).status,403);
 assert.equal((await call(teacher,'enrollStudent',{classId,email:stranger.email})).status,200);
 assert.equal((await call(teacher,'listRoster',{classId})).value.length,3);
 assert.equal((await call(teacher,'removeEnrollment',{classId,uid:other.uid})).status,200);
 assert.equal((await call(other,'listClasses')).value.length,0);
 assert.equal((await call(other,'listAttendance',{sessionId:session.id})).status,200); // historical snapshot is retained
 const functions=require('../lib/index.js');
 await db.doc(`classSessions/${session.id}`).update({finalAfterMs:Date.now()-1});
 await functions.finalizeSessions.run({scheduleTime:new Date().toISOString(),jobName:'test-finalization'});
 const finished=await db.doc(`classSessions/${session.id}`).get();assert.equal(finished.get('state'),'FINALIZED');
 const final=(await call(student,'listAttendance',{sessionId:session.id})).value[0];assert.equal(final.final,true);assert.equal(final.overrideOutcome,'ELIGIBLE');
 assert.equal((await call(student,'submitCheckpoint',{checkpointJson:JSON.stringify(point)})).status,200); // accepted retry remains idempotent after close
 assert.equal((await call(other,'submitCheckpoint',{checkpointJson:JSON.stringify({...point,uid:other.uid})})).status,400); // new ingestion is closed
});

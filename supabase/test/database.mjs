import EmbeddedPostgres from '../../.tools/supabase-cli/node_modules/embedded-postgres/dist/index.js';
import {readFile,mkdtemp} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
const root=new URL('../../',import.meta.url);
const dir=await mkdtemp(join(tmpdir(),'presence-postgres-'));
const pg=new EmbeddedPostgres({databaseDir:join(dir,'db'),user:'postgres',password:randomUUID(),port:55439,persistent:false,onLog:()=>{},onError:()=>{}});
let db,checks=0;
const check=(actual,expected)=>{assert.deepEqual(actual,expected);checks++;};
const config={version:1,calibrated:false,wifiInsideDbm:-72,bleInsideDbm:-75,wifiOutsideDbm:-82,bleOutsideDbm:-85,minDetected:3,minStrong:3,minWeak:3,maxSpreadDb:30,maxAgeMs:35000,bleDurationMs:25000,intervalMs:300000,totalSlots:12,minValid:8,insidePercent:60};
const beacons=[1,2,3,4].map(number=>({espId:`CLASSROOM_ESP_${number}`,number,wifiSsid:`CP_ROOM_A101_ESP_${number}`}));
const scan=values=>({outcome:'SUCCESS',observations:values.flatMap((rssiDbm,i)=>rssiDbm==null?[]:[{espId:beacons[i].espId,rssiDbm,elapsedMs:1000}]),detail:''});
const batch=(wifi,ble=wifi)=>({wifi:scan(wifi),ble:scan(ble),evaluatedElapsedMs:1000});
async function rpc(uid,operation,payload={}){
 await db.query('set role authenticated');
 try {await db.query("select set_config('request.jwt.claim.sub',$1,false)",[uid??'']);return (await db.query('select public.presence_api($1,$2::jsonb) value',[operation,JSON.stringify(payload)])).rows[0].value;}
 finally {await db.query('reset role');}
}
async function denied(uid,operation,payload,code){await assert.rejects(rpc(uid,operation,payload),e=>e.code===code);checks++;}
try {
 await pg.initialise();await pg.start();db=pg.getPgClient();await db.connect();
 await db.query(`create role anon; create role authenticated; create schema auth; create table auth.users(id uuid primary key,banned_until timestamptz); create function auth.uid() returns uuid language sql as $$select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid$$; grant usage on schema auth to authenticated; grant execute on function auth.uid() to authenticated;`);
 await db.query(await readFile(new URL('../migrations/202610030001_presence.sql',import.meta.url),'utf8'));
 await db.query(await readFile(new URL('../migrations/202610030003_session_states.sql',import.meta.url),'utf8'));
 await db.query(await readFile(new URL('../migrations/202610060001_session_timing.sql',import.meta.url),'utf8'));
 await db.query(await readFile(new URL('../migrations/202610060002_adaptive_checkpoints.sql',import.meta.url),'utf8'));
 await db.query(await readFile(new URL('../migrations/202610060003_student_directory.sql',import.meta.url),'utf8'));
 const teacher=randomUUID(),student=randomUUID(),other=randomUUID(),unassigned=randomUUID();
 for(const [uid,role] of [[teacher,'TEACHER'],[student,'STUDENT'],[other,'STUDENT']]){await db.query('insert into auth.users(id) values($1)',[uid]);await db.query('insert into presence_private.profiles(uid,role,email,name) values($1,$2,$3,$2)',[uid,role,`${uid}@example.test`]);}
 await db.query('insert into auth.users(id) values($1)',[unassigned]);
 const put=async(kind,id,data)=>db.query('insert into presence_private.documents values($1,$2,$3)',[kind,id,data]);
 await put('class','class-a101',{title:'A101',roomId:'ROOM_A101',teacherUids:[teacher],studentUids:[student]});
 await put('room','ROOM_A101',{teacherUids:[teacher],beacons,activeConfigVersion:1});await put('config','ROOM_A101:1',{config});
 for(const f of JSON.parse(await readFile(new URL('shared/presence-fixtures.json',root)))){
  const r=(await db.query('select presence_private.evaluate($1,$2,$3) r',[batch(f.wifi,f.ble),config,beacons].map(JSON.stringify))).rows[0].r;check(r.status,f.expected);
 }
 check((await rpc(teacher,'identity')).role,'TEACHER');
 await denied(null,'listClasses',{},'PT401');await denied(unassigned,'listClasses',{},'PT403');
 check((await rpc(other,'listClasses')).length,0);
 check((await db.query('select count(*)::int n from presence_private.students')).rows[0].n,2);
 check((await db.query('select count(*)::int n from presence_private.class_enrollments')).rows[0].n,1);
 check((await db.query('select enrolled_class_count from presence_private.students where uid=$1',[student])).rows[0].enrolled_class_count,1);
 await db.query('set role authenticated');await assert.rejects(db.query('select * from presence_private.profiles'),e=>e.code==='42501');await assert.rejects(db.query('select * from presence_private.students'),e=>e.code==='42501');await assert.rejects(db.query('select * from presence_private.class_enrollments'),e=>e.code==='42501');await assert.rejects(db.query('select presence_private.finalize_sessions()'),e=>e.code==='42501');await db.query('reset role');checks+=4;
 await denied(student,'startSession',{classId:'class-a101'},'PT403');await denied(teacher,'startSession',{classId:'class-a101'},'PT400');
 const calibrated={...config,calibrated:true,version:2};
 const samples=Array.from({length:6},(_,i)=>({label:i<3?'Inside':'Outside',inside:String(i<3),batch:JSON.stringify(batch(Array(4).fill(i<3?-55:-95)))}));
 const publish={roomId:'ROOM_A101',configJson:JSON.stringify(calibrated),samplesJson:JSON.stringify(samples)};
 await denied(student,'publishConfig',publish,'PT403');check((await rpc(teacher,'publishConfig',publish)).ok,true);
 const s=await rpc(teacher,'startSession',{classId:'class-a101'});check(s.participantUids,[student]);
 check((await rpc(student,'listSessionStates'))[0].id,s.id);
 check((await rpc(other,'listSessionStates')).length,0);
 await denied(teacher,'startSession',{classId:'class-a101'},'PT400');await denied(other,'listAttendance',{sessionId:s.id},'PT403');
 const point={sessionId:s.id,uid:student,slot:0,capturedAtMs:Date.now(),configVersion:2,batch:batch(Array(4).fill(-95)),result:{status:'INSIDE'},algorithmVersion:1,demo:false};
 const upload=p=>({checkpointJson:JSON.stringify(p)});
 check((await rpc(student,'submitCheckpoint',upload(point))).status,'OUTSIDE');
 check((await rpc(student,'submitCheckpoint',upload(point))).status,'OUTSIDE');
 await denied(student,'submitCheckpoint',upload({...point,batch:batch(Array(4).fill(-55))}),'PT409');
 await denied(student,'submitCheckpoint',upload({...point,uid:other}),'PT403');
 await denied(student,'submitCheckpoint',upload({...point,demo:true}),'PT400');
 await denied(student,'submitCheckpoint',upload({...point,slot:1}),'PT400');
 check((await rpc(student,'listAttendance',{sessionId:s.id}))[0].summary.outside,1);
 const fresh=structuredClone(point);fresh.batch.wifi.observations[0].espId='ROGUE';await denied(student,'submitCheckpoint',upload(fresh),'PT400');
 await denied(student,'endSession',{sessionId:s.id},'PT403');check((await rpc(teacher,'endSession',{sessionId:s.id})).ok,true);
 const override={sessionId:s.id,uid:student,outcome:'ELIGIBLE',reason:'Teacher observed student in classroom'};
 await denied(student,'overrideAttendance',override,'PT403');check((await rpc(teacher,'overrideAttendance',override)).ok,true);
 check((await db.query('select count(*)::int n from presence_private.audit')).rows[0].n,1);
 check((await rpc(teacher,'enrollStudent',{classId:'class-a101',email:`${other}@example.test`})).ok,true);
 check((await rpc(teacher,'listRoster',{classId:'class-a101'})).length,2);
 check((await db.query('select count(*)::int n from presence_private.student_enrollments where class_id=$1',['class-a101'])).rows[0].n,2);
 check((await rpc(teacher,'removeEnrollment',{classId:'class-a101',uid:student})).ok,true);
 check((await db.query('select count(*)::int n from presence_private.student_enrollments where class_id=$1',['class-a101'])).rows[0].n,1);
 check((await db.query('select enrolled_class_count from presence_private.students where uid=$1',[student])).rows[0].enrolled_class_count,0);
 check((await rpc(student,'listClasses')).length,0);check((await rpc(student,'listAttendance',{sessionId:s.id})).length,1);
 await db.query(`update presence_private.documents set data=data||jsonb_build_object('finalAfterMs',0) where kind='session' and id=$1`,[s.id]);
 await db.query('select presence_private.finalize_sessions()');await db.query('select presence_private.finalize_sessions()');
 const final=(await rpc(student,'listAttendance',{sessionId:s.id}))[0];check(final.final,true);check(final.overrideOutcome,'ELIGIBLE');check(final.summary.outside,1);
 check((await rpc(student,'submitCheckpoint',upload(point))).status,'OUTSIDE');await denied(student,'submitCheckpoint',upload({...point,slot:1,capturedAtMs:s.startMs+300000}),'PT400');
 // New session settings retain room calibration and snapshot the future roster.
 const badTimings=[{durationMinutes:10,minimumPresenceMinutes:5},{durationMinutes:32,minimumPresenceMinutes:20},
  {durationMinutes:125,minimumPresenceMinutes:20},{durationMinutes:30,minimumPresenceMinutes:31},
  {durationMinutes:30,minimumPresenceMinutes:0},{durationMinutes:"30",minimumPresenceMinutes:20},
  {durationMinutes:30,minimumPresenceMinutes:2.5},{durationMinutes:30},{minimumPresenceMinutes:20}];
 for(const timing of badTimings) await denied(teacher,'startSession',{classId:'class-a101',...timing},'PT400');
 await denied(other,'startSession',{classId:'class-a101',durationMinutes:30,minimumPresenceMinutes:20},'PT403');
 const roomBefore=await rpc(teacher,'roomConfig',{roomId:'ROOM_A101'});
 const timed=await rpc(teacher,'startSession',{classId:'class-a101',durationMinutes:30,minimumPresenceMinutes:17});
 check(timed.endMs-timed.startMs,1800000);check(timed.config.totalSlots,10);check(timed.config.minInsideSlots,6);
 check(timed.config.intervalMs,180000);
 check(timed.config.minValid,6);check(timed.participantUids,[other]);check(timed.config.version,roomBefore.version);
 check(await rpc(teacher,'roomConfig',{roomId:'ROOM_A101'}),roomBefore);
 const summary=async statuses=>(await db.query('select presence_private.aggregate($1,$2) r',[
  JSON.stringify(statuses.map(status=>({result:{status}}))),JSON.stringify(timed.config)])).rows[0].r;
 check((await summary(Array(6).fill('INSIDE'))).outcome,'ELIGIBLE');
 check((await summary(Array(5).fill('INSIDE'))).outcome,'INSUFFICIENT_COVERAGE');
 check((await summary([...Array(5).fill('INSIDE'),'UNCERTAIN','OUTSIDE','INSUFFICIENT_DATA'])).outcome,'BELOW_THRESHOLD');
 check((await summary(['INSIDE','INSIDE','INSIDE','INSIDE','INSUFFICIENT_DATA'])).inside,4);
 check((await summary(['INSUFFICIENT_DATA','INSUFFICIENT_DATA'])).valid,0);
 await denied(teacher,'startSession',{classId:'class-a101',durationMinutes:30,minimumPresenceMinutes:20},'PT400');
 // Publishing a changed room version does not change already-started session thresholds or timing.
 const newer={...calibrated,version:3,wifiInsideDbm:-70,minInsideSlots:0};
 check((await rpc(teacher,'publishConfig',{...publish,configJson:JSON.stringify(newer)})).ok,true);
 check((await rpc(teacher,'listSessions')).find(x=>x.id===timed.id).config.version,2);
 await rpc(teacher,'endSession',{sessionId:timed.id});
 const next=await rpc(teacher,'startSession',{classId:'class-a101',durationMinutes:120,minimumPresenceMinutes:120});
 check(next.config.version,3);check(next.config.minInsideSlots,40);check(next.config.totalSlots,40);
 check(next.endMs-next.startMs,7200000);
 await denied(teacher,'publishConfig',{...publish,configJson:JSON.stringify({...newer,version:4,minInsideSlots:4})},'PT400');
 // High slot indices must pass the API and table constraint, while old snapshots stay bounded.
 const shiftedStart=Date.now()-39*next.config.intervalMs;
 await db.query(`update presence_private.documents set data=data||jsonb_build_object('startMs',$2::bigint,'endMs',$3::bigint,'finalAfterMs',$4::bigint) where kind='session' and id=$1`,
  [next.id,shiftedStart,shiftedStart+7200000,shiftedStart+8100000]);
 const highPoint={...point,sessionId:next.id,uid:other,slot:39,capturedAtMs:shiftedStart+39*next.config.intervalMs,configVersion:3,batch:batch(Array(4).fill(-55))};
 check((await rpc(other,'submitCheckpoint',upload(highPoint))).status,'INSIDE');
 await denied(other,'submitCheckpoint',upload({...highPoint,slot:40}),'PT400');
 await denied(student,'submitCheckpoint',upload({...point,slot:24}),'PT400');
 await rpc(teacher,'endSession',{sessionId:next.id});
 // Check every supported duration, including non-divisible intervals and a full-duration minimum.
 for(let minutes=15;minutes<=120;minutes+=5){
  const created=await rpc(teacher,'startSession',{classId:'class-a101',durationMinutes:minutes,minimumPresenceMinutes:minutes});
  const count=Math.max(5,Math.ceil(minutes/3));
  check(created.config.totalSlots,count);check(created.config.minInsideSlots,count);
  check(created.config.intervalMs,Math.floor(minutes*60000/count));
  check(created.endMs-created.startMs,minutes*60000);
  await rpc(teacher,'endSession',{sessionId:created.id});
 }
 await db.query('update presence_private.profiles set disabled=true where uid=$1',[student]);await denied(student,'listSessions',{},'PT403');
 console.log(`PASS: ${checks} PostgreSQL checks (fixtures, real roles, private tables, calibration, server recomputation, timing, retries, roster snapshots, audited overrides, finalization and disabled accounts).`);
} finally {if(db)await db.end();await pg.stop();}

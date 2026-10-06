// Creates disposable accounts/classes, tests hosted Auth/API + Android, and removes test data.
import {readFile,writeFile} from 'node:fs/promises';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {randomBytes,randomUUID} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';
const root=new URL('../../',import.meta.url),privateDir=new URL('.tools/supabase-private/',root);
const s=JSON.parse(await readFile(new URL('project.json',privateDir),'utf8')),base=`https://${s.ref}.supabase.co`;
const run=promisify(execFile),env={...process.env,PATH:fileURLToPath(new URL('.tools/node-v22.15.0-darwin-arm64/bin',root))+':'+process.env.PATH};
async function sql(query){const file=new URL('live-test.sql',privateDir);await writeFile(file,query,{mode:0o600});const {stdout}=await run(fileURLToPath(new URL('.tools/supabase-cli/node_modules/.bin/supabase',root)),['db','query','--linked','--project-ref',s.ref,'--file',fileURLToPath(file),'--agent','no','-o','json'],{env,cwd:fileURLToPath(root),maxBuffer:4*1024*1024});return JSON.parse(stdout.slice(stdout.indexOf('[')));}
async function request(path,body,{token=s.serviceKey,key=s.serviceKey,method='POST'}={}){
 const res=await fetch(base+path,{method,headers:{apikey:key,Authorization:`Bearer ${token}`,'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});
 const data=await res.json();return {status:res.status,data};
}
const rpc=(token,operation,payload={})=>request('/rest/v1/rpc/presence_api',{operation,payload},{token,key:s.anonKey});
const literal=v=>"'"+String(v).replaceAll("'","''")+"'";
const suffix=randomUUID().replaceAll('-',''),classId=`test-${suffix}`,roomId=`ROOM_TEST_${suffix}`;
let teacher,student;
try {
 const teacherEmail=`teacher-${suffix}@example.test`,studentEmail=`student-${suffix}@example.test`;
 const teacherPassword=randomBytes(24).toString('base64url'),studentPassword=randomBytes(24).toString('base64url');
 teacher=(await request('/auth/v1/admin/users',{email:teacherEmail,password:randomBytes(24).toString('base64url'),email_confirm:true})).data;
 student=(await request('/auth/v1/admin/users',{email:studentEmail,password:studentPassword,email_confirm:true})).data;
 assert.ok(teacher.id);assert.ok(student.id);
 const beacons=[1,2,3,4].map(number=>({espId:`CLASSROOM_ESP_${number}`,number,wifiSsid:`CP_ROOM_A101_ESP_${number}`}));
 const config={version:1,calibrated:false,wifiInsideDbm:-72,bleInsideDbm:-75,wifiOutsideDbm:-82,bleOutsideDbm:-85,minDetected:3,minStrong:3,minWeak:3,maxSpreadDb:30,maxAgeMs:35000,bleDurationMs:25000,intervalMs:300000,totalSlots:12,minValid:8,insidePercent:60};
 const cl={title:'Disposable cloud test',roomId,teacherUids:[teacher.id],studentUids:[student.id]},room={teacherUids:[teacher.id],beacons,activeConfigVersion:1};
 let query=`begin; insert into presence_private.profiles(uid,role,email,name) values(${literal(teacher.id)}::uuid,'TEACHER',${literal(teacherEmail)},'Test teacher'),(${literal(student.id)}::uuid,'STUDENT',${literal(studentEmail)},'Test student');`;
 for(const [kind,id,data] of [['class',classId,cl],['room',roomId,room],['config',roomId+':1',{config}]])query+=`insert into presence_private.documents values(${literal(kind)},${literal(id)},${literal(JSON.stringify(data))}::jsonb);`;
 await sql(query+'commit;');
 const signed=await request('/auth/v1/token?grant_type=password',{email:studentEmail,password:studentPassword},{token:s.anonKey,key:s.anonKey});assert.equal(signed.status,200,signed.status===200?'':JSON.stringify(signed.data));
 const token=signed.data.access_token;
 assert.equal((await rpc(token,'identity')).data.role,'STUDENT');
 assert.equal((await rpc(token,'startSession',{classId})).status,403);
 assert.equal((await request('/auth/v1/user',{data:{role:'TEACHER'}},{token,key:s.anonKey,method:'PUT'})).status,200);
 assert.equal((await rpc(token,'identity')).data.role,'STUDENT');
 assert.equal((await request('/rest/v1/profiles?select=*',undefined,{token,key:s.anonKey,method:'GET'})).status,404);
 const refreshed=await request('/auth/v1/token?grant_type=refresh_token',{refresh_token:signed.data.refresh_token},{token:s.anonKey,key:s.anonKey});assert.equal(refreshed.status,200);
 assert.equal((await rpc(refreshed.data.access_token,'identity')).data.role,'STUDENT');
 const link=(await request('/auth/v1/admin/generate_link',{type:'recovery',email:teacherEmail})).data;
 const setupLink=link.action_link??link.properties?.action_link;assert.ok(setupLink);
 const args=[':app:connectedDebugAndroidTest','-Pandroid.testInstrumentationRunnerArguments.class=com.classroompresence.app.SupabaseCloudTest',`-Pandroid.testInstrumentationRunnerArguments.cloudTeacherEmail=${teacherEmail}`,`-Pandroid.testInstrumentationRunnerArguments.cloudStudentEmail=${studentEmail}`,`-Pandroid.testInstrumentationRunnerArguments.cloudPassword=${teacherPassword}`,`-Pandroid.testInstrumentationRunnerArguments.cloudStudentPassword=${studentPassword}`,`-Pandroid.testInstrumentationRunnerArguments.cloudSetupLink=${setupLink}`,`-Pandroid.testInstrumentationRunnerArguments.cloudClassId=${classId}`];
 const result=await run(fileURLToPath(new URL('scripts/build-android.sh',root)),args,{cwd:fileURLToPath(root),env,maxBuffer:8*1024*1024});
 await writeFile(new URL('live-android-test.log',privateDir),result.stdout,{mode:0o600});
 const teacherSigned=await request('/auth/v1/token?grant_type=password',{email:teacherEmail,password:teacherPassword},{token:s.anonKey,key:s.anonKey});assert.equal(teacherSigned.status,200);
 const teacherToken=teacherSigned.data.access_token,studentToken=refreshed.data.access_token;
 const scan=value=>({outcome:'SUCCESS',observations:beacons.map(b=>({espId:b.espId,rssiDbm:value,elapsedMs:1000})),detail:''});
 const batch=value=>({wifi:scan(value),ble:scan(value),evaluatedElapsedMs:1000});
 const samples=Array.from({length:6},(_,i)=>({label:i<3?'Test inside':'Test outside',inside:String(i<3),batch:JSON.stringify(batch(i<3?-55:-95))}));
 assert.equal((await rpc(teacherToken,'publishConfig',{roomId,configJson:JSON.stringify({...config,calibrated:true,version:2}),samplesJson:JSON.stringify(samples)})).status,200);
 const started=await rpc(teacherToken,'startSession',{classId});assert.equal(started.status,200);const session=started.data;
 const point={sessionId:session.id,uid:student.id,slot:0,capturedAtMs:Date.now(),configVersion:2,batch:batch(-55),result:{status:'OUTSIDE'},algorithmVersion:1,demo:false};
 const payload={checkpointJson:JSON.stringify(point)};
 const accepted=await rpc(studentToken,'submitCheckpoint',payload);assert.equal(accepted.status,200);assert.equal(accepted.data.status,'INSIDE');
 assert.equal((await rpc(studentToken,'submitCheckpoint',payload)).status,200);
 assert.equal((await rpc(studentToken,'submitCheckpoint',{checkpointJson:JSON.stringify({...point,batch:batch(-95)})})).status,409);
 assert.equal((await rpc(teacherToken,'listSessionStates')).data.some(r=>r.id===session.id),true);
 const attendance=await rpc(teacherToken,'listAttendance',{sessionId:session.id});assert.equal(attendance.data[0].summary.inside,1);
 assert.equal((await rpc(teacherToken,'endSession',{sessionId:session.id})).status,200);
 assert.equal((await rpc(teacherToken,'overrideAttendance',{sessionId:session.id,uid:student.id,outcome:'ELIGIBLE',reason:'Disposable integration test correction'})).status,200);
 await sql(`update presence_private.documents set data=data||'{"finalAfterMs":0}'::jsonb where kind='session' and id=${literal(session.id)}; select presence_private.finalize_sessions();`);
 const final=await rpc(studentToken,'listAttendance',{sessionId:session.id});assert.equal(final.data[0].final,true);assert.equal(final.data[0].overrideOutcome,'ELIGIBLE');assert.equal(final.data[0].summary.inside,1);
 assert.equal((await rpc(studentToken,'submitCheckpoint',payload)).status,200);
 console.log('PASS: hosted Auth/roles, metadata escalation denial, private tables, refresh tokens; Android 16 password setup/login/encrypted persistence/calibration gates; live calibration, session snapshots, server recomputation, retries, audited correction and finalization.');
} catch(error){
 await writeFile(new URL('live-android-test.log',privateDir),error.stdout??String(error.stack),{mode:0o600});
 console.error('Live test failed; details are in .tools/supabase-private/live-android-test.log');process.exitCode=1;
} finally {
 await sql(`begin; delete from presence_private.checkpoints where session_id in(select id from presence_private.documents where kind='session' and data->>'classId'=${literal(classId)}); delete from presence_private.attendance where session_id in(select id from presence_private.documents where kind='session' and data->>'classId'=${literal(classId)}); delete from presence_private.audit where session_id in(select id from presence_private.documents where kind='session' and data->>'classId'=${literal(classId)}); delete from presence_private.documents where (kind='class' and id=${literal(classId)}) or (kind='room' and id=${literal(roomId)}) or (kind='config' and id like ${literal(roomId+':%')}) or (kind='session' and data->>'classId'=${literal(classId)}); commit;`);
 for(const user of [teacher,student])if(user?.id)await request('/auth/v1/admin/users/'+user.id,undefined,{method:'DELETE'});
}

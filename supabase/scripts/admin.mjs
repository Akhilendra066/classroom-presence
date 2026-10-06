// Trusted local administration. CLI account credentials stay in the CLI store.
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {randomBytes} from 'node:crypto';
import {fileURLToPath} from 'node:url';
const root=new URL('../../',import.meta.url),privateDir=new URL('.tools/supabase-private/',root);
const org='spslyxvopkidccihcytf'; // Newly created Free organization; no billing/upgrade commands.
async function cli(args){
 try {
  const {stdout}=await promisify(execFile)(fileURLToPath(new URL('.tools/supabase-cli/node_modules/.bin/supabase',root)),[...args,'--agent','no','-o','json'],{cwd:fileURLToPath(root),env:{...process.env,PATH:fileURLToPath(new URL('.tools/node-v22.15.0-darwin-arm64/bin',root))+':'+process.env.PATH},maxBuffer:8*1024*1024});
  const starts=[stdout.indexOf('{'),stdout.indexOf('[')].filter(n=>n>=0);
  return starts.length?JSON.parse(stdout.slice(Math.min(...starts))):null;
 }catch(error){throw Error((error.stderr||'Supabase CLI operation failed.').replace(/sbp_[A-Za-z0-9_-]+/g,'[redacted]').slice(0,1200));}
}
async function state(){return JSON.parse(await readFile(new URL('project.json',privateDir),'utf8'));}
async function save(value){await mkdir(privateDir,{recursive:true,mode:0o700});await writeFile(new URL('project.json',privateDir),JSON.stringify(value,null,2),{mode:0o600});}
async function sql(ref,query){await mkdir(privateDir,{recursive:true,mode:0o700});const file=new URL('administration.sql',privateDir);await writeFile(file,query,{mode:0o600});return cli(['db','query','--linked','--project-ref',ref,'--file',fileURLToPath(file)]);}
async function auth(s,path,body,method='POST'){
 const res=await fetch(`https://${s.ref}.supabase.co/auth/v1${path}`,{method,headers:{apikey:s.serviceKey,Authorization:`Bearer ${s.serviceKey}`,'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});
 const data=await res.json();if(!res.ok)throw Error(`Auth ${res.status}: ${data.msg??data.message??'Operation failed'}`);return data;
}
const literal=value=>"'"+String(value).replaceAll("'","''")+"'";
async function main(){
 const action=process.argv[2];
 if(action==='create'){
  const projects=await cli(['projects','list']);if(projects.some(p=>p.organization_id===org && p.name==='Classroom Presence'))throw Error('Project already exists; inspect before reusing.');
  const password=randomBytes(32).toString('base64url');await save({organization:org,databasePassword:password});
  const p=await cli(['projects','create','Classroom Presence','--org-id',org,'--db-password',password,'--region','ap-south-1']);
  await save({ref:p.id,organization:org,databasePassword:password});
  await writeFile(new URL('supabase/project.json',root),JSON.stringify({ref:p.id,organization:org,region:p.region,plan:'free'},null,2));
  console.log(JSON.stringify({projectRef:p.id,status:p.status,region:p.region,plan:'free'}));return;
 }
 const s=await state();
 if(action==='status'){const p=(await cli(['projects','list'])).find(p=>p.id===s.ref);console.log(JSON.stringify({ref:p?.id,status:p?.status,region:p?.region,organization:s.organization,plan:'free'}));return;}
 if(action==='deploy'){
  const installed=await sql(s.ref,"select to_regprocedure('public.presence_api(text,jsonb)') is not null as installed");
  if(!installed[0]?.installed)await sql(s.ref,'begin;\n'+await readFile(new URL('supabase/migrations/202610030001_presence.sql',root),'utf8')+'\ncommit;');
  await sql(s.ref,await readFile(new URL('supabase/migrations/202610030002_cron.sql',root),'utf8'));
  const states=await sql(s.ref,"select to_regprocedure('presence_private.presence_api_base(text,jsonb)') is not null as installed");
  if(!states[0]?.installed)await sql(s.ref,'begin;\n'+await readFile(new URL('supabase/migrations/202610030003_session_states.sql',root),'utf8')+'\ncommit;');
  await sql(s.ref,'begin;\n'+await readFile(new URL('supabase/migrations/202610060001_session_timing.sql',root),'utf8')+'\ncommit;');
  await sql(s.ref,'begin;\n'+await readFile(new URL('supabase/migrations/202610060002_adaptive_checkpoints.sql',root),'utf8')+'\ncommit;');
  await sql(s.ref,'begin;\n'+await readFile(new URL('supabase/migrations/202610060003_student_directory.sql',root),'utf8')+'\ncommit;');
  await cli(['config','push','--project-ref',s.ref,'--yes']);console.log('Attendance API, private tables, five-minute Cron and Authentication settings deployed.');return;
 }
 if(action==='deploy-session-settings'){
  await sql(s.ref,'begin;\n'+await readFile(new URL('supabase/migrations/202610060001_session_timing.sql',root),'utf8')+'\ncommit;');
  await sql(s.ref,'begin;\n'+await readFile(new URL('supabase/migrations/202610060002_adaptive_checkpoints.sql',root),'utf8')+'\ncommit;');
  console.log('Teacher session timing deployed; existing calibration and session records preserved.');return;
 }
 if(action==='deploy-adaptive-checkpoints'){
  await sql(s.ref,'begin;\n'+await readFile(new URL('supabase/migrations/202610060002_adaptive_checkpoints.sql',root),'utf8')+'\ncommit;');
  console.log('Adaptive checkpoints deployed; saved calibration and existing schedules preserved.');return;
 }
 if(action==='deploy-student-schema'){
  await sql(s.ref,'begin;\n'+await readFile(new URL('supabase/migrations/202610060003_student_directory.sql',root),'utf8')+'\ncommit;');
  console.log('Student directory and relational class enrollments deployed; existing students were backfilled.');return;
 }
 if(action==='verify-student-schema'){
  console.log(JSON.stringify(await sql(s.ref,"select to_regclass('presence_private.class_enrollments') is not null as enrollment_table, to_regclass('presence_private.students') is not null as student_directory, (select count(*)::int from presence_private.students) as students, (select count(*)::int from presence_private.class_enrollments) as enrollments, not has_schema_privilege('authenticated','presence_private','USAGE') as client_access_blocked;")));return;
 }
 if(action==='verify-session-settings'){
  console.log(JSON.stringify(await sql(s.ref,"select to_regprocedure('presence_private.start_timed_session(jsonb)') is not null as timing_installed, position('checkpoint_count:=greatest(5,(duration+2)/3)' in pg_get_functiondef('presence_private.start_timed_session(jsonb)'::regprocedure))>0 as adaptive_scheduling, not has_function_privilege('authenticated','presence_private.start_timed_session(jsonb)','execute') as direct_private_access_blocked, presence_private.aggregate('[{\"result\":{\"status\":\"INSIDE\"}},{\"result\":{\"status\":\"UNCERTAIN\"}}]'::jsonb,'{\"minValid\":2,\"totalSlots\":3,\"insidePercent\":1,\"minInsideSlots\":2}'::jsonb)->>'outcome' as minimum_enforced;")));return;
 }
 if(action==='configure'){
  const keys=await cli(['projects','api-keys','--project-ref',s.ref]);const anon=keys.find(k=>k.name==='anon')?.api_key,service=keys.find(k=>k.name==='service_role')?.api_key;
  if(!anon || !service)throw Error('Project API keys are not ready.');await save({...s,anonKey:anon,serviceKey:service});
  await writeFile(new URL('mobile/supabase.properties',root),`SUPABASE_URL=https://${s.ref}.supabase.co\nSUPABASE_ANON_KEY=${anon}\n`);console.log('Android public configuration saved; administrator key stays in private storage.');return;
 }
 if(action==='accounts'){
  const listed=await auth(s,'/admin/users?page=1&per_page=1000',undefined,'GET'),records=[];
  for(const [email,role] of [['hh5379259@gmail.com','TEACHER'],['akhilendrasingh066@gmail.com','STUDENT']]){
   let user=listed.users.find(u=>u.email===email);if(!user)user=await auth(s,'/admin/users',{email,email_confirm:true,password:randomBytes(32).toString('base64url'),user_metadata:{name:role==='TEACHER'?'Teacher':'Student'}});
   records.push({uid:user.id,email,role,name:role==='TEACHER'?'Teacher':'Student'});
  }
  const users=await sql(s.ref,`select uid::text,role from presence_private.profiles where uid in (${records.map(r=>literal(r.uid)+'::uuid').join(',')});`);
  if(users.some(u=>records.find(r=>r.uid===u.uid)?.role!==u.role))throw Error('Existing role conflicts; refusing to change trusted access.');
  const roles=records.map(r=>`insert into presence_private.profiles(uid,role,name,email) values(${literal(r.uid)}::uuid,${literal(r.role)},${literal(r.name)},${literal(r.email)}) on conflict(uid) do update set name=excluded.name,email=excluded.email;`).join('\n');
  const config={version:1,calibrated:false,wifiInsideDbm:-72,bleInsideDbm:-75,wifiOutsideDbm:-82,bleOutsideDbm:-85,minDetected:3,minStrong:3,minWeak:3,maxSpreadDb:30,maxAgeMs:35000,bleDurationMs:25000,intervalMs:300000,totalSlots:12,minValid:8,insidePercent:60};
  const beacons=[1,2,3,4].map(number=>({espId:`CLASSROOM_ESP_${number}`,number,wifiSsid:`CP_ROOM_A101_ESP_${number}`}));
  const cl={title:'Classroom A101',roomId:'ROOM_A101',teacherUids:[records[0].uid],studentUids:[records[1].uid]},room={teacherUids:[records[0].uid],beacons,activeConfigVersion:1};
  let query='begin;\n'+roles;
  for(const [kind,id,data] of [['class','class-a101',cl],['room','ROOM_A101',room],['config','ROOM_A101:1',{config}]])query+=`\ninsert into presence_private.documents values(${literal(kind)},${literal(id)},${literal(JSON.stringify(data))}::jsonb) on conflict(kind,id) do nothing;`;
  await sql(s.ref,query+'\ncommit;');await save({...s,accounts:records});console.log(JSON.stringify({accounts:records,room:'ROOM_A101',class:'class-a101',calibrated:false}));return;
 }
 if(action==='password-links'){
  const entries=[];for(const r of s.accounts){
   const link=await auth(s,'/admin/generate_link',{type:'recovery',email:r.email});
   const actionLink=link.action_link??link.properties?.action_link;
   const hash=link.hashed_token??link.properties?.hashed_token??(actionLink?new URL(actionLink).searchParams.get('token'):null);
   if(!hash)throw Error('Password link generation failed.');
   // Android redeems token_hash by POST. Omit the GET token parameter so browser
   // link previews cannot consume this one-time credential before the user does.
   const value=new URL(`https://${s.ref}.supabase.co/auth/v1/verify`);
   value.searchParams.set('token_hash',hash);value.searchParams.set('type','recovery');
   entries.push(`## ${r.role}\nEmail: ${r.email}\n\n${value}\n`);
  }
  await writeFile(new URL('account-password-links.md',privateDir),'# Private password setup links\n\nGenerated: '+new Date().toISOString()+'\n\nPaste your own complete link into Android → Set password with administrator link. These are app-only links; opening them in a browser will not set your password. Links are single-use and use the project email OTP expiry (default one hour). Keep this file private.\n\n'+entries.join('\n'),{mode:0o600});console.log('Private account links saved to .tools/supabase-private/account-password-links.md');return;
 }
 if(action==='verify'){
  const settings=await auth(s,'/settings',undefined,'GET');
  console.log(JSON.stringify({emailLogin:settings.external?.email,publicSignupDisabled:settings.disable_signup,
   cron:await sql(s.ref,"select jobname,schedule,active from cron.job where jobname='presence-finalize';"),
   cronRuns:await sql(s.ref,"select status,end_time from cron.job_run_details where jobid=(select jobid from cron.job where jobname='presence-finalize') order by start_time desc limit 3;"),
   roles:await sql(s.ref,'select role,count(*)::int users from presence_private.profiles group by role;'),
   access:await sql(s.ref,"select has_schema_privilege('authenticated','presence_private','USAGE') as private_schema_access,has_function_privilege('anon','public.presence_api(text,jsonb)','EXECUTE') as anonymous_api_access;"),
   room:await sql(s.ref,"select data->>'activeConfigVersion' as version from presence_private.documents where kind='room' and id='ROOM_A101';"),
   configuration:await sql(s.ref,"select data->'config'->'calibrated' as calibrated from presence_private.documents where kind='config' and id='ROOM_A101:1';")
  }));return;
 }
 throw Error('Choose create, status, deploy, deploy-session-settings, deploy-student-schema, configure, accounts, password-links, verify-student-schema or verify.');
}
main().catch(error=>{console.error(error.message);process.exitCode=1;});

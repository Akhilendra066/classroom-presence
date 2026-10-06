-- Trusted attendance API. Clients have no table access and cannot assign roles.
create schema if not exists presence_private;
revoke all on schema presence_private from public, anon, authenticated;
create table presence_private.profiles (
 uid uuid primary key references auth.users(id) on delete cascade,
 role text not null check(role in ('TEACHER','STUDENT')),
 name text not null default '', email text not null, disabled boolean not null default false
);
create table presence_private.documents (
 kind text not null check(kind in ('class','room','config','session')),
 id text not null, data jsonb not null, primary key(kind,id)
);
create table presence_private.checkpoints (
 session_id text not null, uid uuid not null, slot integer not null check(slot between 0 and 23),
 point jsonb not null, primary key(session_id,uid,slot)
);
create table presence_private.attendance (
 session_id text not null, uid uuid not null, data jsonb not null, primary key(session_id,uid)
);
create table presence_private.audit (
 id bigint generated always as identity primary key, session_id text not null, data jsonb not null
);
alter table presence_private.profiles enable row level security;
alter table presence_private.documents enable row level security;
alter table presence_private.checkpoints enable row level security;
alter table presence_private.attendance enable row level security;
alter table presence_private.audit enable row level security;
create index documents_teachers on presence_private.documents using gin ((data->'teacherUids'));
create index documents_students on presence_private.documents using gin ((data->'studentUids'));
create index documents_participants on presence_private.documents using gin ((data->'participantUids'));
create index documents_session_end on presence_private.documents ((data->>'state'), ((data->>'finalAfterMs')::bigint)) where kind='session';

create function presence_private.fail(code text, message text) returns void language plpgsql set search_path='' as $$
begin raise exception using errcode=code, message=message; end $$;
create function presence_private.now_ms() returns bigint language sql volatile set search_path='' as $$
 select floor(extract(epoch from clock_timestamp())*1000)::bigint
$$;
create function presence_private.integer_value(v jsonb, low numeric, high numeric) returns boolean language sql immutable set search_path='' as $$
 select case when jsonb_typeof(v)='number' then (v::text)::numeric between low and high and trunc((v::text)::numeric)=(v::text)::numeric else false end
$$;
create function presence_private.validate_config(c jsonb) returns void language plpgsql set search_path='' as $$
declare k text; lo integer; hi integer;
begin
 if jsonb_typeof(c) is distinct from 'object' or (select count(*) from jsonb_object_keys(c))<>16 or jsonb_typeof(c->'calibrated') is distinct from 'boolean' then perform presence_private.fail('PT400','Invalid configuration'); end if;
 for k,lo,hi in select * from (values
 ('version',1,2147483647),('wifiInsideDbm',-127,-1),('bleInsideDbm',-127,-1),('wifiOutsideDbm',-127,-1),('bleOutsideDbm',-127,-1),
 ('minDetected',1,4),('minStrong',1,4),('minWeak',1,4),('maxSpreadDb',1,100),('maxAgeMs',1000,60000),('bleDurationMs',1000,30000),
 ('intervalMs',30001,600000),('totalSlots',1,24),('minValid',1,24),('insidePercent',1,100)) t(k,lo,hi)
 loop if not presence_private.integer_value(c->k,lo,hi) then perform presence_private.fail('PT400','Invalid configuration field: '||k); end if; end loop;
 -- There are 16 fields: the exact-key check below rejects unknown fields.
 if c - array['version','calibrated','wifiInsideDbm','bleInsideDbm','wifiOutsideDbm','bleOutsideDbm','minDetected','minStrong','minWeak','maxSpreadDb','maxAgeMs','bleDurationMs','intervalMs','totalSlots','minValid','insidePercent'] <> '{}'::jsonb
 or (c->>'wifiOutsideDbm')::int >= (c->>'wifiInsideDbm')::int or (c->>'bleOutsideDbm')::int >= (c->>'bleInsideDbm')::int
 or (c->>'minStrong')::int < (c->>'minDetected')::int or (c->>'minWeak')::int < (c->>'minDetected')::int
 or (c->>'minValid')::int > (c->>'totalSlots')::int or (c->>'intervalMs')::int <= (c->>'bleDurationMs')::int then perform presence_private.fail('PT400','Invalid threshold or policy'); end if;
end $$;
create function presence_private.validate_batch(b jsonb) returns void language plpgsql set search_path='' as $$
declare radio text; scan jsonb; o jsonb;
begin
 if jsonb_typeof(b) is distinct from 'object' or b-array['wifi','ble','evaluatedElapsedMs']<>'{}'::jsonb or not presence_private.integer_value(b->'evaluatedElapsedMs',0,9007199254740991) then perform presence_private.fail('PT400','Invalid scan batch'); end if;
 foreach radio in array array['wifi','ble'] loop
 scan:=b->radio;
 if jsonb_typeof(scan) is distinct from 'object' or scan-array['outcome','observations','detail']<>'{}'::jsonb or coalesce(scan->>'outcome','') not in ('SUCCESS','DISABLED','PERMISSION_DENIED','FAILED','TIMEOUT')
 or jsonb_typeof(scan->'observations') is distinct from 'array' then perform presence_private.fail('PT400','Invalid radio scan'); end if;
 if jsonb_array_length(scan->'observations')>5000 or (scan ? 'detail' and (jsonb_typeof(scan->'detail')<>'string' or length(scan->>'detail')>200)) then perform presence_private.fail('PT400','Scan size exceeded'); end if;
 for o in select value from jsonb_array_elements(scan->'observations') loop
 if jsonb_typeof(o) is distinct from 'object' or o-array['espId','rssiDbm','elapsedMs']<>'{}'::jsonb or jsonb_typeof(o->'espId') is distinct from 'string' or length(o->>'espId')>64
 or not presence_private.integer_value(o->'rssiDbm',-127,-1) or not presence_private.integer_value(o->'elapsedMs',0,9007199254740991) then perform presence_private.fail('PT400','Invalid observation'); end if;
 end loop;
 end loop;
end $$;
create function presence_private.evidence(scan jsonb, b jsonb,c jsonb,beacons jsonb,inside integer,outside integer) returns jsonb language sql set search_path='' as $$
 with grouped as (
 select o->>'espId' id,array_agg((o->>'rssiDbm')::int order by (o->>'rssiDbm')::int) v
 from jsonb_array_elements(scan->'observations') o
 where exists(select 1 from jsonb_array_elements(beacons) x where x->>'espId'=o->>'espId')
 and (b->>'evaluatedElapsedMs')::bigint-(o->>'elapsedMs')::bigint between 0 and (c->>'maxAgeMs')::int group by o->>'espId'
 ), medians as (
 select id,case when cardinality(v)%2=1 then v[(cardinality(v)+1)/2] else trunc((v[cardinality(v)/2]+v[cardinality(v)/2+1])/2.0)::int end v from grouped
 ) select jsonb_build_object('detected',count(*),'strong',count(*) filter(where v>=inside),'weak',count(*) filter(where v<=outside),'spreadDb',max(v)-min(v),'medians',coalesce(jsonb_object_agg(id,v),'{}'::jsonb)) from medians
$$;
create function presence_private.evaluate(b jsonb,c jsonb,beacons jsonb) returns jsonb language plpgsql set search_path='' as $$
declare w jsonb; l jsonb; reasons jsonb:='[]'; status text; balanced boolean;
begin
 perform presence_private.validate_config(c); perform presence_private.validate_batch(b);
 w:=presence_private.evidence(b->'wifi',b,c,beacons,(c->>'wifiInsideDbm')::int,(c->>'wifiOutsideDbm')::int);
 l:=presence_private.evidence(b->'ble',b,c,beacons,(c->>'bleInsideDbm')::int,(c->>'bleOutsideDbm')::int);
 if b->'wifi'->>'outcome'<>'SUCCESS' then reasons:=reasons||jsonb_build_array('WIFI_'||(b->'wifi'->>'outcome')); end if;
 if b->'ble'->>'outcome'<>'SUCCESS' then reasons:=reasons||jsonb_build_array('BLE_'||(b->'ble'->>'outcome')); end if;
 if (w->>'detected')::int < (c->>'minDetected')::int then reasons:=reasons||'["WIFI_TOO_FEW_FRESH_BEACONS"]'; end if;
 if (l->>'detected')::int < (c->>'minDetected')::int then reasons:=reasons||'["BLE_TOO_FEW_FRESH_BEACONS"]'; end if;
 if jsonb_array_length(reasons)>0 then return jsonb_build_object('status','INSUFFICIENT_DATA','score',0,'wifi',w,'ble',l,'reasons',reasons); end if;
 balanced:=(w->>'spreadDb')::int <= (c->>'maxSpreadDb')::int and (l->>'spreadDb')::int <= (c->>'maxSpreadDb')::int;
 status:=case when (w->>'weak')::int >= (c->>'minWeak')::int and (l->>'weak')::int >= (c->>'minWeak')::int then 'OUTSIDE'
 when (w->>'strong')::int >= (c->>'minStrong')::int and (l->>'strong')::int >= (c->>'minStrong')::int and balanced then 'INSIDE' else 'UNCERTAIN' end;
 reasons:=jsonb_build_array('WIFI_STRONG_'||(w->>'strong')||'_WEAK_'||(w->>'weak'),'BLE_STRONG_'||(l->>'strong')||'_WEAK_'||(l->>'weak'));
 if not balanced then reasons:=reasons||'["SIGNAL_IMBALANCE"]'; end if;
 reasons:=reasons||jsonb_build_array(case status when 'INSIDE' then 'BOTH_TECHNOLOGIES_SUPPORT_INSIDE' when 'OUTSIDE' then 'BOTH_TECHNOLOGIES_SUPPORT_OUTSIDE' else 'BOUNDARY_OR_CONFLICTING_EVIDENCE' end);
 return jsonb_build_object('status',status,'score',(w->>'strong')::int+(l->>'strong')::int,'wifi',w,'ble',l,'reasons',reasons);
end $$;
create function presence_private.aggregate(points jsonb,c jsonb) returns jsonb language plpgsql set search_path='' as $$
declare i int; o int; u int; v int; outcome text;
begin
 select count(*) filter(where p->'result'->>'status'='INSIDE'),count(*) filter(where p->'result'->>'status'='OUTSIDE'),count(*) filter(where p->'result'->>'status'='UNCERTAIN') into i,o,u from jsonb_array_elements(points) p;
 v:=i+o+u; outcome:=case when v<(c->>'minValid')::int then 'INSUFFICIENT_COVERAGE' when i*100>=(c->>'insidePercent')::int*v then 'ELIGIBLE' else 'BELOW_THRESHOLD' end;
 return jsonb_build_object('outcome',outcome,'inside',i,'outside',o,'uncertain',u,'valid',v,'expected',(c->>'totalSlots')::int,'missing',(c->>'totalSlots')::int-v,'insidePercentage',case when v=0 then 0 else i*100/v end);
end $$;
create function presence_private.refresh_attendance(s jsonb,target uuid,is_final boolean) returns void language plpgsql set search_path='' as $$
declare points jsonb; previous jsonb;
begin
 select coalesce(jsonb_agg(point),'[]') into points from presence_private.checkpoints where session_id=s->>'id' and uid=target;
 select data into previous from presence_private.attendance where session_id=s->>'id' and uid=target;
 insert into presence_private.attendance values(s->>'id',target,jsonb_build_object('uid',target,'summary',presence_private.aggregate(points,s->'config'),'final',is_final or coalesce((previous->>'final')::boolean,false),'overrideOutcome',previous->'overrideOutcome','updatedAtMs',presence_private.now_ms()))
 on conflict(session_id,uid) do update set data=excluded.data;
end $$;
create function presence_private.finalize_sessions() returns void language plpgsql set search_path='' as $$
declare d record; s jsonb; target text; now_ms bigint:=presence_private.now_ms();
begin
 for d in select id,data from presence_private.documents where kind='session' and data->>'state' in ('ACTIVE','ENDED','FINALIZING') and (data->>'endMs')::bigint<=now_ms order by id for update skip locked limit 100 loop
 s:=d.data;
 if (s->>'finalAfterMs')::bigint<=now_ms then
  for target in select jsonb_array_elements_text(s->'participantUids') loop perform presence_private.refresh_attendance(s,target::uuid,true); end loop;
  s:=s||'{"state":"FINALIZED"}';
 else s:=s||'{"state":"ENDED"}'; end if;
 update presence_private.documents set data=s where kind='session' and id=d.id;
 end loop;
end $$;

create function public.presence_api(operation text,payload jsonb default '{}') returns jsonb language plpgsql security definer set search_path='' as $$
#variable_conflict use_column
<<presence_api>>
declare who uuid:=auth.uid(); profile presence_private.profiles; id text; target uuid; cl jsonb; room jsonb; c jsonb; s jsonb; previous jsonb; point jsonb; result jsonb; samples jsonb; sample jsonb; summaries jsonb:='[]'; output jsonb; now_ms bigint:=presence_private.now_ms(); end_ms bigint; slot int; captured bigint; item text;
begin
 if who is null then perform presence_private.fail('PT401','Sign in first'); end if;
 select * into profile from presence_private.profiles where uid=who and not disabled;
 if profile.uid is null or exists(select 1 from auth.users where auth.users.id=who and banned_until>now()) then perform presence_private.fail('PT403','Account role not provisioned or account disabled'); end if;
 if jsonb_typeof(payload) is distinct from 'object' or octet_length(payload::text)>600000 then perform presence_private.fail('PT400','Invalid payload'); end if;
 if operation='identity' then return jsonb_build_object('uid',who,'name',profile.name,'role',profile.role,'demo',false); end if;
 if operation in ('startSession','endSession','overrideAttendance','roomConfig','roomBeacons','publishConfig','listRoster','enrollStudent','removeEnrollment') and profile.role<>'TEACHER' then perform presence_private.fail('PT403','Teacher role required'); end if;
 case operation
 when 'listClasses' then
 select coalesce(jsonb_agg(jsonb_build_object('id',id,'title',data->'title','roomId',data->'roomId','teacherUids',data->'teacherUids')),'[]') into output from (select id,data from presence_private.documents where kind='class' and (case profile.role when 'TEACHER' then data->'teacherUids' else data->'studentUids' end) ? who::text order by id limit 100) d; return output;
 when 'listSessions' then
 perform presence_private.finalize_sessions();
 select coalesce(jsonb_agg(data order by (data->>'startMs')::bigint desc),'[]') into output from (select data from presence_private.documents where kind='session' and (case profile.role when 'TEACHER' then data->'teacherUids' else data->'participantUids' end) ? who::text order by (data->>'startMs')::bigint desc limit 100) d; return output;
 when 'startSession' then
 id:=payload->>'classId'; select data into cl from presence_private.documents where kind='class' and documents.id=presence_api.id for update;
 if cl is null or not (cl->'teacherUids' ? who::text) then perform presence_private.fail('PT403','Assigned class required'); end if;
 select data into previous from presence_private.documents where kind='session' and documents.id=cl->>'activeSessionId';
 if previous->>'state'='ACTIVE' and (previous->>'endMs')::bigint>now_ms then perform presence_private.fail('PT400','This class already has an active session'); end if;
 select data into room from presence_private.documents where kind='room' and documents.id=cl->>'roomId' for update;
 if room is null or not (room->'teacherUids' ? who::text) then perform presence_private.fail('PT403','Room assignment required'); end if;
 select data->'config' into c from presence_private.documents where kind='config' and documents.id=(cl->>'roomId')||':'||(room->>'activeConfigVersion');
 perform presence_private.validate_config(c);
 if not (c->>'calibrated')::boolean then perform presence_private.fail('PT400','Calibrate the room before starting a production session'); end if;
 if jsonb_array_length(cl->'studentUids')>200 then perform presence_private.fail('PT400','Roster limit reached'); end if;
 id:=gen_random_uuid()::text; end_ms:=now_ms+(c->>'intervalMs')::bigint*(c->>'totalSlots')::bigint;
 s:=jsonb_build_object('id',id,'classId',payload->>'classId','title',cl->'title','roomId',cl->'roomId','startMs',now_ms,'endMs',end_ms,'config',c,'beacons',room->'beacons','state','ACTIVE','teacherUids',cl->'teacherUids','participantUids',cl->'studentUids','finalAfterMs',end_ms+900000,'demo',false);
 insert into presence_private.documents values('session',id,s);
 update presence_private.documents set data=data||jsonb_build_object('activeSessionId',presence_api.id) where kind='class' and documents.id=payload->>'classId';
 for item in select jsonb_array_elements_text(cl->'studentUids') loop perform presence_private.refresh_attendance(s,item::uuid,false); end loop;
 return s;
 when 'roomConfig','roomBeacons','publishConfig' then
 id:=payload->>'roomId'; select data into room from presence_private.documents where kind='room' and documents.id=presence_api.id for update;
 if room is null or not (room->'teacherUids' ? who::text) then perform presence_private.fail('PT403','Room assignment required'); end if;
 if operation='roomBeacons' then return room->'beacons'; end if;
 if operation='roomConfig' then select data->'config' into c from presence_private.documents where kind='config' and documents.id=presence_api.id||':'||(room->>'activeConfigVersion'); return c; end if;
 c:=(payload->>'configJson')::jsonb; perform presence_private.validate_config(c);
 if not (c->>'calibrated')::boolean or (c->>'intervalMs')::int<>300000 or (c->>'totalSlots')::int<>12 or (c->>'minValid')::int<>8 or (c->>'insidePercent')::int<>60 then perform presence_private.fail('PT400','Production sessions require the approved 60-minute policy'); end if;
 if (c->>'version')::int<>(room->>'activeConfigVersion')::int+1 then perform presence_private.fail('PT409','Configuration changed. Refresh and retry'); end if;
 samples:=(payload->>'samplesJson')::jsonb;
 if jsonb_typeof(samples) is distinct from 'array' then perform presence_private.fail('PT400','Invalid calibration samples'); end if;
 if jsonb_array_length(samples) not between 6 and 60 or (select count(*) from jsonb_array_elements(samples) p where p->>'inside'='true')<3 or (select count(*) from jsonb_array_elements(samples) p where p->>'inside'='false')<3 then perform presence_private.fail('PT400','Collect at least three inside and three outside recordings'); end if;
 for sample in select value from jsonb_array_elements(samples) loop
 if sample-array['label','inside','batch']<>'{}'::jsonb or jsonb_typeof(sample->'label') is distinct from 'string' or length(sample->>'label') not between 1 and 100 or coalesce(sample->>'inside','') not in ('true','false') or jsonb_typeof(sample->'batch') is distinct from 'string' then perform presence_private.fail('PT400','Invalid calibration sample'); end if;
 result:=presence_private.evaluate((sample->>'batch')::jsonb,c,room->'beacons');
 if result->>'status'='INSUFFICIENT_DATA' then perform presence_private.fail('PT400','Calibration needs both radios and fresh evidence'); end if;
 summaries:=summaries||jsonb_build_array(jsonb_build_object('label',sample->'label','inside',(sample->>'inside')::boolean,'result',result));
 end loop;
 insert into presence_private.documents values('config',id||':'||(c->>'version'),jsonb_build_object('config',c,'createdBy',who,'createdAtMs',now_ms,'calibration',summaries));
 update presence_private.documents set data=data||jsonb_build_object('activeConfigVersion',(c->>'version')::int) where kind='room' and documents.id=presence_api.id;
 when 'listRoster','enrollStudent','removeEnrollment' then
 id:=payload->>'classId'; select data into cl from presence_private.documents where kind='class' and documents.id=presence_api.id for update;
 if cl is null or not(cl->'teacherUids' ? who::text) then perform presence_private.fail('PT403','Assigned class required'); end if;
 if operation='listRoster' then select coalesce(jsonb_agg(jsonb_build_object('uid',uid,'name',name,'email',email)),'[]') into output from presence_private.profiles where cl->'studentUids' ? uid::text; return output; end if;
 if operation='enrollStudent' then
 select uid into target from presence_private.profiles where lower(email)=lower(payload->>'email') and role='STUDENT' and not disabled;
 if target is null then perform presence_private.fail('PT400','An enabled provisioned student account is required'); end if;
 if cl->'studentUids' ? target::text then return '{"ok":true}'; end if;
 if jsonb_array_length(cl->'studentUids')>=200 then perform presence_private.fail('PT400','Roster limit reached'); end if;
 cl:=jsonb_set(cl,'{studentUids}',(cl->'studentUids')||jsonb_build_array(target));
 else target:=(payload->>'uid')::uuid; select coalesce(jsonb_agg(v),'[]') into output from jsonb_array_elements(cl->'studentUids') v where v#>>'{}'<>target::text; cl:=jsonb_set(cl,'{studentUids}',output); end if;
 update presence_private.documents set data=cl where kind='class' and documents.id=presence_api.id;
 when 'endSession','submitCheckpoint','listAttendance','listCheckpoints','overrideAttendance' then
 if operation='submitCheckpoint' then
 if profile.role<>'STUDENT' then perform presence_private.fail('PT403','Student required'); end if;
 point:=(payload->>'checkpointJson')::jsonb;
 if jsonb_typeof(point) is distinct from 'object' or point-array['sessionId','uid','slot','capturedAtMs','configVersion','batch','result','algorithmVersion','demo']<>'{}'::jsonb
 or point->'demo' is distinct from 'false'::jsonb or point->'algorithmVersion' is distinct from '1'::jsonb or not presence_private.integer_value(point->'slot',0,23)
 or not presence_private.integer_value(point->'capturedAtMs',1,9007199254740991) or not presence_private.integer_value(point->'configVersion',1,2147483647) then perform presence_private.fail('PT400','Invalid checkpoint'); end if;
 if point->>'uid' is distinct from who::text then perform presence_private.fail('PT403','Own checkpoint only'); end if;
 id:=point->>'sessionId';
 else id:=payload->>'sessionId'; end if;
 select data into s from presence_private.documents where kind='session' and documents.id=presence_api.id for update;
 if s is null then perform presence_private.fail('PT404','Session missing'); end if;
 if (profile.role='TEACHER' and not(s->'teacherUids' ? who::text)) or (profile.role='STUDENT' and not(s->'participantUids' ? who::text)) then perform presence_private.fail('PT403','Session membership required'); end if;
 if operation='endSession' then
 if s->>'state'='ACTIVE' then end_ms:=least((s->>'endMs')::bigint,now_ms); update presence_private.documents set data=data||jsonb_build_object('state','ENDED','endMs',end_ms,'finalAfterMs',end_ms+900000) where kind='session' and documents.id=presence_api.id; end if;
 elsif operation='submitCheckpoint' then
 slot:=(point->>'slot')::int; captured:=(point->>'capturedAtMs')::bigint;
 if point->'configVersion' is distinct from s->'config'->'version' or slot>=(s->'config'->>'totalSlots')::int then perform presence_private.fail('PT400','Configuration or slot mismatch'); end if;
 result:=presence_private.evaluate(point->'batch',s->'config',s->'beacons');
 if exists(select 1 from (select value from jsonb_array_elements(point->'batch'->'wifi'->'observations') union all select value from jsonb_array_elements(point->'batch'->'ble'->'observations')) o where not exists(select 1 from jsonb_array_elements(s->'beacons') x where x->>'espId'=o.value->>'espId')) then perform presence_private.fail('PT400','Unknown beacon'); end if;
 point:=jsonb_set(point,'{result}',result);
 select checkpoints.point into previous from presence_private.checkpoints where session_id=id and uid=who and checkpoints.slot=presence_api.slot;
 if previous is not null then if previous<>point then perform presence_private.fail('PT409','Conflicting checkpoint retry'); end if; return jsonb_build_object('ok',true,'status',result->'status'); end if;
 if captured<(s->>'startMs')::bigint+slot*(s->'config'->>'intervalMs')::bigint or captured>(s->>'startMs')::bigint+slot*(s->'config'->>'intervalMs')::bigint+60000 or captured>=(s->>'endMs')::bigint or captured>now_ms+5000 or now_ms>(s->>'finalAfterMs')::bigint or s->>'state' not in ('ACTIVE','ENDED') then perform presence_private.fail('PT400','Checkpoint outside collection/upload window. Teacher review required.'); end if;
 insert into presence_private.checkpoints values(id,who,slot,point);
 perform presence_private.refresh_attendance(s,who,false); return jsonb_build_object('ok',true,'status',result->'status');
 elsif operation='listAttendance' then
 select coalesce(jsonb_agg(data order by uid),'[]') into output from presence_private.attendance where session_id=id and (profile.role='TEACHER' or uid=who); return output;
 elsif operation='listCheckpoints' then
 target:=(payload->>'uid')::uuid;
 if not(s->'participantUids' ? target::text) or (profile.role<>'TEACHER' and target<>who) then perform presence_private.fail('PT403','Participant access denied'); end if;
 select coalesce(jsonb_agg(checkpoints.point order by checkpoints.slot),'[]') into output from presence_private.checkpoints where session_id=id and uid=target; return output;
 else
 target:=(payload->>'uid')::uuid;
 if not(s->'participantUids' ? target::text) then perform presence_private.fail('PT403','Participant missing'); end if;
 if coalesce(payload->>'outcome','') not in ('ELIGIBLE','BELOW_THRESHOLD','INSUFFICIENT_COVERAGE') or length(trim(coalesce(payload->>'reason',''))) not between 10 and 1000 then perform presence_private.fail('PT400','A valid outcome and audit reason are required'); end if;
 if now_ms<(s->>'endMs')::bigint then perform presence_private.fail('PT400','End the session before making a final correction'); end if;
 select data into previous from presence_private.attendance where session_id=id and uid=target;
 if previous is null then perform presence_private.fail('PT404','Attendance missing'); end if;
 insert into presence_private.audit(session_id,data) values(id,jsonb_build_object('actorUid',who,'targetUid',target,'previous',previous,'newOutcome',payload->'outcome','reason',trim(payload->>'reason'),'atMs',now_ms));
 update presence_private.attendance set data=data||jsonb_build_object('overrideOutcome',payload->'outcome','updatedAtMs',now_ms) where session_id=id and uid=target;
 end if;
 else perform presence_private.fail('PT400','Unknown operation');
 end case;
 return '{"ok":true}';
exception when invalid_text_representation or numeric_value_out_of_range or null_value_not_allowed then perform presence_private.fail('PT400','Payload failed validation');
end $$;

-- Every private function is administrator-only. Only this API is exposed to signed-in users.
revoke all on all tables in schema presence_private from public, anon, authenticated;
revoke all on all functions in schema presence_private from public, anon, authenticated;
revoke all on function public.presence_api(text,jsonb) from public, anon;
grant execute on function public.presence_api(text,jsonb) to authenticated;

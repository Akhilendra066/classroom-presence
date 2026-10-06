-- Future classes scale to roughly one observation window per three minutes.
-- Preserve existing room calibration and immutable session timing snapshots.
alter table presence_private.checkpoints drop constraint if exists checkpoints_slot_check;
alter table presence_private.checkpoints add constraint checkpoints_slot_check check (slot between 0 and 39);
create or replace function presence_private.validate_config(c jsonb) returns void language plpgsql set search_path='' as $$
declare k text; lo integer; hi integer;
begin
 if jsonb_typeof(c) is distinct from 'object' or (select count(*) from jsonb_object_keys(c)) not in (16,17) or jsonb_typeof(c->'calibrated') is distinct from 'boolean' then perform presence_private.fail('PT400','Invalid configuration'); end if;
 for k,lo,hi in select * from (values
 ('version',1,2147483647),('wifiInsideDbm',-127,-1),('bleInsideDbm',-127,-1),('wifiOutsideDbm',-127,-1),('bleOutsideDbm',-127,-1),
 ('minDetected',1,4),('minStrong',1,4),('minWeak',1,4),('maxSpreadDb',1,100),('maxAgeMs',1000,60000),('bleDurationMs',1000,30000),
 ('intervalMs',30001,600000),('totalSlots',1,40),('minValid',1,40),('insidePercent',1,100)) t(k,lo,hi)
 loop if not presence_private.integer_value(c->k,lo,hi) then perform presence_private.fail('PT400','Invalid configuration field: '||k); end if; end loop;
 if c ? 'minInsideSlots' and (not presence_private.integer_value(c->'minInsideSlots',0,40) or (c->>'minInsideSlots')::int > (c->>'totalSlots')::int) then perform presence_private.fail('PT400','Invalid minimum inside checkpoints'); end if;
 if c - array['version','calibrated','wifiInsideDbm','bleInsideDbm','wifiOutsideDbm','bleOutsideDbm','minDetected','minStrong','minWeak','maxSpreadDb','maxAgeMs','bleDurationMs','intervalMs','totalSlots','minValid','insidePercent','minInsideSlots'] <> '{}'::jsonb
 or (c->>'wifiOutsideDbm')::int >= (c->>'wifiInsideDbm')::int or (c->>'bleOutsideDbm')::int >= (c->>'bleInsideDbm')::int
 or (c->>'minStrong')::int < (c->>'minDetected')::int or (c->>'minWeak')::int < (c->>'minDetected')::int
 or (c->>'minValid')::int > (c->>'totalSlots')::int or (c->>'intervalMs')::int <= (c->>'bleDurationMs')::int then perform presence_private.fail('PT400','Invalid threshold or policy'); end if;
end $$;
create or replace function presence_private.presence_api_base(operation text,payload jsonb default '{}') returns jsonb language plpgsql security definer set search_path='' as $$
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
 or point->'demo' is distinct from 'false'::jsonb or point->'algorithmVersion' is distinct from '1'::jsonb or not presence_private.integer_value(point->'slot',0,39)
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

revoke all on function presence_private.presence_api_base(text,jsonb) from public,anon,authenticated;
create or replace function presence_private.start_timed_session(payload jsonb) returns jsonb
language plpgsql set search_path='' as $$
declare identity jsonb; s jsonb; c jsonb; duration int; minimum int; required_slots int; checkpoint_count int; interval_ms bigint; ends bigint; participant text;
begin
 identity:=presence_private.presence_api_base('identity',payload);
 if identity->>'role'<>'TEACHER' then perform presence_private.fail('PT403','Teacher role required'); end if;
 if payload - array['classId','durationMinutes','minimumPresenceMinutes'] <> '{}'::jsonb
 or not presence_private.integer_value(payload->'durationMinutes',15,120)
 or not presence_private.integer_value(payload->'minimumPresenceMinutes',1,120) then perform presence_private.fail('PT400','Enter a class duration and minimum presence in minutes'); end if;
 duration:=(payload->>'durationMinutes')::int; minimum:=(payload->>'minimumPresenceMinutes')::int;
 if duration % 5 <> 0 or minimum > duration then perform presence_private.fail('PT400','Use a 15–120 minute class in steps of 5, with minimum presence no longer than the class'); end if;
 checkpoint_count:=greatest(5,(duration+2)/3);
 interval_ms:=duration::bigint*60000/checkpoint_count;
 required_slots:=(minimum*checkpoint_count+duration-1)/duration;
 -- Reuse the existing assigned-class, room, calibrated-config and enrollment checks.
 -- Its row locks serialize competing starts. All snapshot changes are atomic.
 s:=presence_private.presence_api_base('startSession',payload);
 c:=s->'config'||jsonb_build_object('intervalMs',interval_ms,'totalSlots',checkpoint_count,'minValid',required_slots,'insidePercent',1,'minInsideSlots',required_slots);
 perform presence_private.validate_config(c);
 ends:=(s->>'startMs')::bigint+duration::bigint*60000;
 s:=s||jsonb_build_object('config',c,'endMs',ends,'finalAfterMs',ends+900000);
 update presence_private.documents set data=s where kind='session' and id=s->>'id';
 for participant in select jsonb_array_elements_text(s->'participantUids') loop
  perform presence_private.refresh_attendance(s,participant::uuid,false);
 end loop;
 return s;
end $$;
revoke all on function presence_private.start_timed_session(jsonb) from public,anon,authenticated;


notify pgrst,'reload schema';

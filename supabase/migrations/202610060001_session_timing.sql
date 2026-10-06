-- Teacher-controlled class duration and absolute sampled-presence minimum.
-- Existing sessions without minInsideSlots retain their original percentage policy.
create or replace function presence_private.validate_config(c jsonb) returns void language plpgsql set search_path='' as $$
declare k text; lo integer; hi integer;
begin
 if jsonb_typeof(c) is distinct from 'object' or (select count(*) from jsonb_object_keys(c)) not in (16,17) or jsonb_typeof(c->'calibrated') is distinct from 'boolean' then perform presence_private.fail('PT400','Invalid configuration'); end if;
 for k,lo,hi in select * from (values
 ('version',1,2147483647),('wifiInsideDbm',-127,-1),('bleInsideDbm',-127,-1),('wifiOutsideDbm',-127,-1),('bleOutsideDbm',-127,-1),
 ('minDetected',1,4),('minStrong',1,4),('minWeak',1,4),('maxSpreadDb',1,100),('maxAgeMs',1000,60000),('bleDurationMs',1000,30000),
 ('intervalMs',30001,600000),('totalSlots',1,24),('minValid',1,24),('insidePercent',1,100)) t(k,lo,hi)
 loop if not presence_private.integer_value(c->k,lo,hi) then perform presence_private.fail('PT400','Invalid configuration field: '||k); end if; end loop;
 if c ? 'minInsideSlots' and (not presence_private.integer_value(c->'minInsideSlots',0,24) or (c->>'minInsideSlots')::int > (c->>'totalSlots')::int) then perform presence_private.fail('PT400','Invalid minimum inside checkpoints'); end if;
 if c - array['version','calibrated','wifiInsideDbm','bleInsideDbm','wifiOutsideDbm','bleOutsideDbm','minDetected','minStrong','minWeak','maxSpreadDb','maxAgeMs','bleDurationMs','intervalMs','totalSlots','minValid','insidePercent','minInsideSlots'] <> '{}'::jsonb
 or (c->>'wifiOutsideDbm')::int >= (c->>'wifiInsideDbm')::int or (c->>'bleOutsideDbm')::int >= (c->>'bleInsideDbm')::int
 or (c->>'minStrong')::int < (c->>'minDetected')::int or (c->>'minWeak')::int < (c->>'minDetected')::int
 or (c->>'minValid')::int > (c->>'totalSlots')::int or (c->>'intervalMs')::int <= (c->>'bleDurationMs')::int then perform presence_private.fail('PT400','Invalid threshold or policy'); end if;
end $$;
create or replace function presence_private.aggregate(points jsonb,c jsonb) returns jsonb language plpgsql set search_path='' as $$
declare i int; o int; u int; v int; outcome text;
begin
 select count(*) filter(where p->'result'->>'status'='INSIDE'),count(*) filter(where p->'result'->>'status'='OUTSIDE'),count(*) filter(where p->'result'->>'status'='UNCERTAIN') into i,o,u from jsonb_array_elements(points) p;
 v:=i+o+u; outcome:=case when v<(c->>'minValid')::int then 'INSUFFICIENT_COVERAGE' when coalesce((c->>'minInsideSlots')::int,0)>0 and i>=(c->>'minInsideSlots')::int then 'ELIGIBLE' when coalesce((c->>'minInsideSlots')::int,0)=0 and i*100>=(c->>'insidePercent')::int*v then 'ELIGIBLE' else 'BELOW_THRESHOLD' end;
 return jsonb_build_object('outcome',outcome,'inside',i,'outside',o,'uncertain',u,'valid',v,'expected',(c->>'totalSlots')::int,'missing',(c->>'totalSlots')::int-v,'insidePercentage',case when v=0 then 0 else i*100/v end);
end $$;
-- Room radio thresholds stay separate from immutable per-session timing.
create or replace function presence_private.start_timed_session(payload jsonb) returns jsonb
language plpgsql set search_path='' as $$
declare identity jsonb; s jsonb; c jsonb; duration int; minimum int; required_slots int; ends bigint; participant text;
begin
 identity:=presence_private.presence_api_base('identity',payload);
 if identity->>'role'<>'TEACHER' then perform presence_private.fail('PT403','Teacher role required'); end if;
 if payload - array['classId','durationMinutes','minimumPresenceMinutes'] <> '{}'::jsonb
 or not presence_private.integer_value(payload->'durationMinutes',15,120)
 or not presence_private.integer_value(payload->'minimumPresenceMinutes',1,120) then perform presence_private.fail('PT400','Enter a class duration and minimum presence in minutes'); end if;
 duration:=(payload->>'durationMinutes')::int; minimum:=(payload->>'minimumPresenceMinutes')::int;
 if duration % 5 <> 0 or minimum > duration then perform presence_private.fail('PT400','Use a 15–120 minute class in steps of 5, with minimum presence no longer than the class'); end if;
 required_slots:=(minimum+4)/5;
 -- Reuse the existing assigned-class, room, calibrated-config and enrollment checks.
 -- Its row locks serialize competing starts. All snapshot changes are atomic.
 s:=presence_private.presence_api_base('startSession',payload);
 c:=s->'config'||jsonb_build_object('intervalMs',300000,'totalSlots',duration/5,'minValid',required_slots,'insidePercent',1,'minInsideSlots',required_slots);
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

create or replace function public.presence_api(operation text,payload jsonb default '{}') returns jsonb
language plpgsql security definer set search_path='' as $$
declare identity jsonb; output jsonb;
begin
 if operation='startSession' and (payload ? 'durationMinutes' or payload ? 'minimumPresenceMinutes') then return presence_private.start_timed_session(payload); end if;
 if operation='publishConfig' then
  identity:=presence_private.presence_api_base('identity',payload);
  if identity->>'role'<>'TEACHER' then perform presence_private.fail('PT403','Teacher role required'); end if;
  if coalesce(((payload->>'configJson')::jsonb->>'minInsideSlots')::int,0)<>0 then perform presence_private.fail('PT400','Save attendance timing on the class session, not the room calibration'); end if;
 end if;
 if operation is distinct from 'listSessionStates' then return presence_private.presence_api_base(operation,payload); end if;
 identity:=presence_private.presence_api_base('identity',payload);
 perform presence_private.finalize_sessions();
 select coalesce(jsonb_agg(jsonb_build_object('id',data->'id','state',data->'state','endMs',data->'endMs')),'[]') into output
 from (select data from presence_private.documents where kind='session'
 and (case identity->>'role' when 'TEACHER' then data->'teacherUids' else data->'participantUids' end) ? (identity->>'uid')
 order by (data->>'startMs')::bigint desc limit 100) d;
 return output;
end $$;
revoke all on function public.presence_api(text,jsonb) from public,anon;
grant execute on function public.presence_api(text,jsonb) to authenticated;
notify pgrst,'reload schema';

-- Keep periodic dashboard refreshes small enough for the Free plan's egress quota.
-- Immutable full sessions are downloaded on login/manual refresh/new-session discovery.
alter function public.presence_api(text,jsonb) set schema presence_private;
alter function presence_private.presence_api(text,jsonb) rename to presence_api_base;
revoke all on function presence_private.presence_api_base(text,jsonb) from public,anon,authenticated;
create function public.presence_api(operation text,payload jsonb default '{}') returns jsonb
language plpgsql security definer set search_path='' as $$
declare identity jsonb; output jsonb;
begin
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

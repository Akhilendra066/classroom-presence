-- Relational student directory and class enrollment records.
-- Trusted roles remain in profiles; the students view cannot grant a role.
create unique index if not exists profiles_email_lower_unique
on presence_private.profiles (lower(email));

create table if not exists presence_private.class_enrollments (
 class_id text not null,
 student_uid uuid not null references presence_private.profiles(uid) on delete cascade,
 enrolled_at timestamptz not null default now(),
 primary key (class_id, student_uid)
);
alter table presence_private.class_enrollments enable row level security;
revoke all on presence_private.class_enrollments from public, anon, authenticated;

create or replace function presence_private.sync_class_enrollments_from_document()
returns trigger language plpgsql set search_path='' as $$
begin
 if tg_op='DELETE' then
  if old.kind='class' then
   delete from presence_private.class_enrollments where class_id=old.id;
  end if;
  return old;
 end if;
 if new.kind<>'class' then return new; end if;

 delete from presence_private.class_enrollments enrollment
 where enrollment.class_id=new.id
 and not coalesce(new.data->'studentUids','[]'::jsonb) ? enrollment.student_uid::text;

 insert into presence_private.class_enrollments(class_id,student_uid)
 select new.id,profile.uid
 from presence_private.profiles profile
 where profile.role='STUDENT'
 and coalesce(new.data->'studentUids','[]'::jsonb) ? profile.uid::text
 on conflict(class_id,student_uid) do nothing;
 return new;
end $$;
revoke all on function presence_private.sync_class_enrollments_from_document() from public,anon,authenticated;

drop trigger if exists sync_class_enrollments on presence_private.documents;
create trigger sync_class_enrollments
after insert or update or delete on presence_private.documents
for each row execute function presence_private.sync_class_enrollments_from_document();

-- Backfill classes that existed before this migration.
insert into presence_private.class_enrollments(class_id,student_uid)
select document.id,profile.uid
from presence_private.documents document
join presence_private.profiles profile
 on profile.role='STUDENT'
 and coalesce(document.data->'studentUids','[]'::jsonb) ? profile.uid::text
where document.kind='class'
on conflict(class_id,student_uid) do nothing;

create or replace view presence_private.students as
select profile.uid,profile.name,profile.email,profile.disabled,
 count(enrollment.class_id)::integer as enrolled_class_count
from presence_private.profiles profile
left join presence_private.class_enrollments enrollment on enrollment.student_uid=profile.uid
where profile.role='STUDENT'
group by profile.uid,profile.name,profile.email,profile.disabled;

create or replace view presence_private.student_enrollments as
select enrollment.class_id,document.data->>'title' as class_title,
 document.data->>'roomId' as room_id,enrollment.student_uid,
 profile.name as student_name,profile.email as student_email,enrollment.enrolled_at
from presence_private.class_enrollments enrollment
join presence_private.profiles profile on profile.uid=enrollment.student_uid
join presence_private.documents document on document.kind='class' and document.id=enrollment.class_id;

revoke all on presence_private.students from public,anon,authenticated;
revoke all on presence_private.student_enrollments from public,anon,authenticated;
notify pgrst,'reload schema';

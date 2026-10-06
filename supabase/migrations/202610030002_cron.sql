-- Hosted Supabase includes pg_cron. This job calls the database directly,
-- without Edge Function invocations or a service-role secret in cron.job.
create extension if not exists pg_cron;
select cron.schedule('presence-finalize','*/5 * * * *','select presence_private.finalize_sessions()');

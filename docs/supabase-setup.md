# Supabase Free deployment

The application now uses the real [Classroom Presence project](https://supabase.com/dashboard/project/wrupuujtrtiegqjbakfz), ref `wrupuujtrtiegqjbakfz`, in Mumbai (`ap-south-1`). It belongs to the newly created **Classroom Presence Free** organization, `spslyxvopkidccihcytf`. No billing, paid plan, size upgrade or paid add-on was activated.

## Deployed resources

- Supabase email/password Authentication, with public signup disabled.
- Teacher `hh5379259@gmail.com` and student `akhilendrasingh066@gmail.com`, both administratively created and confirmed.
- Trusted roles in `presence_private.profiles`; user metadata and the chosen login option never grant access.
- A live SQL student directory in `presence_private.students` and normalized class
  membership in `presence_private.class_enrollments` / `student_enrollments`.
  Existing classes are backfilled, and teacher enrollment changes update these rows
  automatically. These records remain private and are accessed by the app only
  through the trusted database API.
- Classroom A101 with the requested student enrolled, room `ROOM_A101` and all four current firmware beacon identities.
- Native PostgreSQL API `public.presence_api(operation,payload)`, accessible only to authenticated users. All underlying tables/functions remain private with RLS enabled and no mobile grants.
- Server classification, transactional checkpoints, duplicate/conflict handling, immutable session snapshots, teacher roster operations and audited attendance corrections.
- `presence-finalize` Cron job (`*/5 * * * *`), calling PostgreSQL directly after the 15-minute upload grace period. Dashboard session queries also catch up eligible finalization after inactivity.
- Public Android configuration in `mobile/supabase.properties`; administrator keys/passwords stay in ignored `.tools/supabase-private/` with private filesystem permissions.

The room starts **uncalibrated**. Production session creation is deliberately blocked until its authorized teacher collects real observations and publishes a reviewed configuration. No real calibration or physical attendance accuracy is claimed by deployment.

## Set the initial passwords

Open `.tools/supabase-private/account-password-links.md` locally. Each account has its own short-lived password setup link; do not open the link in a browser before using it in the app.

In the Android login screen, choose **Set password with administrator link**, paste your own complete link and choose a password with at least 12 characters. Then sign in using the appropriate role and email. Do not send passwords in chat. Keep each link private; it temporarily grants access to that account. Use only the student's link on the student's device and the teacher's link on the teacher's device.

If a link expires, regenerate it using the authorized local CLI account:

```sh
.tools/node-v22.15.0-darwin-arm64/bin/node supabase/scripts/admin.mjs password-links
```

This generates links without sending email or enabling an SMTP subscription. Supabase's default email service restricts recipients to project team members and currently permits only two messages per hour. The **Forgot password** email flow therefore needs an authorized recipient or a separately configured SMTP service. Do not add students to the Supabase administrator team just to allow emails. [Supabase email restrictions](https://supabase.com/docs/guides/auth/auth-smtp).

## Free plan suitability and limits

Supabase Free includes Authentication, unlimited API requests and PostgreSQL. Published limits include 50,000 monthly active users, a 500 MB database and 5 GB egress; projects may pause after one week of inactivity, with two active Free projects allowed. Automatic backups and an uptime SLA are not included. The app uses database functions and Cron, so it consumes no Edge Function invocation allowance. [Current pricing](https://supabase.com/pricing), [database functions](https://supabase.com/docs/guides/database/functions), [Cron](https://supabase.com/docs/guides/cron).

This is suitable for a final-year project and controlled classroom testing within those quotas. It does not guarantee indefinite free storage or uninterrupted institutional service. Check database/egress use in the dashboard, export records regularly and agree an evidence retention policy before data accumulates. Nothing automatically deletes attendance evidence.

Regular checkpoint batches are compacted to fresh per-beacon medians. Dashboard polling downloads small session states and runs only while the app is visible; immutable session configurations are downloaded on login, explicit refresh or discovery of a new session. Quota exhaustion or a paused project must be treated as an outage: downloaded student sessions can retain evidence locally, but new sessions/login require connectivity. Evidence arriving after the 15-minute server upload grace period requires teacher review.

## Rebuild and administer

```sh
./scripts/build-android.sh :presence-core:test :app:assembleDebug :app:lintDebug
.tools/node-v22.15.0-darwin-arm64/bin/node supabase/scripts/admin.mjs status
.tools/node-v22.15.0-darwin-arm64/bin/node supabase/scripts/admin.mjs verify
```

The trusted setup script uses the logged-in Supabase CLI; it never asks for the account password or prints administrator keys. Its `deploy` action installs migrations and configuration; `configure` saves the public Android key; `accounts` provisions the two initial accounts/class/room while preserving existing initial documents and roles. Do not repeatedly use `create` for an already existing project.

For a fresh development machine, run `sh scripts/setup-supabase-tools.sh` with Node.js 22 available to install the local CLI/PostgreSQL testing tools. Authorize `.tools/supabase-cli/node_modules/.bin/supabase login` and copy the public example properties file. Migrations can also be reviewed and applied in Supabase's SQL editor in filename order. Configure additional accounts through trusted administration, then enroll students through the teacher app; public signup and client role assignment are disabled.

Administrators can inspect the normalized records in the Supabase SQL editor:

```sql
select * from presence_private.students order by name;
select * from presence_private.student_enrollments order by class_title, student_name;
```

The Android app cannot query these private relations directly. Its authenticated
API checks teacher and class authorization before reading or changing enrollment.

Verification completed: 176 local PostgreSQL checks, hosted Auth/attendance operations, Android 16 cloud password/login/persistence checks and the full six-minute local workflow passed. The latest three hosted Cron runs succeeded. See `verification.md` for scope and limitations.

## Verify the first real class

1. Install `artifacts/classroom-presence-debug.apk` on teacher and student phones running Android 13+.
2. Set passwords and sign in. Teacher sees Classroom A101; student sees their enrollment.
3. Follow `calibration-guide.md`, using the four actual boards. Publish reviewed thresholds from the teacher app.
4. Choose Set up class, enter duration and minimum sampled presence, and start the session. The student opens it and starts real beacon monitoring with permissions/radios enabled.
5. Verify server-received checkpoints and inspect their explanations. Keep automatic device time enabled.
6. Test an offline/reconnect cycle and finalization after upload grace. The teacher can make a final correction only after session end and must provide an audit reason.

Field accuracy, Motorola battery behavior and multi-model reliability still require the recorded evaluations in `motorola-test-guide.md` and `calibration-guide.md`.

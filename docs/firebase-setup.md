# Firebase setup and trusted account provisioning

> Archived implementation. The user selected Supabase Free instead of Blaze. Android now uses Supabase; follow [supabase-setup.md](supabase-setup.md). The Firebase project below remains on Spark with billing disabled. No Firebase Functions or real Firebase users were deployed.

## Live project status — 3 October 2026

The separate [Classroom Presence project](https://console.firebase.google.com/project/classroom-presence-akhil-2026/overview) has been created in the authorized Google account. The existing DietApp project was not changed.

| Item | Actual status |
| --- | --- |
| Project ID | `classroom-presence-akhil-2026` |
| Project number | `966663073701` |
| Android package | `com.classroompresence.app` |
| Android app ID | `1:966663073701:android:22eb877b3f8ef2d236424c` |
| Android configuration | Downloaded to ignored `mobile/app/google-services.json` |
| Firestore | Native Standard `(default)` database in `asia-south1`, free tier |
| Security rules | Deployed; direct mobile access denied |
| Composite indexes | All four `classSessions` indexes verified READY |
| Administrator connection | Verified against real Firestore using CLI OAuth, without a service-account key |
| Authentication | API enabled; initialization still pending (`CONFIGURATION_NOT_FOUND`) |
| Teacher account | `hh5379259@gmail.com` — requested, not created yet |
| Student account | `akhilendrasingh066@gmail.com` — requested, not created yet |
| Roles, roster and room | Pending Authentication initialization and account creation |
| Billing | Disabled; project remains on Spark |
| Cloud Functions | Not deployed; requires Blaze billing |
| Android artifact | `artifacts/classroom-presence-firebase-debug.apk`; assembly, lint, signature and 16 KB ZIP alignment passed |

**Cloud login and attendance are not ready yet.** The Firebase-connected APK must not be treated as a completed cloud deployment.

Remaining console actions: open [Authentication](https://console.firebase.google.com/project/classroom-presence-akhil-2026/authentication/users) and click **Get started**; upgrade this project to Blaze and configure billing if proceeding with Cloud Functions. The public authentication initialization API requires billing, so the console is needed to initialize the free project. No payment details or passwords should be sent in chat.

After these actions, enable Email/Password, create both requested accounts, provision roles/class/room, deploy Functions and verify real authenticated teacher/student operations. Users choose their own passwords through Firebase's reset flow; administrator credentials stay outside the Android app. Room calibration and a field evaluation are still required before attendance accuracy can be claimed.

The setup instructions below also apply to a fresh checkout or another dedicated project.

## Project configuration

1. Create/select a Firebase project and register Android package `com.classroompresence.app`.
2. Download its `google-services.json` into `mobile/app/google-services.json` and rebuild the APK. The Google Services plugin is applied only when this file exists.
3. Enable Authentication → Email/Password and create your initial teacher and student accounts in the console. Set their display names if desired. Self-service role assignment is deliberately unavailable.
4. Create a Cloud Firestore database. Choose its region deliberately; callable functions in this implementation use `asia-south1` in both client and server.
5. Firebase deployment of Cloud Functions requires the Blaze plan. Review the project billing settings before deploying; the live project's Firestore resources are deployed, but billing and Functions remain pending. See [Firebase Functions setup](https://firebase.google.com/docs/functions/get-started).

## Install and deploy backend

Use Node.js 22 and JDK 17+ for the pinned local emulator tools. Functions use the Node 22 runtime.

```sh
cd firebase/functions
npm ci
npm run build
cd ..
./functions/node_modules/.bin/firebase login
./functions/node_modules/.bin/firebase deploy --project YOUR_PROJECT_ID --only firestore:rules,firestore:indexes,functions
```

Deployment creates callable functions plus a scheduled finalizer. Wait for composite indexes to finish building. All direct Firestore client reads/writes are denied: authenticated callable endpoints enforce the application's access policy. Never relax the rules to `allow read, write: if true`.

The initial account roster is administratively provisioned. Use your authorized Firebase CLI login with `--firebase-cli`; this keeps credentials in the CLI's local store without downloading a service-account key. Alternatively, omit that flag to use trusted Application Default Credentials. Never bundle administrator credentials in the Android app.

```sh
cd firebase/functions
GCLOUD_PROJECT=YOUR_PROJECT_ID npm run seed -- teacher@example.edu student@example.edu --firebase-cli
```

The seed script assigns trusted claims, creates a sample class and registers `ROOM_A101` with four beacons. Rerunning it preserves existing class members, teacher access and active calibration. It rejects using the same account for both roles or silently changing an existing role. Use teacher roster management to enroll additional provisioned students.

For additional accounts, assign the `role` custom claim (`STUDENT` or `TEACHER`) through trusted Firebase Admin administration. Teachers cannot promote accounts from the Android app. Sign out and sign in after changing claims. [Firebase custom-claim guidance](https://firebase.google.com/docs/auth/admin/custom-claims).

## First production session

1. Sign in with the teacher account through **Teacher login**.
2. Open **Calibrate room**, grant permissions and collect labelled readings from at least three inside and three outside positions. Prefer a much larger dataset across days and phones.
3. Export and evaluate recordings, choose thresholds and acknowledge the calibration review before publishing. Existing sessions keep their original snapshot.
4. Start a 60-minute session. The server snapshots the roster and configuration.
5. Sign in on a separate student phone with an enrolled account; open the active session and explicitly start real monitoring.
6. Verify teacher receives checkpoints. Disconnect internet while keeping scanning radios active, then reconnect and check queued uploads.

Cloud sessions need connectivity to create/download initially. An already downloaded student session can continue locally during an outage. Upload grace is 15 minutes after the actual session end; later evidence remains local as `REVIEW_REQUIRED`. Teachers can review/correct outcomes with an audit reason. A scheduled function closes ended sessions and publishes final summaries. It also retries interrupted finalization.

## Trusted API operations

`listClasses`, `listSessions`, `startSession`, `endSession`, `submitCheckpoint`, `listAttendance`, `listCheckpoints`, `overrideAttendance`, `roomConfig`, `roomBeacons`, `publishConfig`, `listRoster`, `enrollStudent`, `removeEnrollment`.

Students submit only their own registered-beacon evidence; server classification replaces client-provided results. Accepted duplicate uploads are idempotent, including retries after closure. Conflicting retries are rejected. Role, class/session membership and immutable configuration are checked server-side. Teacher enrollment changes affect future snapshots; historical sessions retain their original participants.

## Local emulator verification

```sh
./scripts/test-backend.sh
```

This uses a demo project and does not access live project data. It tests actual callable functions with Auth emulator tokens, denial of direct Firestore access, calibration/session gates, recomputation, enrollment, retries and audit records. All 16 backend tests passed after the administrative setup changes. The Firebase artifact connects to the live project, not the emulators; production login still requires completed account provisioning and deployed Functions.

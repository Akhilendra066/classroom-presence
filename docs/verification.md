# Verification recorded 3 October 2026

## Current Supabase deployment

- Real Free project `wrupuujtrtiegqjbakfz` in Mumbai; private tables, authenticated API, initial accounts/class/room and five-minute Cron deployed.
- 46 local PostgreSQL checks passed: shared fixtures, authentication/roles, private-table/function denial, calibration gates, ownership, timing, recomputation, identical/conflicting retries, history-preserving roster edits, audited corrections, finalization and disabled accounts.
- Disposable hosted integration passed: real Auth password/refresh sessions, metadata escalation denial, private-table denial, calibration publication, session creation/snapshots, server recomputation of a forged client result, retries/conflicts, teacher correction and finalization after grace.
- Android 16 live test passed against that hosted project: private recovery-link password setup, teacher/student login, wrong-role denial, encrypted session reuse after reopening the repository, roster access restrictions and uncalibrated-session rejection.
- Migrated Android assembly, lint and the 11 pure Kotlin tests passed. The app supports API 33+ and targets API 36.
- The complete migrated local demo workflow also passed on Android 16 in 6m 14s: all 12 simulated checkpoints, foreground scheduling, local storage, role switching and teacher review.
- Hosted Auth settings were verified: email login enabled and public signup disabled. The latest three Cron runs succeeded. Anonymous API execution and authenticated private-schema access are denied.
- The packaged 0.2.0 APK passed signature and 16 KB ZIP alignment checks. Its public Supabase configuration is present and its administrator key is absent; SHA-256 is recorded in `artifacts/README.md`.
- All disposable cloud test accounts, classes, rooms, calibration, sessions, checkpoints, attendance and audit data were removed. Production ROOM_A101 remains uncalibrated.

The live tests used synthetic readings in a disposable class. They verify backend behavior and do not measure physical radio accuracy. Teacher/student passwords are chosen by their owners via private setup links; no initial user password was requested in chat or tested on their behalf.

Reproduce local backend checks with `sh scripts/test-supabase.sh`; run `supabase/scripts/test-live.mjs` with the authorized CLI/local toolchain and a running Android emulator for disposable hosted checks. Private logs are in `.tools/supabase-private/live-android-test.log`.

## Earlier Firebase/local demo checks

| Check | Result |
| --- | --- |
| Clean Android build with AGP 8.11.1 / Gradle 8.13 / JDK 17 | Passed |
| APK assembly, minimum API 33 / target API 36 | Passed |
| Pure Kotlin presence engine tests | 11 tests, zero failures/errors |
| Backend TypeScript compilation | Passed |
| Auth / Firestore / Functions emulator tests | 15 tests passed, none skipped |
| Android 16 emulator workflow | One complete six-minute class; all 12 checkpoints saved and teacher review verified |
| Android lint | No errors; remaining warnings are dependency-update suggestions and a KTX style suggestion |
| Visual inspection | Monitoring screen checked on a 320 × 640 emulator display |

The Android workflow signs in as local teacher, creates an accelerated session, switches to local student, runs foreground monitoring through all 12 scheduled simulated checkpoints, checks local persistence/sync isolation, then switches back to teacher and inspects evidence. Its timed session uses real scheduling and database writes, with simulated radio measurements.

Backend integration exercises actual callable endpoints with Auth emulator tokens: student role restrictions, outsider access denial, uncalibrated-room rejection, teacher config publication, session creation, checkpoint ownership, server recomputation overriding a forged client result, identical/conflicting retries, simulation/config mismatch rejection, cross-student isolation, early end, audited overrides, enrollment operations, historical roster preservation, scheduled finalization and retries after closure. Direct Firestore access is denied to both student and teacher clients.

Core tests include the shared strong/weak/missing/conflict/imbalance/boundary fixtures, current firmware payload parsing, stale/future readings, radio failures, median spike suppression, duplicate/mixed participant rejection, attendance boundaries, hysteresis reset and compact-evidence parity.

## Reproduce

```sh
./scripts/build-android.sh :presence-core:test :app:assembleDebug :app:lintDebug
./scripts/test-backend.sh
./scripts/test-android.sh
```

For a clean combined Android run use `scripts/verify-android.sh`. Start the `classroom-api36` emulator first; the test scripts use `emulator-5554` and clear only its app data. Runtime duration is about seven minutes including the six-minute session. Dependencies are locked through the Gradle wrapper and `firebase/functions/package-lock.json`.

## Not established by these tests

- Firebase is archived and unused by the Android app. Its former project remains on Spark with billing disabled; the current cloud implementation and live checks use Supabase Free.
- No physical Motorola Edge 60 Fusion / ESP acquisition has been run in this workspace.
- Android 13–15 and other phone models are declared supported by minimum SDK and API usage, but not yet exercised on devices.
- No measured RSSI accuracy, 60-minute physical acquisition reliability, battery consumption or manufacturer background behavior is claimed.
- Thresholds require classroom calibration and held-out field evaluation.
- The debug APK is a prototype artifact; public distribution requires release signing and the appropriate deployment review.

Use `motorola-test-guide.md`, `calibration-guide.md` and `field-test-report-template.md` for the remaining device gates.

## Packaged artifact

The final packaged APK was rebuilt after adding retry scheduling on cloud sign-in/app reopen. APK Signature Scheme v2 verification and 16 KB ZIP alignment checks passed. The exact file `artifacts/classroom-presence-debug.apk` was installed and launched on the Android 16 emulator. Its SHA-256 is recorded in `artifacts/README.md`.

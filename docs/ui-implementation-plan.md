# Android UI implementation plan

> Current app update: institution-only access. Demo login, local synthetic sessions,
> simulated monitoring and the simulator have been removed. Earlier sections below
> record the original UI design and its verification before that follow-up change.

## Goal and boundaries

Make everyday attendance tasks easy for teachers and students using the existing
Jetpack Compose app. Preserve the repository, backend, monitoring service, pure
Kotlin classifier, beacon contract and firmware. Roles and final attendance remain
backend-owned. Do not invent attendance totals or treat missing data as absence.

## Current experience

The app has working sign-in, class creation, roster management, room calibration,
student monitoring, attendance review and CSV exports. The dashboard gives both
roles similar stacked controls; diagnostics and configuration details compete
with everyday tasks. Correction forms are always visible for every student.

## Implementation sequence

1. Establish a consistent Material 3 theme: indigo primary, light neutral canvas,
   rounded white cards, readable typography and labelled status badges. Use full
   width primary actions, wrapping layouts and scrolling for small screens and
   larger fonts. Retain clear loading, offline, empty and local-demo states.
2. Add role-aware bottom navigation: Home, Classes, History and More. Keep session,
   roster and calibration screens reachable with a dashboard return action and
   support Android Back. Keep simulator and setup tools in More.
3. Teacher home: assigned-class and active-session counts, active sessions first,
   class cards with a prominent start action, and secondary roster/calibration
   actions. Separate ended sessions into History. Confirm ending a class and
   removing an enrollment before executing those actions.
4. Student home: active sessions first, a clear Open monitoring action, enrolled
   classes and access to previous session results. Do not imply overall attendance
   percentages when the backend does not provide an aggregate.
5. Simplify session screens: monitoring readiness and start/stop actions first for
   students; received attendance and export first for teachers. Keep attendance
   policy, corrections, saved checkpoint details and diagnostics expandable.
   Explain insufficient coverage, provisional results and final backend records.
6. Refresh login and roster screens with the same components. Keep existing
   authentication and demo actions. Preserve all calibration and export behavior.

## Verification

- Run Android assembleDebug, lintDebug and compileDebugAndroidTest.
- Run presence-core tests as a regression check, although core is unchanged.
- Run Compose UI navigation tests on an emulator if available; retain the existing
  six-minute teacher/student demonstration test.
- Inspect emulator screenshots when a device is available, including teacher and
  student dashboards. Report any unavailable runtime checks explicitly.
- Backend tests are required if backend changes are made; none are planned.
- UI checks do not establish real-device radio accuracy.

## Implemented

- Added the shared theme and labelled navigation glyphs in `AppDesign.kt`.
- Added Home, Classes, History and More navigation, with Android Back returning
  to the dashboard and fresh scroll positions when changing screens.
- Separated teacher and student dashboard guidance and prioritized active classes.
  Teachers with no active session see the start-class section immediately after
  the overview. Completed sessions are reviewed through History.
- Simplified student monitoring and teacher review; attendance requirements,
  diagnostics, checkpoint detail and correction forms are expandable.
- Added confirmations for ending a session and removing an enrollment, readable
  outcome labels, missing-checkpoint counts and an empty percentage when no valid
  observations exist.
- Preserved authentication, CSV export, room calibration and local-demo warnings.
  Backend, firmware, radio scanning, monitoring and presence-core are unchanged.

## Recorded verification — 6 October 2026

- All 11 presence-core tests passed in a fresh test execution.
- Android debug APK assembled and instrumentation test sources compiled.
- Android lint passed with no errors. The report contains dependency-update
  notices and the existing URI/KTX suggestion; dependencies were not changed.
- Both Android 16 emulator tests passed: role-specific navigation/confirmation,
  and the six-minute teacher/student workflow with all 12 demo checkpoints and
  teacher evidence review. The existing workflow test now opens History to find
  completed sessions.
- Reviewed login, teacher/student dashboard and session screenshots on the
  project's compact 320 × 640 Android virtual device.
- An automatically discovered Motorola phone could not provide a Compose hierarchy
  to the instrumentation tests. That combined Gradle invocation failed for the
  phone, while both emulator tests passed. Subsequent checks use
  `ANDROID_SERIAL=emulator-5554` to exclude physical devices.
- Backend tests were not run because the backend was not changed. These checks
  establish UI/demo behavior, not real-device presence accuracy.

Review images: [teacher dashboard](../artifacts/ui/teacher-home.png),
[student dashboard](../artifacts/ui/student-home.png),
[teacher session](../artifacts/ui/teacher-session.png),
[student session](../artifacts/ui/student-session.png),
[login](../artifacts/ui/login.png).

The combined emulator test result is preserved in
[emulator-workflow-results.xml](../artifacts/ui/emulator-workflow-results.xml).

## Institution-only follow-up

Removed all synthetic workflows from the app and repository. Login, session
creation, roster, room configuration and attendance review use the configured
Supabase backend. The monitoring service always collects real radio readings and
requires a calibrated session. Legacy local sign-ins are invalidated on upgrade;
real account persistence and saved real observations are preserved. Serialized
legacy flags remain for schema compatibility and rejection safeguards.

Replaced the removed demonstration UI tests with `InstitutionAccessTest`, which
checks login has no synthetic access, credentials are required, legacy accounts
are rejected and institution account persistence survives reconstruction. Android
test scripts explicitly select the emulator to protect connected phone data.

Validation: Android assemble, app/data lint, 11 fresh core tests, 46 local
PostgreSQL backend checks and both emulator institution-access tests passed. The updated APK is deployed with `adb install -r`
to the USB-connected Motorola phone. Real account authentication and hardware
attendance require the user's institution credentials and calibrated room; they
are not claimed from login-screen or emulator checks.

## Teacher timing, calibration reuse and enrollment

Teachers now open Set up class and start immediately with a selected duration
(15–120 minutes, multiple of 5) and minimum sampled presence (1 minute through
class duration, rounded up to five-minute checkpoints). Backend and Kotlin
aggregation enforce the absolute inside-checkpoint minimum, preventing a small
number of inside samples from satisfying a high percentage alone. Older sessions
retain their original policy. Uncertain, insufficient and missing readings never
contribute to the inside minimum.

Room calibration remains a saved backend configuration by room/version. Setup
shows the saved version; calibration screens show an explicit Update calibration
action instead of requiring repeat calibration. Enrollment is available through
Add students on each teacher class card. Student accounts must already be
provisioned by the administrator, and enrollment changes apply to future sessions.

Dashboard data now reloads on app restart, and a newly started class immediately
loads its backend attendance records.

Verification: 76 local PostgreSQL checks, 14 core tests and four emulator UI tests
passed. Android build and app/data lint passed. The timing migration was deployed
to the existing Supabase project without replacing saved rooms or sessions.

## Adaptive checkpoint update

Future sessions use `max(5, ceil(durationMinutes / 3))` evenly spaced checkpoints:
15 minutes → 5, 30 → 10, 45 → 15, 60 → 20, 90 → 30, 120 → 40. The interval is
class duration divided by count, rounded down to whole milliseconds; the final
window absorbs the small division remainder. Inside requirements use
`ceil(minimumPresenceMinutes * checkpointCount / durationMinutes)`, avoiding an
extra impossible checkpoint when the interval has a division remainder. The UI
keeps scheduling details internal; teachers enter only class duration and minimum
presence. Checkpoint counts, intervals, rounding explanations and configuration
versions are omitted from class setup and attendance requirements.

Core/schema/backend capacity now supports 40 checkpoints, including validation
and database storage for slot 39. Existing room calibration and existing session
schedules are preserved. The backend continues to determine final attendance.

Verification: 168 PostgreSQL checks passed, including every supported duration,
full-duration minimums and actual upload of slot 39. All 15 core tests and four
emulator UI tests passed; Android build and app/data lint passed. The adaptive
migration was deployed to the live project. The latest APK is installed on the
connected Samsung phone with app data preserved. Teacher and student phones
should both run this version to support sessions above the old 24-slot limit.

# Contracts and operational boundaries

The source of truth for models is `mobile/presence-core/.../Models.kt`; `supabase/migrations` validates cloud input and implements the trusted classifier in PostgreSQL. `shared/presence-fixtures.json` is exercised in Kotlin and PostgreSQL. `shared/beacon-contract.json` matches the current firmware; manufacturer company bytes are stripped by Android's manufacturer-data accessor.

A checkpoint is identified by authenticated UID, session ID and slot. It records UTC capture time, configuration/algorithm versions, radio outcomes, monotonic observation/evaluation timestamps and preliminary presence result. Regular batches are compacted to fresh per-beacon medians. The backend recomputes classification; it does not trust the transmitted `result`. Reported radio measurements are still client-supplied and can be fabricated by a modified client.

Timing is anchored from the cached server-created session start to Android elapsed realtime when monitoring begins. Keep automatic date/time enabled. Clock changes during acquisition do not alter elapsed scheduling; a wrong clock at initial anchoring can invalidate timing and needs a fresh download/correct device time. Allowed acquisition timestamps are within 60 seconds of slot start and before actual session end. Scan initiation only occurs near a scheduled slot; delayed/missed slots are not backfilled.

New teacher-created sessions accept `durationMinutes` (15–120, multiple of 5) and `minimumPresenceMinutes` (1 through duration). The backend sets `totalSlots = max(5, ceil(durationMinutes / 3))`, `intervalMs = floor(durationMinutes * 60000 / totalSlots)`, `minInsideSlots = ceil(minimumPresenceMinutes * totalSlots / durationMinutes)`, and `minValid = minInsideSlots`. Eligibility requires that absolute number of inside checkpoints. Minimums are rounded up to whole checkpoint windows and describe sampled presence, not continuous observation. Missing, insufficient, uncertain and outside readings never contribute to the inside minimum. Calls without timing parameters retain the legacy 60-minute policy; sessions with missing/zero `minInsideSlots` retain the original percentage aggregation. Server session configuration and roster snapshots are immutable. Teacher enrollment/config edits affect future sessions. Roles are trusted database profiles provisioned outside the client; editable Auth user metadata is ignored.

Cloud summary states are provisional until upload grace expires and scheduled finalization completes. Teacher overrides are separate from measured summary and always audit original/new values. Offline cached dashboards are labelled; lack of updates does not prove absence. Private PostgreSQL tables have RLS enabled and no client grants; all access passes through the authenticated API.

`POST /rest/v1/rpc/presence_api` accepts `{ "operation": "listClasses", "payload": {} }` with the public project key in `apikey` and the signed-in user's access token in `Authorization: Bearer ...`. It returns the model as JSON. Operations: `identity`, `listClasses`, `listSessions`, `listSessionStates`, `startSession`, `endSession`, `submitCheckpoint`, `listAttendance`, `listCheckpoints`, `overrideAttendance`, `roomConfig`, `roomBeacons`, `publishConfig`, `listRoster`, `enrollStudent`, `removeEnrollment`.

Authentication uses Supabase Auth password/refresh/recovery endpoints. The APK contains only the public key. Access/refresh sessions are encrypted with Android Keystore and excluded from backup. Recovery links are verified inside the app before the user sets a password; user passwords are never stored in the application.

Permanent upload errors (HTTP 400/403/404/409/422) mark local evidence `REVIEW_REQUIRED`; authentication, network, rate-limit and server outages retain it as pending. Accepted duplicate points remain idempotent after session closure; conflicting evidence is rejected. All writes for a session serialize on its database row so uploads, finalization and overrides cannot overwrite each other's summaries.

## Teacher workflow

`startSession` accepts `{ "classId": "class-a101", "durationMinutes": 60,
"minimumPresenceMinutes": 40 }`. Start time is server time; end time and upload
grace are derived by the backend. The teacher must be assigned to both the class
and its calibrated room. Existing active sessions cannot be replaced.

`publishConfig` saves radio calibration by room/version, independently of class
duration. New sessions reuse the active room configuration; starting a class
does not mutate or republish that room configuration. Existing sessions retain
their own threshold and attendance-policy snapshots even after recalibration.

`enrollStudent` accepts class ID and a provisioned student email. Teachers may add
students to their assigned classes, but cannot provision trusted roles or promote
accounts. Enrollment updates apply to subsequent session snapshots. Every trusted
student profile appears in the private SQL view `presence_private.students`, and
every current class membership appears in `presence_private.class_enrollments`
and `presence_private.student_enrollments`. A database trigger keeps these records
in sync with the class document used by the API.

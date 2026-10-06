# Android application: implementation review and build plan

> Implementation update: Android 13+ / target Android 16 and the real Supabase Free backend are now implemented. Supabase Auth, private PostgreSQL tables, the authenticated attendance API and Cron replace the Firebase proposal below. The deployed project is `wrupuujtrtiegqjbakfz` in Mumbai. Both initial accounts and Classroom A101 are provisioned. See `docs/supabase-setup.md` and `docs/verification.md` for the current implementation and tests. The original review below is retained as planning history; its minimum API 29 and Firebase recommendations are superseded.

Prepared 3 October 2026 from `IMPLEMENTATION_PLAN.md` and all current firmware source files. This is a planning deliverable; Android code and Firebase resources have not been created. Hardware operation is accepted from the project owner's report, not independently bench-tested in this review.

## 1. Summary of the existing implementation plan

The project is a classroom phone-presence and attendance prototype. Four ESP32-WROOM-32 boards broadcast Wi-Fi and BLE identities. A student's Android phone measures their signal strengths, determines classroom presence locally, and uploads checkpoint evidence. A teacher manages class sessions and reviews attendance.

The original plan correctly separates five responsibilities:

| Layer | Responsibility |
| --- | --- |
| ESP firmware | Advertise classroom/beacon identities and serial health information |
| Android scanners | Collect and normalize Wi-Fi/BLE RSSI |
| Pure Kotlin presence core | Filter evidence, classify it, explain decisions, aggregate checkpoints |
| Android UI | Student/teacher workflows and diagnostics |
| Firebase | Authentication, classroom configuration, evidence sync, attendance records |

This separation should remain. The phone must calculate presence because Firebase cannot scan its radios. Temporary internet loss must not interrupt local measurement once a session and its configuration are cached.

The proposed measurement flow is a 25-second BLE burst and one permitted Wi-Fi scan at each five-minute checkpoint. A 60-minute session has 12 checkpoint slots, starting at minutes 0, 5, ..., 55. BLE readings are reduced using a median; independent Wi-Fi/BLE evidence is combined with signal spread and an explainable classification policy. Results are `INSIDE`, `OUTSIDE`, `UNCERTAIN`, or `INSUFFICIENT_DATA`.

The initial attendance policy requires at least eight valid checkpoints and at least 60% inside checkpoints among valid checkpoints. Uncertain checkpoints do not count as inside. A separate hysteresis mechanism stabilizes the displayed live status. These are sampling rules: they do not prove continuous presence for 60% of the class duration.

Calibration covers classroom center/corners/edges, the doorway, hallway and adjacent rooms. Thresholds must be selected from labelled measurements and stored as versioned configurations. Simulation uses the same core as real scans. Firebase stores users, classroom/beacon configurations, sessions, checkpoints, attendance and calibration metadata. Students must not write final attendance; teachers only access their assigned classes.

The original build order is contracts → hardware → presence simulation → Android scanners → calibration → Firebase → UI integration → complete-session demonstration. Since the hardware already works, continue from contracts and the presence core. Build UI shells alongside simulation, but do not claim reliable attendance until real scanning, calibration and backend authorization pass their gates.

## 2. Repository findings and corrections needed

Only the original plan and a standalone firmware sketch currently exist. There is no Android project, shared contract, backend implementation or recorded calibration dataset in this folder.

### Use the implemented beacon protocol

| Item | Original example | Current firmware |
| --- | --- | --- |
| BLE service | `a1b2c3d4-0000-1000-8000-00805f9b34fb` | Short UUID `0xCA01`, expanded as `0000ca01-0000-1000-8000-00805f9b34fb` |
| Manufacturer identity | CP1 prefix with proposed check/signature | Company ID `0xFFFF`, then UTF-8 `CP1\|ROOM_A101\|1` |
| Beacon identifier | Full ESP ID in proposed payload | Number mapped to registered `CLASSROOM_ESP_1` |
| Wi-Fi identity | `CP_ROOM_A101_ESP_1` | Same |
| Transmit power | Explicit example settings | No explicit Wi-Fi/BLE power configuration in current code |
| Recovery | Proposed BLE restart | Current code starts advertising and logs health; no explicit restart logic |

Preserve the working firmware. Capture real advertisements on the target phone and freeze the actual packet contract before creating Android filters. Treat manufacturer data as the primary parser input; verify that the service UUID is actually present over the air before relying on it as a mandatory filter. Android's `getManufacturerSpecificData(0xFFFF)` returns the manufacturer payload after the company identifier; do not strip those bytes twice. Reject malformed payloads, unknown rooms/numbers and unsupported protocol versions. Do not use a BLE name or changing MAC address as identity.

Current advertisements have no cryptographic authenticity. A prefix is not a signature; the app cannot promise resistance to copied/spoofed beacon broadcasts. Likewise, measuring a logged-in student's phone does not prove that the person carrying it is that student.

### Fix the score before implementation

The original score awards positive points for agreement, detected count and small spread even when all signals are weak. Example: four Wi-Fi readings of -90 dBm and four BLE readings of -95 dBm pass neither strength threshold, but can receive +2 for detected counts, +2 for agreement that signals are weak, and +1 for each spread. The score becomes 6, producing uncertainty rather than the required outside fixture. Positive bonuses must not substitute for evidence of presence.

Use explicit data-quality and strength gates before any score. Agreement on outside evidence supports `OUTSIDE`; it must not raise an inside score. Scores can explain a result, but the classification truth table must take precedence. Specify exact meanings for agreement, conflict and spread rather than leaving them to individual modules.

### Define missing data, timing and filtering

- Disabled radios, permission denial, scan failure and stale results are measurement failures, not evidence that a student is outside.
- Define validity explicitly: in the first release, both technologies must successfully produce fresh observations and detect the calibrated minimum registered-beacon count. Single-technology fallback is disabled until separately validated. Sparse results are insufficient data; fresh adequate but conflicting evidence is uncertain.
- Fresh observations can include weak readings; weakness does not invalidate a scan. Record missing beacon observations separately from failed scan attempts.
- Use per-technology timestamps, sample counts and scan outcome fields. A single combined timestamp cannot establish whether both readings are fresh.
- Never reuse cached Wi-Fi results as a new checkpoint. Use scan timestamps against a monotonic clock, with explicit unit conversion and freshness limits.
- A five-value median across five-minute checkpoints spans 20 minutes and delays entry/exit detection. Filter BLE samples within the current burst; expire historical data. Any smoothing across checkpoints must have a calibrated maximum age and must not hide missing observations.
- Define a unique slot index, allowed collection window and lateness tolerance. Late joins leave earlier slots missing. Process restarts do not create replacement historical evidence.
- Store preliminary checkpoint status separately from the smoothed display status. Attendance uses checkpoint evidence; a sticky display must not fabricate inside checkpoints.

## 3. Proposed application scope and role permissions

Build one native Android application with two entry buttons: **Student login** and **Teacher login**. Both use the same identity provider. The selected button is a navigation preference, not authorization. After authentication, load the trusted account role and memberships before routing to an authorized dashboard.

Start with Firebase email/password and password reset; enable institutional sign-in later if required. Provision teacher roles from a trusted administrative process. Student registration may create only an unprivileged profile; enrollment requires an approved roster or teacher/institution invitation. Users cannot modify role, enrollment or teacher assignments themselves. Firebase custom claims must be assigned from a privileged server environment, with membership checks enforced separately. [Firebase role documentation](https://firebase.google.com/docs/auth/admin/custom-claims).

| Capability | Student | Teacher |
| --- | --- | --- |
| View/edit permitted profile fields | Own profile | Own profile |
| View subjects and enrollment | Own assignments | Assigned classes and roster |
| Join an active session | If enrolled | Manage own class session |
| Start/stop phone monitoring | Own session | Calibration/diagnostic mode only |
| See live status/reasons | Own result | Received summaries for assigned class |
| View checkpoint/attendance history | Own records | Assigned students' records |
| Create/end class session | No | Assigned class only |
| Publish calibration configuration | No | Authorized teacher, after review |
| Change final attendance | No | Audited override in assigned class |
| Export roster/attendance | No | Assigned class only |
| Assign privileged account roles | No | No; trusted administration only |

Student screens: login/reset, dashboard, enrolled classes, active-session readiness, monitoring, own attendance history, profile/settings. Monitoring shows status, last successful scan, checkpoint coverage, explanation, pending sync and Stop. Do not label a provisional local result as final attendance.

Teacher screens: login/reset, assigned classes, roster, create/start/end session, session dashboard, student evidence detail, review/override, attendance export, calibration and beacon diagnostics. Display when each student's evidence was received; silence is `NO_RECENT_UPDATE`, not proof of absence. A teacher dashboard cannot directly scan students' phones or show continuous live location.

Biometric unlock can be a later convenience for a previously authenticated session. Do not collect biometric templates or treat biometric unlock as new backend authentication.

## 4. Architecture and technology choices

Proposed stack: Kotlin, Jetpack Compose, Material 3, ViewModel/StateFlow, coroutines, Room, Firebase Auth/Firestore and TypeScript server functions. Use WorkManager for retryable uploads, not as an exact five-minute scanner scheduler. Choose stable compatible dependency versions and the supported compile/target SDK when scaffolding. Initial proposal: minimum SDK 29; document and test each supported OS rather than assuming uniform behavior.

```text
mobile/
  app/                 Compose, navigation, permission UX, monitoring service, DI
  presence-core/       Pure Kotlin models, classifier, attendance aggregation
  scanner-android/     BLE/Wi-Fi adapters and packet parsing
  data/                Room, authenticated repositories, upload outbox
  test-fixtures/       Simulated and anonymized captured measurements
shared/                Actual beacon contract and versioned config schema
firebase/              Rules, indexes, server functions and emulator tests
docs/                  Setup, calibration, field results, operating limits
```

Dependencies flow from app to scanner/data/core; scanner and data can use core contracts. Core depends on none of the other modules. Teacher session administration is a separate use case from student monitoring. Firmware remains independent.

Contracts must include beacon identity; raw technology observation; scan outcome; per-beacon filtered reading; immutable session/config snapshot; checkpoint result; attendance summary; sync state and audit event. Include reason codes, algorithm/config versions, app version, checkpoint slot and device/OS information needed for diagnostics. Raw RSSI is retained locally during calibration by default; regular cloud checkpoints contain compact evidence sufficient to validate the selected deterministic policy.

## 5. Session and measurement workflow

1. Teacher selects an assigned class and a calibrated room configuration. The backend creates a session with roster snapshot, start/end times, 12 slots, attendance policy and immutable configuration version.
2. Student signs in, opens an enrolled active session and passes readiness checks: permissions, BLE/Wi-Fi availability, location settings, supported hardware and cached session/configuration.
3. Student explicitly starts monitoring while the app is visible. A foreground service shows an ongoing notification and Stop action.
4. At each scheduled slot, collect a BLE burst and request/observe an allowed Wi-Fi scan. Record errors and observation ages even when classification is impossible.
5. Parse only registered beacons, filter current observations, evaluate quality, classify evidence and save the checkpoint to Room atomically with an outbox entry.
6. Upload with stable identifiers and retries. Show local evidence immediately; teacher sees only accepted uploaded evidence and its age.
7. At end/stop, close local acquisition and preserve remaining uploads. Teacher ending a session is observed online; offline devices follow the cached scheduled end and reconcile changes after reconnecting.
8. A backend aggregation function creates a provisional summary at session end, then accepts approved late uploads during a configured grace period. After reconciliation it publishes final attendance. Beyond-grace uploads go to review rather than silently changing a final record.

Use monotonic elapsed time for acquisition intervals and UTC/server timestamps for session identity and synchronization. Persist enough timing metadata to detect clock changes. Do not promise accurate timestamps from a compromised offline phone. On reboot or process death, preserve completed slots and mark gaps; require a visible restart if Android cannot legally resume scanning. Stop/join another session cannot duplicate existing slots.

First sign-in and first session/config download require connectivity. Existing authenticated students can continue an already cached session offline. Teacher session creation requires connectivity in release one; offline sessions need a separate reconciliation design.

Android requirements must be implemented per OS. Wi-Fi scan APIs require location permission/settings and Wi-Fi permissions; `NEARBY_WIFI_DEVICES` alone does not replace the scan requirements. Scan requests can fail and results can be old. Five-minute scheduling remains subject to platform/device throttling. [Android Wi-Fi scanning](https://developer.android.com/develop/connectivity/wifi/wifi-scan).

For Android 12+, request `BLUETOOTH_SCAN`; retain location access because this application derives classroom presence. Do not assert `neverForLocation`, which can also filter beacons. Request connect/advertise permissions only for APIs actually used. [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions).

For the proposed presence use case, evaluate a `location` foreground service and its required manifest/runtime permissions; add another type only if the implemented work qualifies. Start from the visible student action and handle denied/revoked permissions. A foreground service is not a blanket exemption from radio throttling or manufacturer battery restrictions. [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [service start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).

Offline testing should disable internet while leaving BLE/Wi-Fi scanning enabled. Ordinary airplane mode can disable the radios and is not a sufficient test of offline presence.

## 6. Deterministic presence and attendance policy

Implement this initial classification contract, then calibrate its parameters:

| Evidence | Preliminary result |
| --- | --- |
| Scan unavailable/stale, too few usable observations, or unsupported configuration | `INSUFFICIENT_DATA` |
| Both technologies have adequate fresh evidence and meet their calibrated strong-signal rules, without unresolved imbalance | `INSIDE` |
| Both have adequate fresh evidence and meet calibrated weak-signal/outside rules | `OUTSIDE` |
| Adequate evidence but conflicting technologies, boundary readings or unresolved imbalance | `UNCERTAIN` |

Define separate inside/outside thresholds with a margin, minimum detected count and minimum strong/weak count per technology. Start from the original -72/-75 dBm values only as experimental inputs. Calibrate outside margins and count/spread rules rather than inventing final numeric values. Spread is supporting evidence; low spread does not make weak readings inside, and a student near a corner may legitimately have unequal signals.

Reset smoothing on room/session/config change, expire readings and define warm-up behavior. Each decision emits reason codes and relevant counts/threshold comparisons. Temporal confidence represents recent evidence consistency, not a statistically established probability of being inside.

For a completed 60-minute session:

```text
valid = INSIDE + OUTSIDE + UNCERTAIN checkpoints
insideFraction = INSIDE / valid, only when valid > 0
eligible = valid >= 8 AND insideFraction >= 0.60
```

Use integer counts or rational comparisons to avoid rounding changing eligibility. Missing/insufficient slots are excluded from the fraction but still reduce coverage. Keep eligible, insufficient coverage and below-threshold outcomes distinct; uncertain/borderline cases are available for teacher review.

| Counts | Policy result |
| --- | --- |
| 8 inside, 4 outside | Eligible: 8/12 |
| 7 inside, 5 outside | Below threshold: 7/12 |
| 5 inside, 3 outside, 4 missing | Eligible under the original rule: 5/8 |
| 7 inside, 5 missing | Insufficient coverage: only 7 valid |
| 5 inside, 4 uncertain, 3 outside | Below threshold: 5/12 |

The third example is an important policy choice: 5 of 12 expected checkpoints can qualify. Preserve that original policy for the prototype, make it visible to teachers, and distinguish it from a stricter future policy using all expected slots as the denominator. Joining late or stopping early must never reduce the session's required checkpoint count to benefit the student.

## 7. Backend, persistence and authorization

Suggested Firestore structure:

```text
users/{uid}
classes/{classId}
classes/{classId}/members/{uid}
classrooms/{roomId}/beacons/{espId}
classrooms/{roomId}/presenceConfigs/{version}
classSessions/{sessionId}
classSessions/{sessionId}/participants/{uid}
classSessions/{sessionId}/checkpoints/{uid_slot}
classSessions/{sessionId}/attendance/{uid}
classSessions/{sessionId}/auditEvents/{eventId}
calibrations/{calibrationId}
```

Separate a subject/class roster from a physical room: several classes can use the same room. Session participant/config snapshots prevent later enrollment or threshold edits from rewriting historical results.

Use server-authorized operations for starting/ending sessions, assigning enrollment, validating checkpoints, final attendance and overrides. Functions are a required part of this app plan because the client cannot own final attendance. A Kotlin core on-device and TypeScript validator/aggregator must share versioned fixtures and identical boundary semantics; reject unknown algorithm/config versions. Recompute classification from submitted compact evidence where possible, while acknowledging that client-reported measurements themselves remain untrusted.

Enforce membership/ownership, field allowlists, legal session states, valid slot ranges, immutable IDs/config versions and timestamp/grace checks. Students cannot enumerate classmates, access raw calibration data, publish configurations or write attendance. Teacher exports and overrides are limited to assigned classes. Every override records actor, timestamp, original value, new value and reason; preserve measured evidence.

Uploads use an idempotency key containing session, UID and slot. Store the first accepted payload and return the same acknowledgement on identical retry; conflicting retries require explicit handling rather than silent overwrite. Partition Room records and outbox items by authenticated UID. Never upload a former user's pending items as a newly signed-in account.

Room is the durable acquisition record and explicit upload outbox. Firestore's Android cache can assist reads, but do not run two independent checkpoint upload paths. Firestore supports offline persistence, so define the source of truth and acknowledgement boundaries clearly. [Firestore offline behavior](https://firebase.google.com/docs/firestore/manage-data/enable-offline).

## 8. Build milestones and acceptance gates

| Milestone | Deliverables | Gate before proceeding |
| --- | --- | --- |
| 1. Contracts and project foundation | Native Gradle project, module boundaries, actual packet/config contracts, sample observations | Parse captured packets from all four ESPs; core builds independently |
| 2. Core and simulation | Quality gates, classifier, attendance aggregation, explanatory reasons, simulated UI | Deterministic regression tests pass, including all-weak and no-data cases |
| 3. Real scanning and local sessions | BLE/Wi-Fi adapters, readiness UI, foreground service, Room/outbox, diagnostics | All four boards identifiable; stale/failed scans rejected; locked-screen 60-minute acquisition checked |
| 4. Calibration and holdout evaluation | Labelled datasets, candidate thresholds, published config, field report | Report inside/outside error rates and uncertainty/coverage on unseen recordings |
| 5. Authentication and backend | Two login entries, trusted roles, class membership, rules, session API, validated sync/finalization | Unauthorized actions denied; cached session works without internet; retry is idempotent |
| 6. Complete student/teacher UI | Role dashboards, session management, history, review, exports | Both roles complete their workflows on separate accounts/devices |
| 7. End-to-end release verification | Signed/installable APK, setup docs, limitations and demonstration results | Full-session policy, offline recovery, process interruption and role isolation verified |

Do not estimate a completion date until the target Android phones, Firebase project access and calibration room are available. Each milestone must produce a demonstrable result and its evidence, not just completed screens.

## 9. Testing and accuracy evidence

Core tests cover strong-balanced, all-weak, no-data, malformed/unknown identity, missing beacon, conflict, imbalance, noise, warm-up, stale evidence, room/config reset, hysteresis and exact attendance boundaries. Include the original six fixtures, but make expected results precise for each frozen configuration. Backend tests cover student impersonation, cross-class access, self-promotion, duplicate/conflicting writes, teacher ownership and forbidden attendance/config writes.

Device tests cover location/Bluetooth/Wi-Fi disabled, approximate-only permission, denied/revoked permissions, scan throttling, old scan results, screen lock, battery saver, process death, force-stop/reboot, network loss/recovery, sign-out/account switch, late joins and early teacher end. Test at least two distinct phone models if claiming compatibility beyond one demonstrated device. Keep production throttling enabled during acceptance tests.

Collect labelled independent trajectories on different days: classroom center, corners, doors, corridor, adjacent room, sitting/standing, phone in hand/pocket/bag, changing orientation, crowding and open/closed doors. Tune on one dataset and evaluate on held-out recordings. Keep recordings from the same run together to avoid leaking near-identical samples into both sets.

Report an inside/outside confusion matrix, false-inside rate, false-outside rate, uncertain rate, insufficient-data rate, valid-slot coverage, entry/exit delay and battery consumption. Report session-level attendance results as well as checkpoint accuracy. Do not exclude uncertain/missing cases from reporting to inflate accuracy. No accuracy percentage is established by the current repository.

Set acceptable false-inside/false-outside and coverage targets with the project supervisor before acceptance; do not promise 100% from RSSI. If adjacent-room and inside measurements overlap, retain uncertainty/manual review and reconsider placement rather than forcing a confident classification. For a crowded class, also test concurrent uploads and teacher dashboard query/Firestore cost behavior.

## 10. Defaults and information needed during implementation

Proceed with one app, two role dashboards, email/password, trusted teacher provisioning, teacher-managed enrollment, both-radio evidence, server-owned attendance, original eight-valid/60% policy and local calibration recordings. These defaults are proposals, not claims about existing implementation.

Before real-device/backend milestones, record: app/package name; target phone models and OS versions; whether all four boards passed the long-duration test; room layout and placement; Firebase project/access; roster/teacher provisioning process; desired data retention; supervisor-approved accuracy targets; upload grace period and missed-session/override policy. Public-store distribution and institutional SSO can follow the working prototype.

The first concrete coding task is to scaffold `mobile/presence-core`, freeze the existing firmware contract and implement the corrected classifier plus simulation fixtures. The student/teacher navigation shell can then connect to these tested use cases while real scanners and backend authorization are added through the milestone gates.

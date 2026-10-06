# Classroom Presence System — Implementation Plan

## 1. Purpose and first release scope

Build a classroom-presence layer that uses four ESP beacons and an Android phone to decide whether a student's phone is `INSIDE`, `OUTSIDE`, `UNCERTAIN`, or has `INSUFFICIENT_DATA`.

The phone measures Wi-Fi and BLE RSSI from the four ESPs. RSSI is **not** treated as an exact distance measurement. The decision uses multiple signals, filtering, configurable rules, and time-based aggregation.

This plan deliberately separates the system into modules. A change to ESP advertising must not require changing Firebase logic; a change to the UI must not change the presence rules; and Firebase must not be needed for the phone to make a local decision.

Initial target:

- Four ESP32-WROOM-32 boards.
- Android phone application.
- Arduino IDE for ESP flashing.
- Firebase Authentication + Cloud Firestore for accounts, classes, configuration, and synced attendance.
- Local/mobile presence calculation, with Firebase used for storage and teacher-facing data.

## 2. Important technical decisions

### What each component does

| Component | Responsibility | Must not do |
| --- | --- | --- |
| ESP firmware | Broadcast an immutable Wi-Fi and BLE identity; report basic health | Identify students or decide attendance |
| Android scanner | Read Wi-Fi and BLE RSSI and normalize data | Draw UI-specific conclusions |
| Presence core | Filter, fuse, score, explain, and temporally aggregate readings | Call Firebase or Android APIs directly |
| Mobile UI | Display status, charts, calibration, and teacher/student screens | Contain decision formulas |
| Firebase | Authentication, configuration sync, session/attendance storage | Be required for live presence decisions |
| Cloud Functions (optional) | Validate uploaded payloads and calculate final server summaries | Replace phone RSSI scanning |

### Why calculation belongs on the phone

Firebase cannot scan a phone's nearby Wi-Fi or Bluetooth radios. The Android app must scan and calculate the immediate result locally. Firebase then receives a compact, auditable checkpoint result and optional raw readings for reporting. This makes the system work during temporary internet loss and avoids a fake or delayed "backend RSSI calculation."

### ESP32-WROOM-32 constraints

ESP32-WROOM-32 supports 2.4 GHz Wi-Fi and BLE advertising. Wi-Fi and BLE share one radio, so they operate by time sharing. For this prototype, each ESP should run:

- a Wi-Fi SoftAP with a unique SSID; and
- a BLE advertisement containing a unique beacon ID.

The phone does **not** connect to four Wi-Fi access points. It only scans their beacons and reads RSSI.

Use standard BLE advertisements compatible with the classic ESP32. Do not require BLE 5 extended advertising. A future ESP32-C3/S3 implementation belongs in a separate `boards/` folder and implements the same beacon contract.

## 3. Repository layout and isolation rules

Create this structure before feature work:

```text
classroom-presence/
├── docs/
│   ├── hardware-setup.md
│   ├── calibration-guide.md
│   ├── firebase-setup.md
│   └── api-contract.md
├── shared/
│   ├── beacon-contract.json
│   ├── presence-config.schema.json
│   └── examples/
├── firmware/
│   ├── README.md
│   ├── classroom_beacon/                 # Arduino IDE sketch project
│   │   ├── classroom_beacon.ino
│   │   ├── BeaconConfig.h
│   │   ├── BeaconWifi.cpp
│   │   ├── BeaconWifi.h
│   │   ├── BeaconBle.cpp
│   │   ├── BeaconBle.h
│   │   ├── BeaconHealth.cpp
│   │   └── boards/
│   │       ├── esp32_wroom_32.h
│   │       ├── esp32_c3.h
│   │       └── esp32_s3.h
│   └── configs/
│       ├── classroom-a-esp1.h
│       ├── classroom-a-esp2.h
│       ├── classroom-a-esp3.h
│       └── classroom-a-esp4.h
├── mobile/
│   ├── app/                              # UI, permissions, Firebase wiring only
│   ├── scanner-android/                  # Android Wi-Fi/BLE API adapters only
│   ├── presence-core/                    # Pure Kotlin; no Android/Firebase imports
│   ├── data/                             # Room cache and Firebase repositories
│   └── test-fixtures/
├── firebase/
│   ├── firestore.rules
│   ├── firestore.indexes.json
│   └── functions/                        # Optional TypeScript Cloud Functions
├── tests/
│   ├── presence-core/
│   ├── firmware-checklist.md
│   └── field-test-sheets/
└── IMPLEMENTATION_PLAN.md
```

### Non-interference rules

1. `presence-core` accepts plain data objects and configuration, then returns a result. It never imports Android, Firebase, UI, or hardware code.
2. The Android scanner only converts platform scan results into the shared reading contract. It does not decide `INSIDE` or `OUTSIDE`.
3. Firmware only obeys its local configuration header and the shared beacon contract. It never knows class rosters or student identity.
4. UI calls a use-case/service interface; it must not calculate RSSI scores itself.
5. Firebase repositories can be replaced or disabled without affecting live local detection.
6. Board-specific firmware settings stay inside `firmware/classroom_beacon/boards/`; do not scatter `#ifdef` checks across application code.

## 4. Shared contracts — build these first

These are the only interfaces modules use to communicate.

### 4.1 Beacon identity contract

Each ESP receives fixed configuration:

```json
{
  "protocolVersion": 1,
  "classroomId": "ROOM_A101",
  "espId": "CLASSROOM_ESP_1",
  "wifiSsid": "CP_ROOM_A101_ESP_1",
  "bleServiceUuid": "a1b2c3d4-0000-1000-8000-00805f9b34fb",
  "bleManufacturerPrefix": "CP1",
  "txPowerDbm": 0
}
```

The BLE payload contains a protocol version, classroom ID, ESP ID, and a short signature/check value. The Android app accepts only IDs registered in the classroom configuration. Never identify a beacon only by its mutable BLE MAC address.

### 4.2 Normalized mobile reading

```kotlin
data class EspReading(
    val espId: String,
    val capturedAtMs: Long,
    val wifiRssiDbm: Int?,
    val bleRssiDbm: Int?,
    val wifiAvailable: Boolean,
    val bleAvailable: Boolean
)
```

### 4.3 Result contract

```kotlin
enum class PresenceStatus { INSIDE, OUTSIDE, UNCERTAIN, INSUFFICIENT_DATA }

data class PresenceResult(
    val status: PresenceStatus,
    val score: Int,
    val detectedEspCount: Int,
    val wifiSpreadDb: Int?,
    val bleSpreadDb: Int?,
    val wifiBleAgreement: Boolean?,
    val temporalConfidence: Double,
    val reasons: List<String>,
    val timestampMs: Long
)
```

Configuration is versioned and stored locally first, then optionally synced from Firebase. Each checkpoint records the configuration version used.

## 5. Firmware module (Arduino IDE)

### 5.1 Firmware responsibilities

For each board, the firmware starts automatically after flashing and power-up:

1. Loads one local `BeaconConfig` profile.
2. Starts the uniquely named Wi-Fi SoftAP beacon.
3. Starts BLE advertising with the shared payload format.
4. Emits serial diagnostics: board type, ESP ID, SSID, BLE payload, heap, and uptime.
5. Restarts advertising safely after a BLE stack error.

No cloud credentials, student data, or attendance rules are placed in firmware.

### 5.2 Arduino IDE setup checklist

1. Install the current Espressif ESP32 board package through Arduino IDE Boards Manager.
2. Select the matching board, initially `ESP32 Dev Module` for ESP32-WROOM-32.
3. Select the correct COM port.
4. Set the active config include to one of the four `firmware/configs/classroom-a-esp*.h` files.
5. Verify serial output at the configured baud rate.
6. Flash one board at a time and label the physical enclosure with its ESP ID.
7. Scan the SSID and BLE advertisement with a phone before installing the board in a classroom corner.

### 5.3 Per-board configuration

Only these values should normally vary between boards:

```cpp
constexpr char ESP_ID[] = "CLASSROOM_ESP_1";
constexpr char CLASSROOM_ID[] = "ROOM_A101";
constexpr char WIFI_SSID[] = "CP_ROOM_A101_ESP_1";
constexpr uint8_t WIFI_CHANNEL = 1;
constexpr int8_t WIFI_TX_POWER_DBM = 0;
constexpr int8_t BLE_TX_POWER_DBM = 0;
```

Keep all four ESPs on the same carefully chosen non-congested 2.4 GHz channel where possible. Choose Wi-Fi transmit power deliberately and keep it consistent across beacons. Record the final values in `docs/hardware-setup.md`.

### 5.4 Firmware acceptance test

Before any mobile development, prove all four devices:

- show four unique SSIDs;
- advertise four unique BLE identities;
- reboot and resume advertising;
- stay stable for 90 minutes under USB power;
- remain distinguishable when all four run together.

## 6. Android scanning module

The initial implementation should target Android only. It should use Kotlin and expose interfaces that can be tested using fake scan data.

### 6.1 Required scanner interfaces

```kotlin
interface WifiRssiScanner {
    suspend fun scan(knownBeacons: List<BeaconIdentity>): List<EspReading>
}

interface BleRssiScanner {
    suspend fun sample(knownBeacons: List<BeaconIdentity>, durationMs: Long): List<EspReading>
}
```

`scanner-android` implements these interfaces using Android APIs. `presence-core` has no dependency on those APIs.

### 6.2 Permission and operating constraints

- Android 12+: request `BLUETOOTH_SCAN`; request only other Bluetooth permissions actually needed.
- Wi-Fi results require the Android Wi-Fi scan API and device/location settings permitted by the Android version.
- Wi-Fi scans are throttled by Android. Do not design around constant scans.
- A 60-minute monitoring session should run as a clearly visible foreground service, with an ongoing notification and an explicit Stop action.
- Do not claim reliable background scanning when a manufacturer battery optimizer prevents it; document and test the target phone models.

### 6.3 Five-minute checkpoint algorithm

At each checkpoint (0, 5, 10, …, 55 minutes):

1. Start a 20–30 second BLE sampling burst, collecting all matching advertisements.
2. Trigger/obtain one allowed Wi-Fi scan result in the same window.
3. Reduce BLE samples per ESP to a median RSSI.
4. Create one normalized reading set for all four ESPs.
5. Pass it to `presence-core`.
6. Save a local checkpoint immediately.
7. Queue a Firebase sync; retry later if offline.

This yields 12 checkpoints for a 60-minute class. Set `minValidCheckpoints` (recommended 8) so missing scans cannot be treated as presence.

## 7. Presence-core calculation module

This is the most important module and must be independently unit tested. Start with deterministic logic only; no ML is required.

### 7.1 Pipeline

```text
Normalized readings
  → per-ESP median filter
  → independent Wi-Fi and BLE analyses
  → relative-strength / spread analysis
  → technology fusion
  → explainable score and preliminary status
  → temporal voting + hysteresis
  → PresenceResult
```

### 7.2 Data processing rules

1. Keep a rolling window of the most recent readings per ESP and technology.
2. Use a configurable moving median (`filterWindow`, initial value 5) to reject sharp RSSI spikes.
3. Calculate per technology: detected count, pass count, maximum RSSI, minimum RSSI, and spread.
4. Rank corners by signal strength for visualization only. Do not infer an exact phone coordinate.
5. Treat a missing signal as missing data, not automatically as a weak measurement.

### 7.3 Explainable score (initial configurable policy)

The following is a starting policy, not a permanent hard-coded truth:

```text
+1 for every ESP with acceptable Wi-Fi RSSI
+1 for every ESP with acceptable BLE RSSI
+2 when Wi-Fi and BLE each meet their minimum detected count
+2 when both technologies agree on the broad result
+1 when Wi-Fi spread is at or below its configured maximum
+1 when BLE spread is at or below its configured maximum
-2 when Wi-Fi and BLE strongly conflict
```

Suggested preliminary classification:

```text
score >= insideScoreThreshold  → INSIDE
score <= outsideScoreThreshold → OUTSIDE
otherwise                      → UNCERTAIN
```

Every point added or removed must append a human-readable reason. Thresholds are never fixed until calibration is complete.

### 7.4 Temporal attendance policy

- A checkpoint produces a preliminary presence status.
- The session aggregates only valid checkpoints.
- `insidePercentage = insideCheckpoints / validCheckpoints`.
- At session end, attendance eligibility requires `insidePercentage >= 0.60` and `validCheckpoints >= minValidCheckpoints`.
- `UNCERTAIN` does not count as inside by default. Make this policy configurable and visible to the teacher.
- Hysteresis prevents the displayed live state from flipping frequently. Example: enter `INSIDE` at 80% recent-inside evidence; leave it at 60% or less.

### 7.5 Initial configuration file

```json
{
  "version": 1,
  "wifiRssiThresholdDbm": -72,
  "bleRssiThresholdDbm": -75,
  "maxWifiSpreadDb": 30,
  "maxBleSpreadDb": 30,
  "minDetectedEspsPerTechnology": 3,
  "insideScoreThreshold": 8,
  "outsideScoreThreshold": 3,
  "filterWindow": 5,
  "checkpointIntervalMinutes": 5,
  "bleSamplingDurationSeconds": 25,
  "sessionMinutes": 60,
  "minValidCheckpoints": 8,
  "attendanceInsidePercentage": 0.60,
  "enterInsidePercentage": 0.80,
  "exitInsidePercentage": 0.60
}
```

These values are placeholders for early tests only. Calibration decides final values per classroom.

## 8. Calibration and simulation modules

### 8.1 Calibration

The UI you build should provide labelled collection at:

- Inside: center, front-left, front-right, back-left, back-right, side areas.
- Outside: door, hallway, just beyond each wall where practical, adjacent room.

Each recorded set contains location label, timestamp, device model, firmware/configuration version, and raw Wi-Fi/BLE readings. The analysis screen reports median, min, max, standard deviation, detection count, and spread.

The app may suggest thresholds between clearly separated inside/outside distributions, but a human must save the final configuration. This is calibration, not machine learning.

### 8.2 Simulation

Build the simulation mode before connecting UI to hardware. It accepts the four Wi-Fi and four BLE values, runs the exact same `presence-core`, and shows score and reasons.

Required fixtures:

1. Strong balanced signals → `INSIDE`.
2. One ESP absent → `UNCERTAIN` or configured outcome.
3. All signals weak → `OUTSIDE`.
4. Wi-Fi/BLE conflict → `UNCERTAIN`.
5. Large imbalance → `UNCERTAIN` or configured outcome.
6. Noisy sequence → filtered stable output and hysteresis demonstration.

## 9. Firebase module

Firebase is optional for presence calculation but recommended for the application backend.

### 9.1 Firebase services

- **Firebase Authentication:** email/password or institutional sign-in. Android biometric authentication only unlocks a previously authenticated local session; it does not replace Firebase identity.
- **Cloud Firestore:** classrooms, beacon registrations, configuration versions, class sessions, checkpoint summaries, attendance summaries, calibration metadata.
- **Firebase Storage (optional):** CSV exports or large calibration attachments.
- **Cloud Functions (optional):** validate write shape, protect final attendance fields, build server-side class summaries.

### 9.2 Firestore collections

```text
users/{uid}
classrooms/{classroomId}
classrooms/{classroomId}/beacons/{espId}
classrooms/{classroomId}/presenceConfigs/{version}
classSessions/{sessionId}
classSessions/{sessionId}/checkpoints/{uid_checkpointNumber}
classSessions/{sessionId}/attendance/{uid}
calibrations/{calibrationId}
```

Checkpoint documents should store the final result and reason codes; raw readings should be optional/configurable to reduce storage and protect privacy.

### 9.3 Security rules

- A student can read their assigned classroom configuration and write only their own session checkpoint.
- A student cannot write their own final attendance decision.
- A teacher can read sessions for classrooms they own or teach.
- Cloud Functions use Admin privileges only after validating classroom membership and payload format.
- Never store biometric data, BLE MAC addresses as identity, or unrestricted raw location history.

## 10. Build order (do not skip the gates)

### Phase 0 — Project controls

- Create the folder layout and shared contracts.
- Add `AGENTS.md` describing the non-interference rules and required test gate for future AI-assisted work.
- Create a decision log in `docs/`.

**Gate:** no module may invent its own beacon ID, reading schema, or result schema.

### Phase 1 — One hardware beacon

- Create the Arduino sketch and ESP32-WROOM-32 board configuration.
- Flash one ESP and verify SSID + BLE advertising using an Android diagnostic tool.

**Gate:** stable advertising for 90 minutes.

### Phase 2 — Four-beacon hardware installation

- Configure and label all four ESPs.
- Place them at classroom corners and record channel/power settings.
- Verify that all four appear simultaneously at center and near the door.

**Gate:** phone can distinguish all four beacon IDs.

### Phase 3 — Presence core and simulation

- Implement pure calculation classes, config loading, reasons, temporal aggregation, and tests.
- Implement all six simulation fixtures.

**Gate:** unit tests pass without Android hardware or Firebase.

### Phase 4 — Android scanner adapters

- Implement BLE scanner, Wi-Fi scanner, permission flow, foreground session service, and local Room queue.
- Connect scanner output to the existing core only through interfaces.

**Gate:** show actual raw and filtered readings from all four ESPs on one target Android phone.

### Phase 5 — Calibration and tune configuration

- Gather inside/outside readings.
- Inspect distributions and save versioned thresholds.
- Repeat at different times with people and doors in normal use.

**Gate:** document expected behavior and known ambiguous regions.

### Phase 6 — Firebase backend

- Configure Firebase project, Auth, Firestore schema/rules, and offline sync repository.
- Add optional Cloud Function for final attendance summary.

**Gate:** local detection works with airplane mode; sync completes when connectivity returns.

### Phase 7 — Your frontend integration

- Connect your Student and Teacher views through app-facing use cases only.
- Student: sign-in, biometric unlock, class-session monitoring, live explanation.
- Teacher: start/end class, inspect checkpoint history, review `UNCERTAIN`/insufficient-data cases.

**Gate:** UI changes do not alter `presence-core` tests.

### Phase 8 — End-to-end demonstration

- Run a full 60-minute session (or a documented accelerated test mode).
- Validate the 60% attendance rule, offline queue, sync, and teacher result.
- Produce README, flashing guide, Firebase setup guide, calibration guide, and limitations.

## 11. Definition of done for the prototype

The prototype is complete only when it can:

- flash four independent ESP32-WROOM-32 beacons from Arduino IDE;
- discover their unique Wi-Fi and BLE identities on Android;
- collect and filter real RSSI measurements;
- use the same deterministic core for live and simulation modes;
- generate an explained `INSIDE`, `OUTSIDE`, `UNCERTAIN`, or `INSUFFICIENT_DATA` result;
- aggregate 12 five-minute checkpoints for a 60-minute class and apply the 60% rule;
- work locally during an internet outage and sync later to Firebase;
- let a teacher review results without accessing raw biometric information;
- document accuracy limits, including ambiguity near walls/doors and RSSI variation by phone model.

## 12. Immediate work checklist

Start in this exact order:

1. Confirm four ESP32-WROOM-32 boards, stable USB power supplies, and one Android test phone.
2. Install Arduino IDE ESP32 support and prove one SoftAP + BLE beacon.
3. Create and freeze `shared/beacon-contract.json`.
4. Flash/lab-test all four boards.
5. Implement and test `presence-core` using simulation data before building any UI.
6. Add the Android scanner adapters and verify real readings.
7. Calibrate the actual classroom before choosing final thresholds.
8. Add Firebase only after local scanning and local decisions are proven.

## 13. Decisions to edit before implementation begins

Edit these project choices if needed:

- Classroom identifier naming format.
- Whether the teacher dashboard is inside the Android app or a later web client.
- Firebase sign-in provider (email/password, Google, or institution SSO).
- Whether raw RSSI samples are stored in Firebase or retained only locally during calibration.
- The policy for `UNCERTAIN` checkpoints (recommended: do not count as inside automatically).
- Exact Android phones and Android versions used for demonstration.
- Final physical placement, power source, Wi-Fi channel, and transmit power for each ESP.

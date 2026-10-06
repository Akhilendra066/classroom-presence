# Classroom Presence — Android 13+

Native Kotlin Android prototype with student and teacher workspaces, backed by real Supabase Auth and PostgreSQL on the Free plan. The existing ESP32 firmware is unchanged. The app targets Android 16 (API 36), supports Android 13+ (minimum API 33), and recognizes the current `CP1|ROOM_A101|1` manufacturer payload and `CP_ROOM_A101_ESP_1` Wi-Fi identity.

## Install and sign in

Build `mobile/app/build/outputs/apk/debug/app-debug.apk` and install it on your
Android phone. This is a debug-signed prototype APK, not a Play Store release.
Generated APK files stay local and are not committed to the source repository.
Allow installation from the app through which you open the APK when Android
prompts you.

The live project is [Classroom Presence](https://supabase.com/dashboard/project/wrupuujtrtiegqjbakfz), hosted in Mumbai under **Classroom Presence Free**. Teacher: `hh5379259@gmail.com`. Student: `akhilendrasingh066@gmail.com`. The administrator provisions roles; login selection cannot change them.

1. Open the private `.tools/supabase-private/account-password-links.md` file in this workspace. Keep it private; do not commit or share the complete file publicly.
2. Copy your own link without opening it in a browser. In the app, tap **Set password with administrator link**, paste it and choose your own password (12+ characters).
3. Select your role and sign in with the corresponding email and password.
4. The teacher collects real inside/outside readings and publishes room calibration before starting a production session. The student is already enrolled in Classroom A101.
5. Use separate phones for the two accounts; grant radio/location/notification permissions before starting monitoring.

See [docs/supabase-setup.md](docs/supabase-setup.md) for private link regeneration, deployment, free limits and first-session checks. Email password reset requires an authorized recipient or your own SMTP service; administrator setup links work without sending mail.

## Classroom workflow

1. Sign in using your institution account and administrator-assigned role.
2. Teachers open Classes → Add students to manage enrollment. Calibrate each room
   once; its saved calibration is reused automatically. Choose Set up class to
   select duration (15–120 minutes, in steps of 5) and minimum sampled presence
   before starting immediately. Sampling scales with duration: at least five
   checkpoints, with 20 in a 60-minute class. Active sessions appear on Home; completed sessions appear in
   History for review and CSV export.
3. Students open an active enrolled session and start real beacon monitoring.
   Enable Wi-Fi, Bluetooth and Location, and grant the requested permissions.
4. Saved checkpoints upload to the backend. Local progress remains provisional;
   the backend determines final attendance. A late start leaves earlier
   checkpoints missing rather than backfilling them.

Only institution accounts and real beacon collection are available in the app.

## Implemented functionality

- Two login entry points; trusted Supabase database roles determine production access.
- Student enrolled classes, active sessions, foreground monitoring, per-beacon RSSI explanations, saved checkpoints, attendance history and sync/review status.
- Teacher class sessions, rosters/enrollment management, received attendance, checkpoint review, audited cloud overrides and CSV export.
- BLE/Wi-Fi scanning with permission/readiness checks, timeouts and stale-data rejection.
- Pure Kotlin classifier and attendance aggregation covered by isolated core tests.
- Room persistence, account-partitioned upload queue and WorkManager retries.
- Teacher-labelled calibration recordings, statistics, CSV export and versioned threshold publication.
- PostgreSQL API validating role, enrollment, session timing, payload, configuration and idempotency; server recomputes results and owns attendance.
- Scheduled attendance finalization after a 15-minute upload grace period.
- Keystore-encrypted Supabase sessions, refresh tokens, private administrator password setup and foreground-only polling of small session-state records.

Production rooms start uncalibrated and cannot start a cloud session until an authorized teacher publishes a reviewed configuration.

## Build

Open `mobile/` in Android Studio with JDK 17, SDK platform 36 and build tools 35.0.0. Let Gradle sync, then run the app on an Android 13+ device. The pinned toolchain is AGP 8.11.1, Gradle 8.13 and Kotlin 2.1.20.

```sh
cd mobile
./gradlew :presence-core:test :app:assembleDebug :app:lintDebug
```

On the current workspace, downloaded toolchains are available under ignored `.tools/`:

```sh
./scripts/build-android.sh :presence-core:test :app:assembleDebug :app:lintDebug
```

APK output: `mobile/app/build/outputs/apk/debug/app-debug.apk`. Room stores evidence locally; uninstalling or clearing app storage deletes it. Keep upload/review queues resolved before clearing data.

## Supabase Free

The app uses Supabase Auth and the authenticated `presence_api` database function. All attendance tables are in a private schema with no mobile grants. Cron finalizes sessions every five minutes. No Edge Functions, paid plan, card or paid add-on is required for this implementation.

Local public configuration is in ignored `mobile/supabase.properties`; copy `mobile/supabase.properties.example` when configuring another checkout. Never put a service-role/secret key in Android. Building without that file disables sign-in and explains that an institution connection must be configured.

The former Firebase setup is archived for reference in `firebase/` and `docs/firebase-setup.md`; the Android app no longer uses its SDKs or configuration. The previously created Firebase project remains on Spark with billing disabled.

## Tests

```sh
./scripts/build-android.sh :presence-core:test
sh scripts/test-supabase.sh
./scripts/test-android.sh
```

Supabase backend tests start disposable local PostgreSQL and exercise real database roles, permissions, policy fixtures and attendance operations. The live cloud test (`supabase/scripts/test-live.mjs`) creates disposable Supabase accounts/classes, tests real Auth/API plus the Android 16 client, and removes its cloud test data. It requires the local CLI/toolchain and authorized admin login.

Android UI tests check institution-only login, rejection of legacy local accounts and persistence of institution accounts. Android tests require a running `emulator-5554`; the test scripts explicitly target that emulator and clear app data only there. Authenticated cloud tests require disposable live credentials supplied by the trusted cloud test script.

See [docs/verification.md](docs/verification.md) for recorded verification, [docs/calibration-guide.md](docs/calibration-guide.md) for field accuracy evaluation and [docs/motorola-test-guide.md](docs/motorola-test-guide.md) for your Edge 60 Fusion checklist.

## Architecture

`mobile/presence-core` is pure Kotlin. `scanner-android` adapts Android radio APIs. `data` owns local/Supabase repositories and upload retries. `app` owns Compose UI and the user-started monitoring service. `supabase/migrations` owns trusted operations and final attendance. `shared/` documents the actual beacon protocol and cross-language fixtures.

Presence is evidence about a phone. RSSI varies with phone model, orientation, walls and people. Current beacons are not cryptographically authenticated; the prototype does not establish that a student personally carried their logged-in phone. Real hardware acquisition, battery behavior and accuracy must be measured on the target phones before institutional use.

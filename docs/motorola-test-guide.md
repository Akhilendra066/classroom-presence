# Motorola Edge 60 Fusion / Android 16 test

The APK targets Android 16 and supports Android 13+. Emulator verification does not establish Motorola radio behavior or field accuracy.

1. Install the supplied debug APK. Enable automatic date/time on the phone.
2. Power all four ESPs. Confirm SSIDs `CP_ROOM_A101_ESP_1` through `_4` and manufacturer payloads `CP1|ROOM_A101|1` through `|4`. The phone scans; do not connect to the ESP access points.
3. Grant **precise** Location and Nearby devices. Allow notification display. Enable Wi-Fi, Bluetooth and system Location.
4. In teacher demo, collect a calibration sample or in a session run **real radio diagnostic**. Confirm detected IDs and changing negative RSSI values on both technologies. A weak but fresh reading is valid data; stale/missing readings are not outside.
5. Run a 60-minute real-beacon student session with the screen locked and normal battery settings. Inspect all 12 slots and record missing/failed scans. If Motorola stops scanning, inspect the app battery settings and document the setting required; the app never silently grants itself an exemption.
6. Repeat normal classroom positions and outside/adjacent-room positions. Compare classification with labelled ground truth. Test a second phone/model and Android 13, 14 or 15 before claiming those versions verified.
7. Disable internet while keeping Wi-Fi/BLE scanning available. Cloud monitoring needs a previously downloaded session/configuration. Restore internet within the upload grace period; verify each slot uploads once and the teacher summary agrees with saved evidence.
8. Revoke precise location, turn off a radio and trigger battery saver during separate runs. Expect insufficient data and explanations, not a false attendance success.
9. Stop/restart monitoring, join late, rotate the phone and terminate the process in separate runs. Completed slots persist; missed slots must not be invented. Process death requires a visible restart. Force-stop/reboot prevents reliable autonomous resumption.
10. Test teacher early end, denied student teacher-login, cross-student record isolation and teacher overrides after session end.

Use local demo for one-phone radio testing. For a teacher and student on separate phones, use the deployed Supabase project and accounts described in `supabase-setup.md`; the initial student is already enrolled. Set each account's password with its private administrator link before login. The emulator cannot validate the four physical ESP signals. Keep a field-test report with phone model, OS, permissions, battery settings, room layout, config version, errors and observed accuracy.

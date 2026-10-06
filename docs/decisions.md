# Implementation decisions

- Minimum Android API 33 and target API 36, following the requested Android 13+ support and Motorola Android 16 test target.
- One application, two role entry points. Trusted Supabase database profiles grant roles; login selection and Auth user metadata do not grant permissions.
- Use the existing firmware's manufacturer payload and registered Wi-Fi identities. Do not change working firmware or require a BLE UUID filter that has not been observed on a phone.
- Both radio technologies are required for a valid checkpoint. Experimental single-radio fallback is disabled.
- Classification uses explicit quality/strength gates. The original additive score's weak-signal agreement defect is removed. Score now reports the count of strong signals as an explanation; it does not override the classification.
- Median filtering operates on fresh observations within the burst. Regular uploads compact to per-beacon medians; raw calibration recordings remain local.
- Attendance preserves the original 8-valid / 60%-of-valid policy. Late joins and early ends do not shrink the 12-slot requirement. Hysteresis affects display only.
- Use user-started location foreground monitoring with an ongoing notification, Stop action and bounded partial wake lock. Process death requires visible restart; historical gaps remain gaps.
- Local demo is explicit and never uploads. The accelerated six-minute demo has 12 slots at 30-second intervals.
- A trusted PostgreSQL API owns sessions, enrollment operations, accepted checkpoints, final attendance and audit records. Underlying tables/functions are private, have RLS enabled and have no client grants.
- Production configurations require teacher calibration review. The default room is uncalibrated; cloud session creation is blocked until publication.
- Cloud upload grace is 15 minutes after actual session end. Late/invalid/conflicting evidence remains local as review-required instead of disappearing.
- Historical session roster/config snapshots remain immutable. Teacher roster changes affect future sessions.
- Supabase Free was selected after the user declined Firebase Blaze and all payments. The real Mumbai project uses Auth, database functions and Cron; no Edge Functions, paid add-ons or subscription activation are needed.
- Local Supabase auth sessions are Keystore-encrypted. Administrator-generated recovery links let initial users choose their own passwords without depending on SMTP delivery.
- Foreground-only polling retrieves small session states. Immutable configuration is downloaded when needed to conserve the Free plan's egress quota.

# Android application artifacts

APK files in this directory are local build outputs and are intentionally ignored
by Git. Rebuild the current APK from the checked-in Android source.

## Current Supabase application

`classroom-presence-debug.apk` and `classroom-presence-supabase-debug.apk` are identical version 0.2.0 debug-signed APKs for Android 13+ (minimum API 33), targeting Android 16 (API 36). They contain the public configuration for the real Free Supabase project `wrupuujtrtiegqjbakfz`. No administrator key is bundled.

Assembly, lint, APK signature verification and 16 KB ZIP alignment passed. The Android 16 live integration test verified private password setup, role-specific login, encrypted session persistence and backend access against the deployed project. Disposable hosted attendance tests also passed.

SHA-256 for both current APKs:

```text
92687460b13602acda828fc0d4d00817aa42e40547be2c724b7828286bd4edc7
```

Set your own password using your private link from `.tools/supabase-private/account-password-links.md` through **Set password with administrator link** in the app. Then sign in as teacher or student. See `../docs/supabase-setup.md` for the first production session. Real room calibration and physical-phone verification are still required.

## Historical artifacts

`classroom-presence-local-demo-debug.apk` preserves the earlier version 0.1.0 local demo artifact (SHA-256 `9fcff7a59c59fa2b6d6e45ee220b03c6468b77ca9245725ae3d3368cd2e968a7`). It has no cloud configuration.

`classroom-presence-firebase-debug.apk` is the former Firebase-configured build (SHA-256 `abaa2ef59fbaec2c5ad07dfbb1f4974da9ddf77b61656704e21dd4ff3e648ea1`). Its Firebase backend was not deployed. The current application uses Supabase.

These are prototype artifacts. Public distribution requires release signing; emulator tests do not establish Motorola radio/battery behavior or field accuracy.

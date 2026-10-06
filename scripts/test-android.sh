#!/bin/sh
set -eu
export ANDROID_SERIAL=emulator-5554
PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ADB="$PROJECT_ROOT/.tools/android-sdk/platform-tools/adb"
"$ADB" -s emulator-5554 wait-for-device
"$ADB" -s emulator-5554 shell pm clear com.classroompresence.app >/dev/null 2>&1 || true
"$PROJECT_ROOT/scripts/build-android.sh" :app:connectedDebugAndroidTest --no-daemon

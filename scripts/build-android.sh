#!/bin/sh
set -eu
PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ -d "$PROJECT_ROOT/.tools/jdk-17.0.20.1+1/Contents/Home" ]; then export JAVA_HOME="$PROJECT_ROOT/.tools/jdk-17.0.20.1+1/Contents/Home"; fi
export GRADLE_USER_HOME="$PROJECT_ROOT/.tools/gradle-home"
export ANDROID_USER_HOME="$PROJECT_ROOT/.tools/android-user"
if [ -d "$PROJECT_ROOT/.tools/android-sdk" ]; then export ANDROID_HOME="$PROJECT_ROOT/.tools/android-sdk"; fi
cd "$PROJECT_ROOT/mobile"
if [ -f gradle/wrapper/gradle-wrapper.jar ]; then exec ./gradlew "$@"; fi
exec "$PROJECT_ROOT/.tools/gradle-8.13/bin/gradle" "$@"

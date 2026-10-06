#!/bin/sh
set -eu
PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ -d "$PROJECT_ROOT/.tools/node-v22.15.0-darwin-arm64/bin" ]; then export PATH="$PROJECT_ROOT/.tools/node-v22.15.0-darwin-arm64/bin:$PATH"; fi
if [ -d "$PROJECT_ROOT/.tools/jdk-17.0.20.1+1/Contents/Home" ]; then export JAVA_HOME="$PROJECT_ROOT/.tools/jdk-17.0.20.1+1/Contents/Home"; export PATH="$JAVA_HOME/bin:$PATH"; fi
export FIREBASE_CLI_DISABLE_USAGE_TRACKING=1
mkdir -p "$PROJECT_ROOT/.tools"
cd "$PROJECT_ROOT/firebase"
npm --prefix functions run build
if ./functions/node_modules/.bin/firebase emulators:exec --project demo-classroom-presence --only auth,firestore,functions 'TEST_FUNCTIONS_EMULATOR=1 npm --prefix functions test' > "$PROJECT_ROOT/.tools/backend-emulator-test.log" 2>&1; then
    tail -n 24 "$PROJECT_ROOT/.tools/backend-emulator-test.log"
else
    tail -n 100 "$PROJECT_ROOT/.tools/backend-emulator-test.log"
    exit 1
fi

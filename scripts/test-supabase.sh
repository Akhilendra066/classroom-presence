#!/bin/sh
set -eu
PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
exec "$PROJECT_ROOT/.tools/node-v22.15.0-darwin-arm64/bin/node" "$PROJECT_ROOT/supabase/test/database.mjs"

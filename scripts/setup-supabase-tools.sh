#!/bin/sh
set -eu
PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ -d "$PROJECT_ROOT/.tools/node-v22.15.0-darwin-arm64/bin" ]; then export PATH="$PROJECT_ROOT/.tools/node-v22.15.0-darwin-arm64/bin:$PATH"; fi
npm install --prefix "$PROJECT_ROOT/.tools/supabase-cli" --cache "$PROJECT_ROOT/.tools/npm-cache" supabase@2.119.0 embedded-postgres@18.4.0-beta.17 pg@8.23.1

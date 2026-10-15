#!/usr/bin/env bash
# One command: fetch everything, build the pty bridge, build the APK.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
"$REPO/tools/fetch-termux.sh"
"$REPO/tools/fetch-opencode.sh"
"$REPO/tools/build-pty.sh"
"$REPO/tools/fetch-sdk.sh"
"$REPO/tools/fetch-webui.sh"
"$REPO/tools/build.sh"

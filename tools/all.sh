#!/usr/bin/env bash
# One command: fetch everything, build the pty bridge and the native shim,
# build the APK.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
"$REPO/tools/check-host.sh"
"$REPO/tools/fetch-termux.sh"
"$REPO/tools/fetch-opencode.sh"
"$REPO/tools/build-pty.sh"
"$REPO/tools/build-native.sh"
"$REPO/tools/fetch-sdk.sh"
"$REPO/tools/fetch-webui.sh"
"$REPO/tools/build.sh"

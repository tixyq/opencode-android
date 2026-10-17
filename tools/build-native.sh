#!/usr/bin/env bash
# Build the native compatibility shim shipped in the APK, plus the
# test-only seccomp stand used by tools/test-shim.sh.
#
#   build/liboc_compat.so   -> assets/tools/lib/liboc_compat.so (server)
#   build/seccomp-block      -> test stand only, never shipped
#
# The shim interposes close_range(2): Android's seccomp policy traps that
# call for this app and the resulting SIGSYS kills the server at startup.
# See app/native/oc_compat.c for the full rationale.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$REPO/build"
mkdir -p "$OUT"

CC="${CC:-clang}"
CFLAGS="-O2 -fPIC -Wall -Wextra"

# The shim runs inside the app: it must be built for the Android runtime
# (bionic, API 28+), natively on an aarch64-Android host, cross otherwise.
HOST_TRIPLE="$(${CC} -dumpmachine 2>/dev/null || echo unknown)"
case "$HOST_TRIPLE" in
    *aarch64*android*|*android*aarch64*)
        # clang may report aarch64-linux-android or aarch64-unknown-linux-androidNN
        echo "native aarch64-Android build ($HOST_TRIPLE)"
        TARGET_ARGS=()
        ;;
    *)
    echo "host is $HOST_TRIPLE, cross-compiling for aarch64-linux-android"
    : "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME (https://developer.android.com/ndk/downloads)}"
    NDK_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
    [ -x "$NDK_BIN/aarch64-linux-android28-clang" ] \
        || { echo "NDK linker not found in $NDK_BIN" >&2; exit 1; }
    TARGET_ARGS=(--target aarch64-linux-android28
                 --sysroot "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot")
        ;;
esac

echo "== shim =="
# shellcheck disable=SC2086
$CC "${TARGET_ARGS[@]}" $CFLAGS -shared \
    -o "$OUT/liboc_compat.so" "$REPO/app/native/oc_compat.c" -ldl
# The whole fix is these two symbols; refuse to ship a shim without them.
readelf -sW "$OUT/liboc_compat.so" | awk '/GLOBAL/ && /close_range/ {print "   ", $NF}'
if ! readelf -sW "$OUT/liboc_compat.so" | grep -q ' close_range$'; then
    echo "shim does not export close_range, aborting" >&2
    exit 1
fi

echo "== test stand (not shipped) =="
# shellcheck disable=SC2086
$CC $CFLAGS -o "$OUT/seccomp-block" "$REPO/tools/seccomp-block.c"

ls -la "$OUT/liboc_compat.so" "$OUT/seccomp-block"
echo OK
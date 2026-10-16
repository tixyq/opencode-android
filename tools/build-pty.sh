#!/usr/bin/env bash
# Build the PTY bridge cdylib from pinned sources.
# Native aarch64-Android hosts (incl. Termux) build directly.
# Any other Linux cross-compiles via the NDK (ANDROID_NDK_HOME).
# Output: $REPO/build/librust_pty_arm64.so
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
. "$REPO/third-party/pins.env"
OUT="$REPO/build"
mkdir -p "$OUT"

if [ ! -e "$REPO/rust/bun-pty/.git" ]; then
    echo "missing submodule rust/bun-pty; run: git submodule update --init" >&2
    exit 1
fi
(
    cd "$REPO/rust/bun-pty"
    git checkout -q "$BUN_PTY_COMMIT"
    git checkout -q -- rust-pty/Cargo.toml rust-pty/Cargo.lock
    git apply ../bun-pty.patch
)
cp "$REPO/rust/Cargo.lock" "$REPO/rust/bun-pty/rust-pty/Cargo.lock"
HOST_TRIPLE="$(rustc -vV | sed -n 's/^host: //p')"
TARGET_ARGS=""
LIB_OUT="target/release/librust_pty.so"
if [ "$HOST_TRIPLE" = "aarch64-linux-android" ]; then
    echo "native aarch64-Android build"
else
    echo "host is $HOST_TRIPLE, cross-compiling for aarch64-linux-android"
    : "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME (https://developer.android.com/ndk/downloads)}"
    NDK_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
    [ -x "$NDK_BIN/aarch64-linux-android28-clang" ] \
        || { echo "NDK linker not found in $NDK_BIN" >&2; exit 1; }
    rustup target add aarch64-linux-android
    export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$NDK_BIN/aarch64-linux-android28-clang"
    TARGET_ARGS="--target aarch64-linux-android"
    LIB_OUT="target/aarch64-linux-android/release/librust_pty.so"
fi
(
    cd "$REPO/rust/bun-pty/rust-pty"
    # shellcheck disable=SC2086
    cargo build --release --locked $TARGET_ARGS
)
cp "$REPO/rust/bun-pty/rust-pty/$LIB_OUT" "$OUT/librust_pty_arm64.so"
chmod 755 "$OUT/librust_pty_arm64.so"
# leave the submodule pristine; recipe state lives in rust/
(
    cd "$REPO/rust/bun-pty"
    git checkout -q -- rust-pty/Cargo.toml rust-pty/Cargo.lock
)
readelf -h "$OUT/librust_pty_arm64.so" 2>/dev/null | grep -i "machine" || true
ls -la "$OUT/librust_pty_arm64.so"
echo OK

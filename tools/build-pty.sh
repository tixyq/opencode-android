#!/usr/bin/env bash
# Build the PTY bridge cdylib from pinned sources (offline-capable).
# Output: $REPO/build/librust_pty_arm64.so
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
. "$REPO/third-party/pins.env"
OUT="$REPO/build"
mkdir -p "$OUT"

if [ ! -d "$REPO/rust/bun-pty/.git" ]; then
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
(
    cd "$REPO/rust/bun-pty/rust-pty"
    cargo build --release --offline --locked
)
cp "$REPO/rust/bun-pty/rust-pty/target/release/librust_pty.so" "$OUT/librust_pty_arm64.so"
chmod 755 "$OUT/librust_pty_arm64.so"
# leave the submodule pristine; recipe state lives in rust/
(
    cd "$REPO/rust/bun-pty"
    git checkout -q -- rust-pty/Cargo.toml rust-pty/Cargo.lock
)
ls -la "$OUT/librust_pty_arm64.so"
echo OK

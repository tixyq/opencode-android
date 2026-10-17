#!/usr/bin/env bash
# End-to-end check of the close_range shim.
#
# Runs the real server binary under a seccomp filter that traps
# close_range (what Android's app policy does), waits for it to serve,
# probes HTTP, then tears the whole thing down by recorded PID.
# Never touches anything it did not start itself.
#
# Usage: tools/test-shim.sh <server-binary> [port]
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
SRV="${1:-$ROOT/build/opencode-2.0.27}"
PORT="${2:-18099}"
LOG="$(dirname "$SRV")/../shim-serve.log"
SHIM="$ROOT/build/liboc_compat.so"
BLOCK="$ROOT/build/seccomp-block"

for f in "$SRV" "$SHIM" "$BLOCK"; do
    [ -x "$f" ] || { echo "missing: $f (run tools/build-native.sh)"; exit 1; }
done
# The test stand must never use a port the app itself serves on.
case "$PORT" in
    18080|49474) echo "refusing to use an app port ($PORT)"; exit 1;;
esac

cleanup() {
    [ -n "${CHILD:-}" ] && kill -TERM -"$CHILD" 2>/dev/null
    sleep 1
    [ -n "${CHILD:-}" ] && kill -KILL -"$CHILD" 2>/dev/null
    return 0
}
# Hard ceiling: this test cannot outlive its own patience.
( sleep 90; cleanup; echo "TIMEOUT: test exceeded 90s" >> "$LOG" ) &
WATCH=$!
trap 'cleanup; kill "$WATCH" 2>/dev/null' EXIT

run() { # label, extra env...
    local label="$1"; shift
    cleanup; CHILD=""
    : > "$LOG"
    setsid env "$@" "$BLOCK" "$SRV" serve --service --port "$PORT" \
        > "$LOG" 2>&1 &
    CHILD=$!
    local i code=""
    for i in $(seq 1 40); do
        sleep 2
        code="$(curl -s -o /dev/null -w '%{http_code}' -m 5 \
               "http://127.0.0.1:$PORT/" 2>/dev/null)"
        [ "$code" = "200" ] && break
        kill -0 "$CHILD" 2>/dev/null || break
    done
    if [ "$code" = "200" ]; then
        echo "PASS  $label (served / with $code)"
        return 0
    fi
    if ! kill -0 "$CHILD" 2>/dev/null; then
        local sig=""
        wait "$CHILD" 2>/dev/null
        sig="$?"
        echo "FAIL  $label (server exited, status $sig)"
        sed -n '1,4p' "$LOG" | sed 's/^/        /'
        return 1
    fi
    echo "FAIL  $label (no HTTP within 80s, last code '$code')"
    sed -n '1,4p' "$LOG" | sed 's/^/        /'
    return 1
}

RC=0
case_only() {
    local label="$1"; shift
    # "$@" is the env assignment list; empty means "no shim"
    if [ "$#" -eq 0 ]; then
        run "$label" HOME="$HOME" TMPDIR="${TMPDIR:-/tmp}"
    else
        run "$label" HOME="$HOME" TMPDIR="${TMPDIR:-/tmp}" "$@"
    fi
}

# Each case can also be run alone: ./tools/test-shim.sh <server> <port> <case>
WANT="${3:-all}"
if [ "$WANT" = "all" ] || [ "$WANT" = "control" ]; then
    # Control: no shim. Must die exactly like the device crash did.
    case_only "blocked, no shim (must fail with SIGSYS)" && \
        { echo "UNEXPECTED: control passed, the stand proves nothing"; RC=1; }
fi
if [ "$WANT" = "all" ] || [ "$WANT" = "shim" ]; then
    case_only "blocked + shim (must serve)" LD_PRELOAD="$SHIM" || RC=1
fi
if [ "$WANT" = "all" ] || [ "$WANT" = "plain" ]; then
    # Shim must stay inert when the policy allows the call.
    case_only "unblocked + shim (must serve)" LD_PRELOAD="$SHIM" || RC=1
fi

cleanup
[ "$RC" -eq 0 ] && echo "SHIM OK"
exit "$RC"
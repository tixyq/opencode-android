#!/usr/bin/env bash
# Fetch the pinned OpenCode server binary (Hope2333 Termux port).
# Output: $REPO/build/opencode-<ver> (executable, --version verified).
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
. "$REPO/third-party/pins.env"
DL="$REPO/download"; OUT="$REPO/build"
mkdir -p "$DL" "$OUT"

url="$OPENCODE_RELEASE_BASE/$OPENCODE_TAG/$OPENCODE_ASSET"
dest="$DL/$OPENCODE_ASSET"
if [ -f "$dest" ]; then
    echo "cached: $OPENCODE_ASSET"
else
    echo "fetch: $url"
    curl -fsSL -C - --max-time 900 --speed-limit 1000 --speed-time 60 -o "$dest.tmp" "$url"
    mv "$dest.tmp" "$dest"
fi
echo "$OPENCODE_DEB_SHA256  $dest" | sha256sum -c -
rm -rf "$DL/oc-ex" && mkdir -p "$DL/oc-ex"
dpkg-deb -x "$dest" "$DL/oc-ex"
BIN="$DL/oc-ex/data/data/com.termux/files/usr/bin/opencode"
V=$("$BIN" --version 2>/dev/null | head -n 1)
WANT="$(basename "$OPENCODE_ASSET" .deb)"; WANT="${WANT#opencode_}"; WANT="${WANT%_aarch64}"
echo "staged binary reports: $V (want $WANT)"
case "$V" in
    *"$WANT"*) ;;
    *) echo "unexpected server version ($V), aborting" >&2; exit 1;;
esac
cp "$BIN" "$OUT/opencode-2.0.27"
chmod 755 "$OUT/opencode-2.0.27"
rm -rf "$DL/oc-ex"
echo OK

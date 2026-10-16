#!/usr/bin/env bash
# Fetch + verify the web UI snapshot, apply our patches and overlays.
# Output: $REPO/build/www/{...patched tree...} and build/www.zip
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
BASE="$REPO/build/www"
UA='Mozilla/5.0 (Linux; Android 16; CPH2727) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36'
rm -rf "$BASE"
mkdir -p "$BASE"

echo "== fetch upstream base (3 crawl phases) =="
curl -sL -m 60 -A "$UA" -e 'https://beta.opencode.ai/' \
    'https://beta.opencode.ai/' -o "$BASE/index.html"
[ -s "$BASE/index.html" ] || { echo "index.html empty, aborting" >&2; exit 1; }
python3 "$REPO/webui/tools/mirror.py" "$BASE"
python3 "$REPO/webui/tools/mirror2.py" "$BASE"
python3 "$REPO/webui/tools/crawl3.py" "$BASE"

echo "== verify snapshot hashes =="
cd "$BASE"
while read -r want rel; do
    [ -n "$want" ] || continue
    got="$(sha256sum "$rel" | awk '{print $1}')"
    if [ "$got" != "$want" ]; then
        echo "HASH DRIFT (upstream redeployed?): $rel" >&2
        echo "got=$got want=$want" >&2
        exit 1
    fi
done < "$REPO/webui/MANIFEST.sha256"
echo "snapshot verified"

echo "== verify file set (no silent additions/removals) =="
cd "$BASE"
find . -type f | sed 's|^\./||' | sort > "$REPO/build/webui-have.txt"
awk '{print $2}' "$REPO/webui/MANIFEST.sha256" | sort > "$REPO/build/webui-want.txt"
if ! diff -u "$REPO/build/webui-want.txt" "$REPO/build/webui-have.txt"; then
    echo "file set drift (see diff above)" >&2
    exit 1
fi

echo "== apply deterministic patches =="
python3 "$REPO/webui/tools/patch-webui.py" "$BASE"

echo "== apply overlays =="
cp "$REPO/webui/overlays/mobile.css" "$REPO/webui/overlays/mobile.js" \
   "$REPO/webui/overlays/enter-newline.js" "$REPO/webui/overlays/loader.html" "$BASE/"
mkdir -p "$BASE/oc-mobile"
cp "$REPO/webui/overlays/oc-mobile/changelog-empty-01.json" "$BASE/oc-mobile/"

echo "== pack www.zip =="
cd "$BASE" && zip -qr "$REPO/build/www.zip" . -x "*.py" "*.log" "*.out" "*.b64" "diag.js"
cp "$BASE/loader.html" "$REPO/build/loader.html"
ls -la "$REPO/build/www.zip"
echo OK

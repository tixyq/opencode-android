#!/usr/bin/env bash
# Fetch official Termux userland + helper packages (all hash-verified).
# Output: $REPO/build/bootstrap-clean.zip, build/stage-tools/{bin,lib,libexec}/
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
. "$REPO/third-party/pins.env"
DL="$REPO/download"; OUT="$REPO/build"
mkdir -p "$DL" "$OUT"

fetch() { # url sha256 dest
    local url="$1" want="$2" dest="$3" try=0
    while [ $try -lt 4 ]; do
        try=$((try + 1))
        if [ -f "$dest" ] && [ "$(sha256sum "$dest" | awk '{print $1}')" = "$want" ]; then
            echo "cached: $(basename "$dest")"
            return 0
        fi
        echo "fetch ($try/4): $url"
        curl -fsSL -C - --max-time 900 --speed-limit 1000 --speed-time 120 \
            -o "$dest.tmp" "$url" || true || true
        local got=""
        [ -f "$dest.tmp" ] && got="$(sha256sum "$dest.tmp" | awk '{print $1}')"
        if [ "$got" = "$want" ]; then
            mv "$dest.tmp" "$dest"
            return 0
        fi
        echo "incomplete ($got), resuming..."
        sleep 5
    done
    echo "FAILED to fetch $url" >&2
    return 1
}

echo "== termux base apk (bootstrap source) =="
fetch "$TERMUX_APK_URL" "$TERMUX_APK_SHA256" "$DL/com.termux_1022.apk"
echo "== extracting bootstrap =="
python3 - "$DL/com.termux_1022.apk" "$OUT/bootstrap-raw.zip" "$TERMUX_BOOTSTRAP_INNER_SHA256" <<'EOF'
import sys, zipfile, hashlib
apk, out, want = sys.argv[1], sys.argv[2], sys.argv[3]
data = zipfile.ZipFile(apk).read('lib/arm64-v8a/libtermux-bootstrap.so')
assert hashlib.sha256(data).hexdigest() == want, 'inner bootstrap hash drift'
open(out, 'wb').write(data)
print('bootstrap extracted + verified')
EOF
echo "== repacking bootstrap (strip zip extras Android chokes on) =="
python3 "$REPO/tools/repack-bootstrap.py" "$OUT/bootstrap-raw.zip" "$OUT/bootstrap-clean.zip"

echo "== termux system debs =="
for spec in \
    "$TERMUX_DEB_PROOT $TERMUX_DEB_PROOT_SHA256" \
    "$TERMUX_DEB_TALLOC $TERMUX_DEB_TALLOC_SHA256" \
    "$TERMUX_DEB_SHMEM $TERMUX_DEB_SHMEM_SHA256"; do
    set -- $spec
    fetch "$TERMUX_POOL/$1" "$2" "$DL/$(basename "$1")"
done
echo "== extracting staged guest libs =="
rm -rf "$OUT/stage-tools" && mkdir -p "$OUT/stage-tools/bin" "$OUT/stage-tools/lib" "$OUT/stage-tools/libexec/proot"
cd "$DL"
dpkg-deb -x "$(basename "$TERMUX_DEB_PROOT")" proot-ex/
dpkg-deb -x "$(basename "$TERMUX_DEB_TALLOC")" talloc-ex/
dpkg-deb -x "$(basename "$TERMUX_DEB_SHMEM")" shmem-ex/
cp proot-ex/data/data/com.termux/files/usr/bin/proot "$OUT/stage-tools/bin/proot"
cp proot-ex/data/data/com.termux/files/usr/libexec/proot/loader "$OUT/stage-tools/libexec/proot/"
cp proot-ex/data/data/com.termux/files/usr/libexec/proot/loader32 "$OUT/stage-tools/libexec/proot/"
cp talloc-ex/data/data/com.termux/files/usr/lib/libtalloc.so.2.5.0 "$OUT/stage-tools/lib/"
ln -sf libtalloc.so.2.5.0 "$OUT/stage-tools/lib/libtalloc.so.2"
cp shmem-ex/data/data/com.termux/files/usr/lib/libandroid-shmem.so "$OUT/stage-tools/lib/"
cp "$REPO/third-party/libopentui.so" "$OUT/stage-tools/lib/libopentui.so"
(cd "$REPO/third-party" && sha256sum -c libopentui.so.sha256) || exit 1
# hash record of everything staged from downloads
sha256sum "$OUT/stage-tools/bin/proot" \
    "$OUT/stage-tools/lib/libtalloc.so.2.5.0" \
    "$OUT/stage-tools/lib/libandroid-shmem.so" \
    "$OUT/stage-tools/lib/libopentui.so" | tee "$OUT/stage-tools/SHA256SUMS"
chmod 755 "$OUT/stage-tools/bin/"* 2>/dev/null || true
chmod 755 "$OUT/stage-tools/lib/"* "$OUT/stage-tools/libexec/proot/"* 2>/dev/null || true
echo OK

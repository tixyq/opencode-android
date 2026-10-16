#!/usr/bin/env bash
# Full offline-capable build. Run fetch-*.sh / build-pty.sh first (or ./tools/all.sh).
# All inputs come from $REPO/build; outputs go there too. Nothing outside
# the repo is read except the toolchain (aapt2 dx apksigner javac zip...).
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
APP="$REPO/app"
OUT="$REPO/build/apk"
ANDROID_JAR="${ANDROID_JAR:-$REPO/build/android.jar}"
KEYSTORE="$REPO/build/keys/opencode-debug.jks"
PASSFILE="$REPO/build/keys/storepass.txt"

# Toolchain: plain PATH first (Termux packages, distro packages),
# then ANDROID_HOME/build-tools (standard SDK layout, any Linux).
find_sdk_tool() {
    if command -v "$1" >/dev/null 2>&1; then
        command -v "$1"
        return 0
    fi
    for home in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" /opt/android-sdk /usr/lib/android-sdk; do
        [ -n "$home" ] || continue
        best="$(ls -d "$home"/build-tools/*/ 2>/dev/null | sort -V | tail -n 1)"
        if [ -n "$best" ] && [ -x "$best$1" ]; then
            echo "$best$1"
            return 0
        fi
    done
    return 1
}
AAPT2="$(find_sdk_tool aapt2)" || { echo "missing tool: aapt2 (PATH or ANDROID_HOME/build-tools)" >&2; exit 1; }
APKSIGNER="$(find_sdk_tool apksigner)" || { echo "missing tool: apksigner (PATH or ANDROID_HOME/build-tools)" >&2; exit 1; }
if command -v dx >/dev/null 2>&1; then
    DEXER="dx"
    DEX_OUT_FLAG="--output"
elif D8BIN="$(find_sdk_tool d8)"; then
    DEXER="$D8BIN"
    DEX_OUT_FLAG="--output-dir"
else
    echo "missing tool: dx or d8 (PATH or ANDROID_HOME/build-tools)" >&2
    exit 1
fi
for t in javac keytool zip unzip python3; do
    command -v "$t" >/dev/null 2>&1 || { echo "missing tool: $t" >&2; exit 1; }
done
echo "toolchain: aapt2=$AAPT2 dexer=$DEXER apksigner=$APKSIGNER javac=$(command -v javac)"

for f in "$REPO/build/bootstrap-clean.zip" "$REPO/build/www.zip" \
         "$REPO/build/opencode-2.0.27" "$REPO/build/librust_pty_arm64.so" \
         "$REPO/build/android.jar" "$ANDROID_JAR"; do
    [ -f "$f" ] || { echo "missing input: $f (run tools/fetch-*.sh first)" >&2; exit 1; }
done
# opencode binary must report the pinned version, never bundle blindly
# shellcheck disable=SC1091
. "$REPO/third-party/pins.env"
WANT="$(basename "$OPENCODE_ASSET" .deb)"; WANT="${WANT#opencode_}"; WANT="${WANT%_aarch64}"
BINCHECK="$REPO/build/opencode-2.0.27"
echo "$OPENCODE_BIN_SHA256  $BINCHECK" | sha256sum -c -
if "$BINCHECK" --version >/dev/null 2>&1; then
    V=$("$BINCHECK" --version 2>/dev/null | head -n 1)
    echo "server binary: $V"
    case "$V" in *"$WANT"*) ;; *) echo "bad server version ($V, want $WANT)" >&2; exit 1;; esac
else
    echo "note: cannot execute aarch64 binary on this host; SHA256 pin stands as the guarantee"
fi

VER=$(grep -o 'android:versionCode="[0-9]*"' "$APP/AndroidManifest.xml" | grep -o '[0-9]*')
APK="$REPO/build/OpenCode-$VER.apk"
ASSETS="$REPO/build/assets"
rm -rf "$OUT" "$APK" "$ASSETS"
mkdir -p "$OUT/gen" "$OUT/obj" "$OUT/dex" "$ASSETS"

echo "== assets: tools =="
mkdir -p "$ASSETS/tools/bin" "$ASSETS/tools/lib" "$ASSETS/tools/libexec/proot"
cp "$REPO/build/stage-tools/bin/proot" "$ASSETS/tools/bin/"
cp "$REPO/build/stage-tools/libexec/proot/loader" "$ASSETS/tools/libexec/proot/"
cp "$REPO/build/stage-tools/libexec/proot/loader32" "$ASSETS/tools/libexec/proot/"
cp "$REPO/build/stage-tools/lib/libtalloc.so.2.5.0" \
   "$REPO/build/stage-tools/lib/libandroid-shmem.so" \
   "$REPO/build/stage-tools/lib/libopentui.so" \
   "$ASSETS/tools/lib/"
ln -sf libtalloc.so.2.5.0 "$ASSETS/tools/lib/libtalloc.so.2"
cp "$REPO/build/librust_pty_arm64.so" "$ASSETS/tools/lib/"
chmod 755 "$ASSETS/tools/bin/"* "$ASSETS/tools/lib/"* "$ASSETS/tools/libexec/proot/"*

echo "== assets: bootstrap + www =="
cp "$REPO/build/bootstrap-clean.zip" "$ASSETS/bootstrap.zip"
cp "$REPO/build/www.zip" "$ASSETS/www.zip"
cp "$REPO/build/loader.html" "$ASSETS/loader.html"
cp "$REPO/app/legal/NOTICES" "$ASSETS/NOTICES"

echo "== aapt2 compile =="
"$AAPT2" compile --dir "$APP/res" -o "$OUT/res.zip"

echo "== aapt2 link =="
"$AAPT2" link -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$APP/AndroidManifest.xml" \
  --min-sdk-version 28 --target-sdk-version 28 \
  --java "$OUT/gen" \
  -A "$ASSETS" \
  "$OUT/res.zip"

echo "== javac =="
find "$APP/java" "$OUT/gen" -name "*.java" > "$OUT/sources.txt"
set +e
javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
  -d "$OUT/obj" @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1
JAVAC_CODE=$?
set -e
grep -v "bootstrap\|deprecat\|obsolete\|Xlint\|warnings\|Note:" "$OUT/javac.log" || true
if [ "$JAVAC_CODE" -ne 0 ]; then echo "javac FAILED, aborting"; exit 1; fi
if [ -z "$(ls -A "$OUT/obj" 2>/dev/null)" ]; then echo "no classes, aborting"; exit 1; fi

echo "== dex =="
if [ "$DEX_OUT_FLAG" = "--output" ]; then
    # classic dx
    $DEXER --dex --output="$OUT/dex/classes.dex" "$OUT/obj"
else
    # d8 (modern build-tools): directory output, needs the platform on --lib
    FINDINGS=$(find "$OUT/obj" -name '*.class' | tr '\n' ' ')
    # shellcheck disable=SC2086
    $DEXER --output "$OUT/dex/" --lib "$ANDROID_JAR" --min-api 28 $FINDINGS
fi
if [ ! -s "$OUT/dex/classes.dex" ]; then echo "empty classes.dex, aborting"; exit 1; fi

echo "== add classes.dex =="
python3 - "$OUT/base.apk" "$OUT/dex/classes.dex" <<'EOF'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
print('dex added')
EOF

echo "== native libs (STORED, uncompressed) =="
mkdir -p "$OUT/stage/libs"
cp "$REPO/build/opencode-2.0.27" "$OUT/stage/libs/libopencode.so"
cp "$REPO/third-party/libopentui.so" "$OUT/stage/libs/libopentui.so"
cp "$REPO/build/stage-tools/bin/proot" "$OUT/stage/libs/libproot.so"
chmod 755 "$OUT/stage/libs/"*.so
python3 - "$OUT/base.apk" "$OUT/stage/libs" <<'EOF'
import sys, os, zipfile
apk, libs = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_STORED) as z:
    for name in sorted(os.listdir(libs)):
        info = zipfile.ZipInfo('lib/arm64-v8a/' + name)
        info.compress_type = zipfile.ZIP_STORED
        info.external_attr = (0o755 << 16)
        with open(os.path.join(libs, name), 'rb') as f:
            z.writestr(info, f.read())
        print('lib added:', name)
EOF

if [ ! -f "$KEYSTORE" ]; then
    echo "== generating throwaway debug keystore (NOT for release) =="
    mkdir -p "$(dirname "$KEYSTORE")"
    head -c 24 /dev/urandom | base64 | tr -d '\n' > "$PASSFILE"
    P=$(cat "$PASSFILE")
    keytool -genkeypair -keystore "$KEYSTORE" -alias opencode \
        -keyalg RSA -keysize 2048 -validity 10950 \
        -storepass "$P" -keypass "$P" \
        -dname "CN=OpenCode Debug, OU=dev, O=local, C=US" >/dev/null
fi

echo "== sign =="
P=$(cat "$PASSFILE")
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass "pass:$P" --key-pass "pass:$P" --out "$APK" "$OUT/base.apk"

echo "== verify =="
"$APKSIGNER" verify --print-certs "$APK" | head -n 8
ls -la "$APK"

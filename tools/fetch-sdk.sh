#!/usr/bin/env bash
# Android SDK platform jar for javac -bootclasspath.
# Prefers a local sdkmanager; falls back to Google's pinned platform zip.
# Output: $REPO/build/android.jar
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$REPO/build"
mkdir -p "$OUT"
if [ -f "$OUT/android.jar" ]; then
    echo "cached: android.jar"
    exit 0
fi
if command -v sdkmanager >/dev/null 2>&1; then
    echo "installing platforms;android-28 via sdkmanager"
    yes | sdkmanager "platforms;android-28" >/dev/null
    ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
    cp "$ANDROID_HOME/platforms/android-28/android.jar" "$OUT/android.jar"
else
    echo "fetch: platform-28_r04.zip (dl.google.com)"
    curl -fsSL --max-time 300 -o "$OUT/platform-28_r04.zip" \
        "https://dl.google.com/android/repository/platform-28_r04.zip"
    python3 - "$OUT/platform-28_r04.zip" "$OUT/android.jar" <<'EOF'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
names = [n for n in z.namelist() if n.endswith('android.jar')]
assert names, 'no android.jar in platform zip'
open(sys.argv[2], 'wb').write(z.read(names[0]))
print('extracted', names[0])
EOF
fi
ls -la "$OUT/android.jar"
echo OK

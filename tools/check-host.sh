#!/usr/bin/env bash
# Verify the build host has everything needed. Works on any modern Linux
# (x86_64/aarch64, glibc), Termux included. Fails loudly with hints.
set -uo pipefail
MISS=0
need() { # command, install hint
    if command -v "$1" >/dev/null 2>&1; then
        echo "ok: $1 ($($1 --version 2>/dev/null | head -n 1))"
    else
        echo "MISSING: $1 -- $2"
        MISS=1
    fi
}
echo "== core (any Linux) =="
need bash "any shell"
need curl "curl package"
need git "git package"
need python3 "python3 package"
need zip "zip package"
need unzip "unzip package"
need sha256sum "coreutils package"
need dpkg-deb "Debian/Ubuntu: apt install dpkg | Fedora: dnf install dpkg | Termux: preinstalled"
need javac "JDK 17+ (Temurin/OpenJDK) or Termux: pkg install openjdk-17"
need keytool "comes with the JDK"
need cargo "rustup (rustup.rs) or Termux: pkg install rust"
need rustc "same as cargo"
echo "== Android tools: PATH or ANDROID_HOME =="
if command -v aapt2 >/dev/null 2>&1; then
    echo "ok: aapt2 (PATH)"
else
    echo "info: aapt2 not in PATH, will look under ANDROID_HOME/build-tools"
fi
if command -v dx >/dev/null 2>&1; then
    DEXTOOL="dx"
elif find_sdk_tool d8 >/dev/null; then
    DEXTOOL="d8"
else
    echo "missing tool: dx or d8 (PATH or ANDROID_HOME/build-tools)" >&2
    exit 1
fi
if command -v apksigner >/dev/null 2>&1; then
    echo "ok: apksigner (PATH)"
else
    echo "info: apksigner not in PATH, will look under ANDROID_HOME/build-tools"
fi
ARCH="$(uname -m)"
echo "== host arch: $ARCH =="
if [ "$ARCH" != "aarch64" ] && [ "$ARCH" != "arm64" ]; then
    echo "note: APK still targets arm64-v8a; only the PTY bridge needs a cross toolchain:"
    if [ -z "${ANDROID_NDK_HOME:-}" ]; then
        echo "MISSING: ANDROID_NDK_HOME (https://developer.android.com/ndk/downloads)"
        MISS=1
    else
        echo "ok: ANDROID_NDK_HOME=$ANDROID_NDK_HOME"
    fi
    echo "note: 'rustup target add aarch64-linux-android' runs automatically at build"
else
    echo "ok: native aarch64 build, no NDK needed"
fi
if [ "$MISS" -ne 0 ]; then echo "HOST CHECK FAILED" >&2; exit 1; fi
echo "HOST CHECK OK"

# OpenCode for Android

Self-contained Android APK (`com.opencode`, targetSdk 28) that runs an
OpenCode server, a bundled PWA web UI and a Linux shell
entirely on the phone — nothing else needs to be installed, no root.

App identity: name `OpenCode`, package `com.opencode`. Version comes from
`app/AndroidManifest.xml` (`versionCode`/`versionName` travel together).

## Layout

- `app/` — our Android code (`MainActivity`, `ServerService`,
  `StaticServer`, `Notifier`), manifest, resources, launcher icon source.
- `webui/overlays/` — our web additions: `mobile.css`, `mobile.js`,
  `enter-newline.js`, `loader.html`, `oc-mobile/changelog-empty-01.json`.
- `webui/tools/` — mirror crawler + deterministic patch script.
- `webui/MANIFEST.sha256` — pinned upstream snapshot (457 files).
- `rust/bun-pty` — submodule (sursaone/bun-pty, pinned commit in
  `.gitmodules`); `rust/bun-pty.patch` + `rust/stub-serial/` + `rust/Cargo.lock`
  are ours and make it build for bionic/Android.
- `third-party/` — pins (`pins.env`), provenance notes, and the one blob
  with no downloadable source (`libopentui.so` + hash).
- `tools/` — fetch + build pipeline. `build/`, `download/` are work dirs
  (git-ignored).
- `keys/` — empty on purpose: the debug keystore is generated at build.
  **Release updates on devices require the original release keystore,
  which is private and never committed here.**

## Build host

Any modern Linux, x86_64 or aarch64 — including on-device Linux
environments such as Termux on Android:

- `bash curl git python3 zip unzip sha256sum dpkg-deb`
- JDK 17+ (`javac`, `keytool`)
- `cargo` + `rustc` (rustup recommended; distro packages work too)
- Android tools: `aapt2`, `apksigner`, and `dx` or `d8` — from `PATH`
  (distro packages) or from an SDK
  (`ANDROID_HOME`/`ANDROID_SDK_ROOT`, highest `build-tools/*` wins)
- Android SDK platform android-28 (`tools/fetch-sdk.sh` fetches it via
  `sdkmanager` or dl.google.com; `ANDROID_JAR` env overrides the path)
- For non-ARM hosts only: `ANDROID_NDK_HOME` + `rustup target add
  aarch64-linux-android` (the PTY bridge cross-compiles; everything else
  is just downloaded and packed, arch-independent)

`tools/check-host.sh` verifies all of this and tells you exactly what is
missing. Then:

```sh
git clone --recurse-submodules <this-repo>
cd opencode-android
./tools/check-host.sh
./tools/all.sh        # fetch + verify + build, ~1 GB downloads
ls build/OpenCode-*.apk
```

Every fetch is hash-pinned (`third-party/pins.env`,
`webui/MANIFEST.sha256`); any drift aborts loudly instead of silently
changing the app. See `third-party/PROVENANCE.md` for where each blob
comes from and why it is referenced the way it is.

## What goes into the APK

- Server binary: pinned `opencode_2.0.27` release asset (SHA-verified,
  `--version`-gated; never bundled blind).
- `libopentui.so`, `libproot.so`: staged native libs (STORED, uncompressed).
- Termux userland: official bootstrap from the F-Droid Termux APK
  (byte-verified), repacked only to strip zip extras old Android
  `ZipInputStream` chokes on (entries, bytes, modes unchanged).
- Web UI: upstream snapshot + our overlays + two asserted patches
  (mobile tags, offline changelog).
- At runtime the app self-heals symlinks after apt upgrades, keeps the
  terminal `opencode`-free, stages server updates for next restart, and
  notifies on permissions/questions/replies. Details live in the code.

## License

MIT (see LICENSE) for our code. Third-party components keep their own
licenses — see third-party/PROVENANCE.md. If the MIT default does
not fit, replace LICENSE before publishing.

## Status

Unofficial community client, not affiliated with or endorsed by the
upstream OpenCode project. "OpenCode" and the logo are trademarks of
their respective owners and are used here only to identify compatibility.

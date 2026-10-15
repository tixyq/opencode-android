# OpenCode for Android

Self-contained Android APK (`com.opencode`, targetSdk 28) that runs an
OpenCode server, a bundled PWA web UI and a Termux-compatible shell
entirely on the phone — no Termux app required, no root.

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

Termux on aarch64 Android with:
`pkg install aapt2 dx apksigner openjdk-17 python git zip unzip curl rust`
plus Android SDK platform android-28 (`tools/fetch-sdk.sh` handles it via
`sdkmanager`, falling back to dl.google.com).

Then:

```sh
git clone --recurse-submodules <this-repo>
cd opencode-android
./tools/all.sh        # fetch + verify + build, ~1 GB downloads, ~10 min
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
licenses — see THIRD_PARTY.md / PROVENANCE.md. If the MIT default does
not fit, replace LICENSE before publishing.

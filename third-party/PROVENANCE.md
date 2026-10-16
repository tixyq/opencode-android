# Third-party provenance: what is ours, what is fetched, what is vendored.
#
# Rule of this repo: nothing third-party lives here as an unexplained blob.
# Every external artifact is either a git submodule, a pinned download
# (URL + SHA256 in third-party/pins.env, verified by tools/fetch-*.sh),
# or — only when no downloadable source exists — a vendored snapshot with
# a PROVENANCE note below. Our own code and overlays need no attribution
# beyond LICENSE.

## Git submodules (.gitmodules)

- `rust/bun-pty` → https://github.com/sursaone/bun-pty.git @ 41dd5b8
  (upstream `bun-pty` v0.4.8 era). Only `rust-pty/` (the cdylib crate) is
  used. Local adjustment in `rust/bun-pty.patch` + our stub crate
  `rust/stub-serial/` (see below).

## Pinned downloads (verified by hash, never committed)

- Termux base APK `com.termux_1022.apk` (F-Droid, com.termux 0.119.0-beta.3):
  source of the official bootstrap userland. The APK embeds it as
  `lib/arm64-v8a/libtermux-bootstrap.so`, byte-identical to a zip.
  Repacked locally to `bootstrap-clean.zip` (extra fields stripped so
  Android's ZipInputStream accepts it; entries, order, bytes, modes
  unchanged — verified entry-by-entry).
- Termux system debs from packages.termux.dev (exact pool paths + SHAs in
  pins.env): proot 5.1.107.96, libtalloc 2.5.0, libandroid-shmem 0.7.
  Only the listed files are extracted (proot binary + loader, the two
  loader libs).
- OpenCode server `opencode_2.0.27_aarch64.deb` from Hope2333/opencode-termux
  (tag Push261013, SHA in pins.env). Only `usr/bin/opencode` is extracted
  and bundled as the server binary. The in-app updater tracks newer
  releases at runtime via tags.atom + SHA256SUMS.txt (see ServerService).
- Web UI snapshot from https://beta.opencode.ai (mirror + hash manifest in
  webui/MANIFEST.sha256). Upstream is a moving target: the manifest pins
  the exact snapshot; fetch fails loudly on drift.
- Android SDK platform android-28 (`platforms;android-28` via sdkmanager)
  for android.jar. On a Termux build host, aapt2/dx/apksigner/javac/cargo
  come from Termux packages instead (`pkg install aapt2 dx apksigner
  openjdk-17 python git zip unzip curl rust`).

## Vendored snapshot (no stable download URL found)

- `third-party/libopentui.so` (+ .sha256): OpenTUI native renderer,
  dlopen'd lazily by the server for TUI. No package owns it on Termux and
  no standalone upstream asset exists; snapshot taken from a working
  install (identical to the copy shipped with the upstream opencode
  Android bundle in ~/opencode-install). If upstream ever publishes it
  separately, replace this blob with a pinned download.

## Our code (this repo, see LICENSE)

- `app/` — Android app: MainActivity, ServerService (env bootstrap,
  oc-shell/proot entry, updater, notifications), StaticServer, Notifier,
  manifest, resources, launcher icon source (`app/icon-src/`).
- `webui/overlays/` — mobile.css, mobile.js, enter-newline.js, loader.html,
  oc-mobile/changelog-empty-01.json; index.html patch + changelog rewrite
  applied deterministically by webui/tools.
- `rust/stub-serial/` — minimal `serial` crate stub (Android has no serial
  ports; rust-pty never uses that backend). MIT, ours.
- `rust/bun-pty.patch` — relativized `[patch.crates-io]` to the stub.
- `tools/` — fetch + build pipeline.
- Debug keystore is GENERATED at build time (tools/build.sh), never stored.

## Component licenses (why your own license choice is constrained)

Our code is under `LICENSE` (MIT by default, swappable). These apply to
bundled third-party parts regardless of that choice:

- MIT: opencode server + web UI + libopentui (anomalyco/opencode),
  bun-pty (sursaone/bun-pty), portable-pty et al (Rust crates, MIT/Apache
  dual), our stub-serial (MIT). MIT requires keeping copyright notices:
  they live here and upstream; binary distributions should also carry
  them (e.g. an in-app legal note — currently TODO, see README).
- GPL (copyleft): the Termux userland + proot ship GPLv2/GPLv3 code
  (bash, coreutils, readline, proot, dpkg, ...). They run as separate
  processes (mere aggregation, so our code stays under its own license),
  but distributing their binaries obliges a source offer: use the exact
  package versions below to find the corresponding sources in
  https://github.com/termux/termux-packages.
- Pinned Termux package versions in this build: proot 5.1.107.96,
  libtalloc 2.5.0, libandroid-shmem 0.7, bash 5.3.20, readline 8.3.6,
  ncurses 6.6.x, coreutils 9.12, dash 0.5.13.5.

Trademark note: "OpenCode" and the logo belong to the upstream project.
This is an unofficial client; README states no affiliation.

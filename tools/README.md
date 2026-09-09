# tools

Scripts for building, porting and diagnosing Cobalt. Grouped by what they act
on. Everything here resolves paths relative to itself — none of it assumes a
particular checkout location.

Most of these run **inside WSL**, against the Chromium tree on a Linux
filesystem. See [`../docs/build.md`](../docs/build.md).

## `build/` — producing an APK

| Script | Purpose |
|---|---|
| `setup-build-host.sh` | Install Chromium's build dependencies |
| `bootstrap-depot-tools.sh` | Fetch depot_tools' own python and ninja |
| `fetch-chromium.sh` | Clone and sync the Chromium tree at a pinned tag |
| `build-env.sh` | Single source of truth for where the tree lives |
| `build-chromium.sh` | `deps`/`hooks`/`gen`/`build` — writes `args.gn` |
| `resume-build.sh` | Resume a stopped build; `--clean` discards `out/` first |
| `start-build-detached.sh` | Start it `setsid`-detached so a client kill cannot reach it |
| `stop-build.sh` | Stop cleanly, preserving completed objects |
| `verify-and-build.sh` | Verify a migrated checkout, then build |
| `setup-ccache.sh` | Configure ccache (the local stand-in for Google's RBE cache) |
| `check-gn-args.sh` | Check a GN argument exists before using it |
| `verify-checkout.sh`, `verify-deps.sh`, `fix-missing-dep.sh` | Catch a half-synced tree before the build does |

**`--clean` or not** is the question that matters. Resume after a clean stop;
`--clean` after an I/O fault, where objects written while the disk was failing
look present and are not.

## `monitor/` — is it still alive

| Script | Purpose |
|---|---|
| `watch-build.sh` | Emits only actionable events: read-only volume, memory pressure, stall, OOM, completion |
| `build-progress.sh` | Progress from siso's own edge counter |
| `build-edge-rate.sh` | Sampled rate — the run average hides that Blink is far slower than Skia |
| `build-mem.sh`, `build-diag.sh`, `sync-progress.sh` | Memory, failure detail, gclient sync progress |

A monitor that greps only for the success marker stays silent through a
crashloop. These report failure states too — that is the entire point.

## `disk/` — the build volume

Written during four failures of an external SSD. See
[`../docs/build.md`](../docs/build.md).

| Script | Purpose |
|---|---|
| `fsck-volume.sh` | Unmount and check properly — handles all three things that block `e2fsck` |
| `repair-build-volume.sh` | Repair, resolving by **label**, refusing any device backing a root mount |
| `check-volume.sh`, `diag-volume.sh` | Mount state, writability, kernel I/O errors |
| `migrate-to-c.sh`, `migrate-depot-tools.sh` | Move the tree and the toolchain to a healthy disk |
| `disable-fstab.sh`, `enable-fstab.sh` | Take `/build` out of fstab so a check can run |

Device letters are **not** identity. WSL reassigns `/dev/sdX` across restarts,
and an `fsck` aimed at yesterday's letter once pointed at the distro root.

## `patches/` — the Kiwi port

| Script | Purpose |
|---|---|
| `extract-kiwi-delta.sh` | Classify the overlay against pristine Chromium |
| `classify-patches.sh` | Keep / drop / rebase signals per patch |
| `try-patches.sh` | `git apply --check` the series against a target |
| `trial-vs-class.sh` | Cross-reference trial results with the classification |
| `check-mv2.sh` | Is MV2 still flag-gated in this tree, or deleted? |
| `cobalt-mv2-defaults.py` | Make MV2 enabled by default |
| `check-webui-migration.sh` | Locate Polymer→Lit successors for deleted WebUI files |
| `check-eol.sh`, `analyse-delta.sh`, `check-delta-complete.sh` | Line endings and delta sanity |
| `apply-batch.sh` | Apply a named batch, reporting each patch rather than stopping at the first failure |
| `cobalt-chromesearch-scheme.py` | Port Kiwi's chrome-search URL pattern to M140, where the constant moved up a layer |
| `cobalt-search-engines.py` | Offer Ecosia, Kagi and Qwant in every region |
| `cobalt-mv2-action-schemas.py` | Ship the MV2 browserAction/pageAction schemas on Android; without them the renderer aborts |
| `cobalt-saf-file-enumerator.py` | Let `base::FileEnumerator` list `/SAF/` paths, so localised extensions can side-load |
| `cobalt-bundle-ublock.py` | Stage the uBO CRX into the APK, install it, and make it disableable-but-not-removable |

`apply-batch.sh` is also a **gate**. It refuses any patch whose added lines
contain `#if 0`, `&& 0` or `|| true`. One such patch reached the tree and had
disabled the check enforcing `web_accessible_resources`; it compiled for two
builds because extensions were not built for Android at the time. See
[decision 0011](../docs/decisions/0011-refuse-mechanical-disablers.md).

`cobalt-search-engines.py` does three things, and the second two are the reason
it works: it adds the engines, raises `kTopSearchEnginesThreshold` so non-EEA
regions are not truncated to the first five, and bumps `kCurrentDataVersion` so
existing profiles re-read the list. Without either, the data is correct
everywhere and invisible on device.

`check-mv2.sh` is a **gate**, not a report: a missing flag means upstream moved
from gating MV2 to deleting it, which changes whether a tree is a viable rebase
target at all. See [decision 0005](../docs/decisions/0005-support-mv2-and-mv3.md).

## `device/`

`verify-apk.sh` — check the built APK is a real, complete APK. Three false
"success" signals in this project's history earned that.

## `assets/`

| Script | Purpose |
|---|---|
| `make-icons.py` | One SVG → 14 rasters: Android densities, adaptive, store, favicon |
| `recolour-icon.py` | Re-derive the icon from the source palette |
| `fetch-google-fonts.py` | Bake the Google Fonts catalogue into the app at build time |
| `brand-chromium.py` | Product name and launcher icons |
| `brand-android-logos.py` | The logos Android shows *after* the launcher icon: splash, first-run, wordmark |
| `brand-launcher-layers.py` | The launcher layers `brand-chromium.py` misses: the round icon's background and the monochrome themed icon |
| `brand-strings.py` | Rename the product in user-facing strings only |
| `fetch-ublock.py` | Fetch uBlock Origin, pinned by version **and** sha256 |
| `pack-crx.py` | Pack an unpacked extension into a signed CRX3 |

`brand-strings.py` rewrites text nodes inside `<message>` elements and nothing
else. Message ids are referenced from C++ and Java, `desc` attributes are
instructions to translators, `chrome://` is a URL scheme, and the Chrome Web
Store is a product Cobalt does not operate — renaming any of those is a bug
rather than branding.

`fetch-ublock.py` pins a hash as well as a version. An unpinned bundled
extension would let a future uBO release change what ships inside Cobalt with no
change to Cobalt, so a mismatch is a supply-chain event rather than a stale pin.
`pack-crx.py` exists because Chromium's packer needs a desktop Chrome binary and
this build produces neither; it verifies its own output by re-parsing it.

Both icon tools take `--check`, so CI can fail when a raster drifts from the SVG.

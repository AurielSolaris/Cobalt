# Reproducing a Cobalt build

How someone with a Linux box and no context turns this repository into the same
APK we ship.

## The two build systems, and which one is which

Cobalt has two, and confusing them wastes an afternoon.

| | What it builds |
|---|---|
| `./gradlew` | The **0.1.0 Kotlin chassis** — the pre-Chromium shell in `modules/`. It does not fetch Chromium, does not apply patches, and never touches ccache. |
| `tools/build/build-chromium.sh` | **The browser.** Chromium 140 plus Cobalt's patch series, built to `chrome_public_apk`. This is the one that produces a Cobalt APK. |

## The whole thing

```sh
tools/build/setup-build-host.sh          # once, as root: volume + packages
tools/build/bootstrap-depot-tools.sh
tools/build/fetch-chromium.sh            # pinned to 140.0.7339.264
tools/build/setup-ccache.sh              # 40 GB object cache
tools/build/build-chromium.sh deps       # once, as root
tools/build/build-chromium.sh all        # hooks + patch + gen + build
```

`all` is `gclient runhooks` → **apply the patch series** → `gn gen` → `autoninja`.
The steps also run individually: `hooks`, `patch`, `gen`, `build`.

`docs/build.md` covers the host requirements — WSL2 on Ubuntu, an ext4 volume,
and why the checkout cannot live on `/mnt/c`.

## The patch series

`tools/patches/series.txt` is the **single declaration of everything Cobalt
changes about Chromium**. `tools/patches/apply-all.sh` reads it top to bottom.

```sh
tools/patches/apply-all.sh --check   # list what would run, change nothing
tools/patches/apply-all.sh           # apply
```

Entries are one of:

```
kiwi <patch-name>              apply patches/kiwi-105/<name>.patch
kiwi <patch-name> :: <marker>  ... treating <marker> in the target files as "already applied"
tool <script> [args]           run a script from tools/, $SRC expands to the checkout
note <text>                    a section heading
```

Kiwi patches go through `apply-batch.sh` rather than `git apply`, so the
disabler refusal gate ([decision
0011](decisions/0011-refuse-mechanical-disablers.md)) cannot be bypassed by
adding a line here.

### It has to be re-runnable, and it is

Every entry is idempotent, and the series is designed to be run again after
every `gclient sync`. Three things made that harder than it sounds, and all
three are now handled:

- **`git apply --check` fails on an already-applied patch.** `apply-batch.sh`
  now tries `--reverse --check` first, which succeeds exactly when the patch is
  already in the tree.
- **A later entry can rewrite the line an earlier patch added.**
  `cobalt-chromesearch-scheme.py` rewrites the line Kiwi's `url_pattern.cc`
  patch inserts, so no reverse check can recognise it. Those entries carry a
  `:: marker` instead.
- **Part of the series lands in a git submodule.**
  `third_party/search_engines_data/resources` holds the search engine
  definitions. `gclient sync` and `git submodule update` both reset it, and
  `src`'s own `git status` **never reports it as dirty**. Re-running the series
  is the only thing that puts it back.

### What is deliberately not in it

The Route A scripts — `cobalt-full-extensions-android.py`,
`cobalt-platform-apps-guards.py`, `cobalt-android-extensions-graph.py`,
`cobalt-circular-includes.py`. They are work in progress toward
`enable_extensions = true` alongside `is_desktop_android`, still parked on an
`//apps` reference. Adding them would break a fresh checkout. See
[decision 0010](decisions/0010-desktop-android-extensions.md).

## ccache

Wired in and working: `setup-ccache.sh` configures a 40 GB cache, and `args.gn`
sets `cc_wrapper = "ccache"`.

It is the local stand-in for the shared object cache Google gives Chromium
engineers through RBE. Object files are not portable — a `.o` is tied to the
clang revision, the full flag set, the sysroot and the build paths — so the
cache has to be local, and nobody can ship prebuilt objects.

Measured effect on this project, incremental builds on a 16-core host capped at
10 GB with `-j 4`:

| Change | Steps | Wall |
|---|---:|---:|
| Bundled extension staging + policy provider | 36 | 5m11s |
| Two schema files into a resource bundle | 12 | 2m09s |
| `base::FileEnumerator` + five icon rasters | 142 | 5m05s |

The first build after enabling ccache gets no hits; the win starts from the
second.

## Verifying rather than trusting

Two habits this project keeps, because both have already caught a false
success:

- **`autoninja` can exit 0 on a build that failed** in siso's scheduling phase.
  `build-chromium.sh build` checks the APK exists and fails loudly if not.
- **An APK existing is not proof it was just built.** `start-build-detached.sh`
  writes `/opt/cobalt/build.start` before starting; completion means the APK is
  *newer than that stamp*, never merely present.

- **siso's `.siso_fs_state` can go stale, and a stale one turns builds into
  no-ops that report success.** After editing
  `runtime_enabled_features.json5`, three consecutive builds reported
  `Build Succeeded` — one of them "27 steps" — while the generated
  `runtime_enabled_features.cc` was never recompiled into the APK. Asking for
  the generated file by name still returned `ninja: no work to do`, and `gn
  refs` confirmed the dependency was real, so the graph was right and the cache
  was wrong. Deleting `out/Default/.siso_fs_state` fixed it: the next build
  planned **80,306 steps** instead of zero.

  So: if a source change produces a suspiciously small build, or the artifact
  does not behave as the source says it should, **suspect the cache before
  suspecting the patch.** Removing that file is safe — it is a cache, and the
  next build rebuilds it.

  Two related habits: do not mix plain `ninja` with `siso` in the same out dir
  (`ninja` rewrites the build log and siso then re-plans from scratch), and do
  not edit a shell script on `/mnt/c` while WSL is executing it — bash reads
  scripts incrementally, and a rewrite mid-run produced
  `unexpected EOF while looking for matching '"'` from a file that was
  syntactically fine by the time it was checked.

There are two scripts for checking the result rather than assuming it:

| Script | Answers |
|---|---|
| `tools/device/verify-apk.sh` | is the APK real, freshly built, and does it carry the assets it should |
| `tools/device/check-unlimited-storage.py` | does the bundled extension actually get `unlimitedStorage` on the device, on both of the two mechanisms that honour it |

The second one exists because reading GN files got that question wrong twice in
a row — first predicting a gate that is not there, then measuring an extension
that had been terminated, which looks identical to having no permission at all.
Build files tell you what is compiled, not what happens. It needs `adb`, a
running Cobalt, and `python3 -m pip install websocket-client`.

## Known gaps

- The series is verified idempotent against **our** tree. It has not been run
  against a genuinely fresh checkout end to end; the Kiwi entries would take the
  ordinary apply path there rather than the already-applied path.
- `fetch-chromium.sh` pins the Chromium tag but the DEPS-managed submodules
  follow whatever that tag's DEPS resolves to. Reproducible in practice, not
  cryptographically pinned.

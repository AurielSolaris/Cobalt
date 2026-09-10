# APK size

Cobalt targets 4 GB of RAM and two cores
([decision 0004](decisions/0004-performance-budget.md)). Install footprint
belongs to that budget: on a device where free storage is measured in single
-digit gigabytes, half a gigabyte for a browser is a product problem.

Almost all of the current number is **build configuration, not Chromium**.

## Measured

`nightly`, arm64, `is_official_build = false`:

| | Cobalt | Google WebView |
|---|---:|---:|
| Package | **317.8 MB** | 75.7 MB |
| Extracted `lib/` | **207.0 MB** | **0.0 MB** |
| **On device** | **525 MB** | **75.7 MB** |

WebView is the fair reference available on the test device: the *same Chromium
engine*, built officially by Google, for the same arm64 target. It is a trimmed
subset — no browser UI, fewer locales — so the totals are not directly
comparable, but it pins the scale, and the `lib/` row is comparable exactly.

Inside Cobalt's APK:

| Section | Size | Note |
|---|---:|---|
| `lib/` | 206.8 MB | almost entirely `libchrome.so`, stored uncompressed |
| `assets/` | 69.6 MB | of which **332 locale paks, 50.0 MB** |
| `resources.arsc` | 30.7 MB | |
| `classes.dex` | 8.8 MB | |
| `res/` | 3.9 MB | |
| `assets/ublock.crx` | 4.5 MB | [bundled uBO](ublock-bundling.md) |

Reproduce:

```sh
unzip -v out/Default/apks/ChromePublic.apk | sort -k1 -rn | head
adb shell du -sk "$(dirname "$(adb shell pm path app.auriel.cobalt \
    | sed 's/package://')")"/*
```

For perspective on what the engine work costs: removing Google Play Services'
vision AARs ([gms-removal.md](gms-removal.md)) took **53,600 bytes** off. That
work is about attack surface and independence. Size is a separate problem with
separate levers.

## The three levers, and when each is safe

### 1. Do not extract the native libraries — tried, blocked, reverted

Worth **~207 MB**, and the reason it was free: since API 23 the platform loader
maps a `.so` straight out of an APK when it is stored uncompressed and the app
declares `android:extractNativeLibs="false"`. Cobalt's libraries were already
uncompressed. The manifest simply never said so, and PackageManager duplicated
207 MB at install time.

**Upstream's own setting.** Chromium declares it on monochrome and both
trichrome manifests — every configuration real users install. It is absent only
from the base manifest, which `chrome_public_apk` uses, because
`incremental_install/generate_android_manifest.py` rewrites it back to `"true"`
for the developer inner loop.

**It does not work, and the reason is not the manifest.** The patch built
cleanly and the attribute reached the APK. Install failed outright:

```
INSTALL_FAILED_INVALID_APK: Failed to extract native libraries, res=-2
```

`extractNativeLibs="false"` requires *every* entry under `lib/` to be stored
uncompressed. `libchrome.so` is. **`libchrome_crashpad_handler.so` is not**, and
that is deliberate — `chrome/android/chrome_public_apk_tmpl.gni:679`:

```gn
# TODO(agrieve): Use Crashpad trampoline in chrome_public_apk.
if (!_is_monochrome && !_is_trichrome) {
  deps += [ "//components/crash/core/app:chrome_crashpad_handler_named_as_so" ]
  loadable_modules += [ "$root_out_dir/libchrome_crashpad_handler.so" ]
  library_always_compress += [ "libchrome_crashpad_handler.so" ]
}
```

So the missing attribute was a **symptom, not the cause**. `chrome_public_apk`
ships the crashpad handler as a compressed `.so` that has to be extracted to
disk because it is an *executable* to be exec'd, and you cannot exec a file
inside a zip. Monochrome and trichrome can set the attribute precisely because
they use the crashpad **trampoline** instead — which upstream's own TODO says
`chrome_public_apk` should adopt too.

The whole 207 MB duplication therefore exists to support a **1.8 MB** crash
handler.

**Reverted.** What looked like a one-attribute packaging fix is really the
crashpad trampoline migration, and that is not something to attempt in the
middle of GMS removal — crash reporting is entangled with
[decision 0013](decisions/0013-remove-google-play-services.md)'s telemetry work,
which has its own ordering.

The prediction that held: it failed **loudly and immediately**, at install,
before the browser ever ran. Nothing subtle to mistake for a de-Googling bug.

**To unblock it,** in rough order of preference:

1. Adopt the crashpad trampoline for `chrome_public_apk` (upstream's TODO), then
   set the attribute. This is the real fix.
2. Or decide crash reporting's fate first as part of 0013 — if Cobalt does not
   ship a Google-endpoint crash handler at all, the compressed `.so` may simply
   go away, and this becomes free.

Option 2 is worth noting: **this may resolve itself as a side effect of GMS
removal**, which is another argument for not forcing it now.

### 2. `is_official_build = true` — deferred, deliberately

The largest win by far. `libchrome.so` is **205 MB** against WebView's entire
76 MB package, and the gap is ThinLTO and PGO, not symbols — `symbol_level = 0`
is already set.

It is deferred until GMS removal closes, and the reason is not build time:

**R8 full mode strips code reachable only through reflection and JNI**, which is
precisely the failure shape produced by deleting Java classes and GMS
dependencies — a class missing at runtime, with no compile error. Turning it on
mid-removal means every regression raises two hypotheses instead of one. The
whole point of [`gms-inventory.sh`](../tools/build/gms-inventory.sh) and the
patch series is changing one variable at a time.

The practical costs are real too, and secondary:

- ThinLTO linking on a host already memory-bound — 10 GB cap,
  `concurrent_links = 2`, heavy translation units already near 1 GB per clang.
- `chrome/build/pgo_profiles/` does not exist in this checkout. It needs
  `checkout_pgo_profiles` and a `gclient sync`, which resets
  `third_party/search_engines_data/resources` — the submodule trap
  [`series.txt`](reproducible-build.md) already warns about.
- ccache is invalidated wholesale.

### 3. Locale pruning — a product decision, not an optimisation

`assets/locales/` is **332 pak files, 50.0 MB**. Dropping locales is technically
safe: a missing one falls back to en-US rather than failing.

But which languages Cobalt supports is a product question, and Kiwi's audience
was heavily non-English. This is not a size decision to make quietly, and it is
recorded here rather than acted on.

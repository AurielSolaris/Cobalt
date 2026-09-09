# Gate B — Route B build results

The first Cobalt APK with Chromium's extension subsystem compiled in.
Partial: everything verifiable without unlocking the device is done; the
on-device extension checks are not.

**Build:** Chromium 140.0.7339.264, `is_desktop_android = true`
**APK:** 331,517,322 bytes, 2026-09-09 05:18 UTC
**Device:** RZ8R81K1NBP

## Verified

### The build genuinely succeeded

```
1h5m06.75s Build Succeeded: 3390 steps - 0.87/s
```

Checked against this project's four previous false successes:

- [x] `Build Succeeded`, not merely a zero exit code.
- [x] The single `FAILED` line in the log is `//chrome/android:monochrome_64_public_bundle__lint`,
      marked skipped and not on `chrome_public_apk`'s path.
- [x] APK **newer than the build start stamp** (05:18 vs 04:11), so it is this
      build's artifact and not the Gate A APK still sitting at that path.
- [x] Valid zip, magic `504b`, 4,418 entries, `unzip -t` clean.
- [x] `AndroidManifest.xml`, `classes.dex`, `resources.arsc` all present.
- [x] `lib/arm64-v8a/libchrome.so` — 214,963,192 bytes.

### The extension subsystem is actually in the binary

This is what Route B was for. Gate A's APK could not contain any of it, because
`enable_extensions = !is_android && …` compiled the whole subsystem out.
Strings in `libchrome.so`:

| symbol | count |
|---|---:|
| `chrome-extension` | 18 |
| `declarativeNetRequest` | 28 |
| `manifest_version` | 9 |
| `web_accessible_resources` | 6 |
| `ExtensionRegistry` | 4 |
| `webRequestBlocking` | **2** |
| `background.service_worker` | 2 |

`webRequestBlocking` is the MV2 API the project exists to keep. Its presence in
an Android binary is the single most load-bearing line in this table.

Size went 303 MB → 317 MB against Gate A; the delta is the subsystem.

### It runs

- [x] Installs via `adb install -r -d` — `Success`.
- [x] Package id is `app.auriel.cobalt` (confirmed in the R8 map).
- [x] Launches; process stays alive; 3 processes (browser + renderer + GPU).
- [x] No `FATAL` or `AndroidRuntime` crash in logcat.

## Not verified — device is PIN-locked

`mDreamingLockscreen=true`. Screenshots return the lock screen, so nothing
visual could be confirmed and no UI could be driven. Not attempted.

Outstanding, all needing an unlocked device:

- [ ] `chrome://extensions` renders. One navigation attempt returned a blank
      page, but the device was locked at the time, so **this is not evidence of
      a fault** — it needs redoing before it means anything.
- [ ] An MV2 extension installs from a `.crx` and survives a restart.
- [ ] No MV2 deprecation warning appears.
- [ ] An MV3 extension installs and runs.
- [ ] A `webRequestBlocking` listener fires and actually blocks a request.
- [ ] `web_accessible_resources` is enforced (see [decision 0011](decisions/0011-refuse-mechanical-disablers.md)).
- [ ] Component extension bundled resources load.
- [ ] Gate A's pinch-zoom check, still NOT RUN from Gate A.

## What it cost to get here

Three build failures, each a different class:

1. **Oversubscription** — 6 jobs against 9.9GB. Throughput collapsed to 6
   edges/min with 7.3GB in swap. Four jobs restored it.
2. **A disabled security check** — Kiwi's `extension_protocols.cc` patch, applied
   verbatim, `#if 0`'d `AllowExtensionResourceLoad`. Caught only because
   `-Werror` flagged the now-unused variables. Reverted; `apply-batch.sh` now
   refuses this class outright.
3. **A moved symbol** — `content::kChromeSearchScheme` became
   `chrome::kChromeSearchScheme`, which `extensions/` may not include. Ported
   with a local constant.

Only the second was dangerous. The third is the ordinary shape of a
35-milestone rebase, exactly as `rebase-trial.md` predicted.

## Note

`svc power stayon true` was set on the device to stop the screen sleeping
between commands. Revert with `adb shell svc power stayon false`.

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

## Extension platform — verified on device

Device unlocked; all of the following was driven on RZ8R81K1NBP.

### The management surface exists

- [x] `chrome://extensions` renders the **full desktop WebUI** on a phone.
- [x] Developer mode toggles, revealing **Load unpacked / Pack extension / Update**.
- [x] `Load unpacked` opens the Android SAF directory picker and loads from it.
- [x] `chrome://version` reports `Cobalt 140.0.7339.264` and `Desktop Android: true`.

### MV2 — the platform the project exists to keep

- [x] MV2 extension loads, enables, and runs a **persistent background page**.
- [x] **No deprecation warning anywhere.**
- [x] Survives a full `force-stop` + relaunch, **still enabled** — not auto-disabled.
- [x] Disabled state also survives a restart, so the pref is genuinely persisted
      rather than defaulted.
- [x] **`webRequestBlocking` actually blocks**: navigating to a blocked host gives
      `ERR_BLOCKED_BY_CLIENT`, "This page has been blocked by an extension".
- [x] **Control test**: with the extension disabled the same URL loads normally,
      so the block is attributable to the extension and not to anything else.

### The uBlock Origin API surface

uBO needs more than a blocking listener, so a probe exercising its actual
dependencies was run. All nine checks pass:

| check | result |
|---|---|
| `manifest_version` | 2 |
| `getBackgroundPage` | reachable — MV2-only API, absent in MV3 |
| `bg_messaging` | pong |
| `tabs_query` | works |
| **`block_subresource`** | **cancelled** — blocking works on subresources, not just navigations |
| **`onHeadersReceived`** | **fires in blocking mode** — uBO's CSP-injection path |
| `storage_6MB` | 6,291,456 bytes — `unlimitedStorage` honoured |
| `storage_roundtrip` | persisted |
| `control_fetch` | not blocked |

Content script, injected at `document_start` in all frames — how uBO hides
elements before paint:

```
cs_ran_at=loading       → document_start injection confirmed
war_allowed=OK_loaded   → listed resource loads from a page context
war_secret=blocked_OK   → unlisted resource is REFUSED
```

That last line is [decision 0011](decisions/0011-refuse-mechanical-disablers.md)'s
check passing. Had Kiwi's `#if 0` around `AllowExtensionResourceLoad` shipped, it
would read `LEAKED_FAIL`.

**One MV2 rule was enforced against us, correctly.** The probe was first written
with `"persistent": false` and Chromium refused it: *"The 'webRequest' API cannot
be used with event pages."* uBO sets `"persistent": true` for exactly this
reason. A build with a half-wired MV2 would have accepted the manifest and then
silently failed to block.

### MV3 — alongside, not instead

- [x] MV3 extension loads and runs a **service worker**.
- [x] **Three extensions run simultaneously**: 2 × MV2 with background pages and
      1 × MV3 with a service worker. Gate B's "both, not either" is satisfied.

## Still outstanding

- [ ] **Real uBlock Origin**, as opposed to a probe shaped like it. The probe
      covers the API surface; it does not prove uBO's own code runs.
- [ ] **uBO as a bundled system extension** — Stage 8. Everything above was
      side-loaded unpacked through SAF, which is a *different install path* from
      a preinstalled recommended-mode extension. Disable-but-not-uninstall is
      therefore still completely untested; the Remove button was present on every
      extension here, which is the correct behaviour for unpacked ones.
- [ ] Pinch-zoom, still NOT RUN, carried over from Gate A.

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

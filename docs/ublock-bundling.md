# Bundling uBlock Origin

Release gate 1 of 3. Where it stands: **done and verified on device.** uBO ships
in the APK, installs itself on first run, loads its filter lists, blocks
requests, and cannot be uninstalled but can be disabled.

Decision: [0006](decisions/0006-bundle-ublock-origin.md).
Applied by `tools/patches/cobalt-bundle-ublock.py`, in the series.

## What was built

Three mechanisms, none of them a Kiwi patch — this is upstream machinery
configured for Android.

### 1. Delivery — the CRX rides in the APK

`chrome/browser/cobalt/extensions/BUILD.gn` declares an `android_assets` target
carrying the signed CRX, wired into `chrome_public_non_pak_assets` so it lands
in `chrome_public_apk`. `disable_compression = true` is a requirement, not an
optimisation: `base::android::OpenApkAsset` can only return a file descriptor
for an asset stored uncompressed. A CRX is a signed zip, so this costs nothing.

Verified in the built APK:

```
4529818  Stored  4529818   0%  assets/ublock.crx
```

### 2. Staging — out of the APK, onto a real path

`chrome/browser/cobalt/bundled_extensions.cc` copies the asset into
`chrome::DIR_EXTERNAL_EXTENSIONS` and writes the `external_extensions.json` that
describes it. It runs from `ChromeBrowserMainPartsAndroid::PreCreateThreads`,
which is **before any profile exists** — `ExtensionService` builds the external
providers during profile construction, and a provider that runs first would find
an empty directory and install nothing until the next launch.

It must be a real path: `ExternalPrefLoader` uses `base::FileEnumerator` and
`JSONFileValueDeserializer`, neither of which can see inside an APK.

`DIR_EXTERNAL_EXTENSIONS` needed one change. Chromium already registers an
`ExternalPrefLoader` over it on **every non-Windows platform, Android included**
— the `#else` branch in `ExternalProviderImpl::CreateExternalProviders`. What it
lacked was a usable directory: on Android `base::DIR_MODULE` is the native
library directory, which is read-only, so the path's `create_dir` fails.
`chrome_paths.cc` now points it at app data on Android.

### 3. Uninstall protection — policy, not a patch

`ExtensionSettings` with `installation_mode: normal_installed` maps to
`ManagedInstallationMode::kRecommended`, and
`StandardManagementPolicyProvider::MustRemainInstalled` returns true for it
while `MustRemainEnabled` does not. That is exactly the *cannot remove, can
disable* semantic decision 0006 specified, and it is a maintained upstream
feature rather than something Cobalt carries.

The pref behind it (`extensions.management`) is loaded with
`force_managed=true`, so it cannot be set as a user pref — it has to arrive
through a policy provider. `CobaltBundledExtensionPolicyProvider` supplies it,
owned by `ProfilePolicyConnector` and pushed **last** onto that profile's
provider list, which is the lowest priority: a real administrator's
`ExtensionSettings` replaces Cobalt's rather than merging with it. Getting this
onto the *profile's* policy service rather than the browser's took a second
attempt; see below.

#### The update URL, and why nothing fetches it

`normal_installed` refuses to parse without a valid `update_url`, and the
recommended-mode policy loader turns that into a second external provider
offering the same extension over the network. It loses:
`kExternalPref` (rank 2) outranks `kExternalPrefDownload` (rank 1), so once the
local CRX is in, `OnExternalExtensionUpdateUrlFound` reports `ALREADY_INSTALLED`
and returns false.

The URL is a Cobalt-owned placeholder that does not resolve today. It exists to
satisfy the parser, and to be the real self-hosted endpoint later — decision
0006 already flags that a bundled uBO goes stale and needs refreshing.

## On-device result

Fresh profile, `pm clear`, first launch:

```
W chromium: [external_pref_loader.cc:293] You are using an old-style extension
            deployment method (external_extensions.json)...
```

— the staged JSON was found and read. The extension then installed and its
renderer started:

```
"loaded-origin-0" = "chrome-extension://fimbmjialkbnbbedhcpdodbhicmjfgli"
"num-extensions"  = "1"
"switch-2"        = "--extension-process"
```

So delivery, staging, installation and load all work end to end.

## What blocked it, and what still does

### Fixed: browserAction killed the renderer

The first run died immediately:

```
F chromium: [FATAL:native_extension_bindings_system.cc:213] Unknown API browserAction
```

The *feature* was available — `browserAction` in `_api_features.json` is
unconditional and depends only on `manifest:browser_action`, which uBO declares
and which parsed fine. The **schema** was missing, and `GetAPISchema` is a hard
`LOG_IF(FATAL)`, so the renderer aborted rather than degrading.

Cause: a gating asymmetry in `chrome/common/extensions/api/api_sources.gni`.
`action.json` — MV3's `chrome.action` — is in the unconditional
`uncompiled_sources_` list. Its MV2 equivalents `browser_action.json` and
`page_action.json` are inside `if (enable_extensions)`. Route B sets
`enable_extensions_core` but not `enable_extensions`, so MV3 got its schema and
MV2 did not.

Upstream never hits this because every platform that enables extensions also
sets `enable_extensions`. Cobalt does not, and
[decision 0005](decisions/0005-support-mv2-and-mv3.md) commits to MV2 as a
first-class manifest version. Fixed by
`tools/patches/cobalt-mv2-action-schemas.py`, which moves the two schema-only
files into the unconditional list. No generated C++, and the browser-side
`extension_action_dispatcher.cc` was already compiled unconditionally.

### Fixed: webNavigation was off for desktop_android

With the renderer surviving, uBO then failed at its own bootstrap:

```
I chromium: [INFO:CONSOLE:43] "Uncaught TypeError: Cannot read properties of
            undefined (reading 'getFrame')",
            source: chrome-extension://fimbmj.../js/webext.js (43)
```

`js/webext.js:118` is `promisify(chrome.webNavigation, 'getFrame')`, and
`_permission_features.json` excluded the API from `desktop_android` in as many
words.

The API is two classes with very different dependencies.
`WebNavigationTabObserver` is a `WebContentsObserver` and produces every event
uBO consumes, plus `getFrame` and `getAllFrames`; nothing about it is
desktop-specific. `WebNavigationEventRouter` is a `TabStripModelObserver`,
serves exactly `onTabReplaced` and `onCreatedNavigationTarget`, and is the sole
reason the target asserted `enable_extensions` and pulled
`//chrome/browser/ui:browser_tab_strip`, `:browser_list` and `/browser_window`.

`tools/patches/cobalt-webnavigation-android.py` compiles the API on Android and
guards the tab-strip half with `#if !BUILDFLAG(IS_ANDROID)` — the same condition
`extension_tab_util.h` already uses for `GetTabStripModel`, the helper the
guarded code calls. Upstream had drawn this line; the target had just not been
split along it.

**What Android does not get:** `onTabReplaced` and `onCreatedNavigationTarget`.
Both describe desktop tab-strip mechanics Cobalt's shell does not have yet, and
neither is used by uBO. The guarded region is small and self-contained on
purpose — it is where Cobalt's own tab model hooks in later.

### Fixed: the policy was on the wrong PolicyService

The first attempt registered `CobaltBundledExtensionPolicyProvider` in
`ChromeBrowserPolicyConnector::CreatePolicyProviders`, which looks like the
obvious place and is the wrong one. `ProfilePolicyConnector::Init` does not
iterate the browser connector's providers — it picks specific named ones
(`GetPlatformProvider`, `proxy_policy_provider`, `command_line_policy_provider`,
plus the profile's own cloud provider). A provider appended to the browser list
reaches `local_state` and nothing else, while `extensions.management` is a
profile pref.

The symptom was quiet and would have been easy to declare done: uBO installed
and ran, and only `chrome://policy` ("No policies set") and a working **Remove**
button on `chrome://extensions` gave it away. The provider is now owned by
`ProfilePolicyConnector` and `Init`ed against the profile's schema registry,
following `RestrictedMGSPolicyProvider`'s precedent exactly.

## Verified on device

Fresh profile, `pm clear`, first launch, on a real phone:

| Check | Result |
|---|---|
| CRX in the APK | `4529818  Stored` — uncompressed, as `OpenApkAsset` requires |
| Staged and read | `external_pref_loader.cc:293` reports the deployment file |
| Installed and loaded | extension process runs with `--extension-process` |
| Filter lists compiled | **176,450 network + 54,877 cosmetic filters** |
| Lists auto-updated | EasyList 85,287/85,821, EasyPrivacy 55,781/56,529, uBlock filters 71,385/71,494, AdGuard Mobile 12,665/12,836 |
| **Actually blocks** | `static.doubleclick.net` → **`ERR_BLOCKED_BY_CLIENT`**, "blocked by an extension" |
| Cannot uninstall | no **Remove** button, and Safety Check no longer offers removal |
| Can disable | the toggle works, and the Remove button stays absent while disabled |

The blocking result is the one that matters: it proves MV2 `webRequestBlocking`
with a persistent background page works on Android, which is the reason Cobalt
exists.

### Not a bug: unlimitedStorage already works

This document previously claimed `unlimitedStorage` was excluded from
`desktop_android` the same way `webNavigation` was. **That was an expectation,
not a measurement, and it was wrong.** Unlike `webNavigation`, the permission
carries no `platforms` key at all in `_permission_features.json`, so it is
available everywhere, and both halves of the implementation are already
compiled into the Android build.

Measured on device rather than reasoned about, because that is what the
webNavigation and policy-provider mistakes both cost. See
[`tools/device/check-unlimited-storage.py`](../tools/device/check-unlimited-storage.py),
which re-runs every number below.

**The quota system** (`ExtensionSpecialStoragePolicy` → `QuotaManagerImpl`),
covering IndexedDB, Cache API and the rest:

| Origin | `navigator.storage.estimate().quota` | Which path |
|---|---:|---|
| `https://example.com` | 72,061,068,902 | 60% of a 120 GB disk — the shared pool |
| `chrome-extension://fimbmj…` | 94,801,749,772 | free space + usage — the unlimited path |

`chrome://quota-internals` agrees and is the clearer statement of it, because it
splits the total by bucket:

```
Total Storage Usage:  13654207 B (13654207 B for unlimited origins)
```

Every byte uBO owns is accounted to the unlimited bucket.

**The settings store** (`LocalValueStoreCache` →
`WeakUnlimitedSettingsStorage`), which is a separate mechanism covering
`chrome.storage.local`:

```
QUOTA_BYTES  10,485,760      the documented limit
in use       21,599,453      already more than double it
wrote        11,534,336      one MB past the limit on its own
lastError    null            accepted, quota enforcer bypassed
```

So no patch, no series entry, and nothing to port. The gating asymmetry that
caught `browserAction` and `webNavigation` simply is not present here.

## Still open

- **Refreshing the bundled version** is a release-checklist item
  ([decision 0006](decisions/0006-bundle-ublock-origin.md)), not something to
  notice later.
- **The update endpoint** at `updates.cobalt.auriel` does not exist yet. Nothing
  fetches it today, by design, but it is now a named future dependency.
- ~~**uBO can end up `TERMINATED` and stay there.**~~ **Fixed** — see below.

## Fixed: a killed extension never came back

The worst bug found so far, and it was found by accident while measuring
something else.

**Reproduced deterministically:**

```
$ adb shell su -c 'kill -9 <extension renderer pid>'

developerPrivate.getExtensionsInfo   ->  state: "TERMINATED"
navigate to static.doubleclick.net/instream/ad_status.js
    before:  ERR_BLOCKED_BY_CLIENT, "This page has been blocked by an extension"
    after:   window.google_ad_status = 1;      <- the ad script runs
```

The process stayed dead, the extension stayed `TERMINATED`, and nothing in the
UI said so. **The content blocker stops blocking and does not tell anyone.**

### Why upstream does not hit this

`ExtensionService::OnExtensionHostRenderProcessGone` posts
`ExtensionRegistrar::TerminateExtension`, commented "either fully working or not
loaded at all, but never half-crashed" — deliberate and correct. What follows it
on desktop is a **crash bubble** offering Reload. There is no automatic reload
anywhere in Chromium, because desktop does not need one: desktop renderers are
not killed by an out-of-memory killer, and a human is looking at the window.

Neither holds on Android. Killing background renderers is routine, Cobalt
targets 4 GB and two cores, and Cobalt has no crash bubble.

Worth knowing why it is *structurally* likely here:
`ProcessRankPolicyAndroid::CalculateRank` ranks on focus, visibility and
active-tab. **An extension background page is never any of the three**, so it
sits at the bottom of the kill list permanently.

### The fix

`tools/patches/cobalt-extension-auto-reload.py`, in the series. A Cobalt-owned
`ExtensionHostRegistry::Observer` in
`chrome/browser/cobalt/extension_recovery/` reloads a terminated extension:

| attempt | 1 | 2 | 3 | 4 | 5 | then |
|---|---|---|---|---|---|---|
| delay | 2s | 8s | 30s | 60s | 120s | give up, loudly |

The counter resets after five minutes of health, so an extension killed once a
day is always recovered. A user-disabled or blocklisted extension is left alone
— that is a decision, not a crash.

**This is recovery, not prevention.** The right prevention is teaching
`ProcessRankPolicyAndroid` that an extension host is not a discardable
background tab, which is a larger change and can never be complete anyway: on a
4 GB device Android eventually wins, so recovery has to exist regardless.

Verified on device against the reproduction above:

```
W chromium: [extension_auto_reload.cc:82] Cobalt: extension fimbmjia...
            terminated (renderer gone); reloading in 2s (attempt 1 of 5)
state: ENABLED
static.doubleclick.net is blocked  /  ERR_BLOCKED_BY_CLIENT
```

### Three things the compiler and I disagreed about first

- `kDelays[attempt]` on a C array — rejected by `-Werror,-Wunsafe-buffer-usage`.
  Now a `switch`.
- The forward declaration went next to `ChromeExtensionRegistrarDelegate`, which
  is **inside `namespace extensions`**, so it declared
  `extensions::cobalt::ExtensionAutoReload` — compiled fine, then failed at the
  point of use with "allocation of incomplete type". Now at global scope, with
  the member written `::cobalt::`.
- The first draft wrote `chrome/browser/cobalt/extensions/BUILD.gn`, which
  **already belongs to the uBO bundling patch** and was silently clobbered. The
  component now owns `chrome/browser/cobalt/extension_recovery/` instead, so
  the two patches cannot collide and neither depends on series order.

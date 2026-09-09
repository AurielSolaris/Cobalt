# Bundling uBlock Origin

Release gate 1 of 3. Where it stands: **uBO ships in the APK, installs itself on
first run, and loads. It does not yet run**, because two of the permissions it
declares are switched off for Android upstream.

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
**appended last** in `CreatePolicyProviders`, which is the lowest priority: a
real administrator's `ExtensionSettings` replaces Cobalt's rather than merging
with it.

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

### Open: webNavigation is off for desktop_android

With the renderer surviving, uBO now fails at its own bootstrap:

```
I chromium: [INFO:CONSOLE:43] "Uncaught TypeError: Cannot read properties of
            undefined (reading 'getFrame')",
            source: chrome-extension://fimbmj.../js/webext.js (43)
```

`js/webext.js:118` is `promisify(chrome.webNavigation, 'getFrame')`, and
`chrome.webNavigation` is undefined. `_permission_features.json` says why, in
as many words:

```jsonc
"webNavigation": {
  "channel": "stable",
  "extension_types": ["extension", "legacy_packaged_app"],
  // "desktop_android" is not supported.
  "platforms": ["chromeos", "linux", "mac", "win"]
},
```

Checking every permission uBO declares:

| Permission | Available on desktop_android |
|---|---|
| `alarms` | yes |
| `contextMenus` | yes |
| `privacy` | yes |
| `storage` | yes |
| `tabs` | yes |
| **`unlimitedStorage`** | **no — excluded** |
| **`webNavigation`** | **no — excluded** |
| `webRequest` | yes |
| `webRequestBlocking` | yes |

The important line is the last one: **`webRequest` and `webRequestBlocking` are
not restricted**, so the blocking machinery uBO actually needs is present. What
is missing is frame bookkeeping and a storage quota flag.

`webNavigation` is not a one-line permission flip. Its implementation
(`chrome/browser/extensions/api/web_navigation/BUILD.gn`) carries
`assert(enable_extensions)` and depends on `//chrome/browser/ui:browser_tab_strip`
and `//chrome/browser/ui:browser_list` — desktop tab-strip concepts. Porting it
is the same class of work as Route A.

`unlimitedStorage` is expected to be much cheaper, but has not been looked at
yet.

## Next

1. Port `webNavigation` for desktop-android, or establish whether uBO can be run
   without it.
2. `unlimitedStorage`.
3. Confirm on device that uBO shows as disableable but not removable — the
   policy is wired and compiled, but the *user-visible* proof needs
   `chrome://extensions` with the extension actually running.
4. Refresh the bundled version as a release-checklist item (decision 0006).

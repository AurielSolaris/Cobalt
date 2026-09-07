# Batch 1 — extension core plumbing

The first patches to land in Stage 5a: `extensions/common` and
`extensions/browser`, the manifest and permission layer everything else sits on.
16 patches. Verdicts below are from reading each patch, not from the trial
classification alone.

## Why this batch first

Once the unpatched M140 APK exists we already have a working browser, so every
Kiwi patch is additive. That means ordering should follow **risk, not cost**:
extensions are the reason Cobalt exists and carry the largest unknown, so they
go first. If this batch cannot be brought forward, that is a project-level fact
worth learning in week one rather than month three.

## The surprise: these patches are tiny

| Patch | +/− | Verdict |
|---|---|---|
| `extensions_common_url_pattern.h` | +1 / −0 | take |
| `extensions_common_url_pattern.cc` | +2 / −0 | take |
| `extensions_common_extension.cc` | +2 / −1 | take |
| `extensions_common_manifest_handlers_permissions_parser.cc` | +4 / −0 | take |
| `extensions_common_manifest_handlers_csp_info.cc` | — | take |
| `extensions_common_manifest_handlers_default_locale_handler.cc` | — | take |
| `extensions_browser_extension_protocols.cc` | +9 / −0 | take |
| `extensions_common_user_script.cc` | — | take (fuzzy) |
| `extensions_browser_pref_names.cc/.h` | +1 / −1 | **rewrite** — `\|\| true` |
| `extensions_common_command.cc` | +1 / −1 | **rewrite** — `\|\| true` |
| `extensions_browser_event_router.cc` | +1 / −0 | **drop** — debug logging only |
| `extensions_browser_zipfile_installer.cc` | +14 / −2 | **reimplement** |
| `extensions_browser_extension_registrar.cc` | +23 / −0 | **reimplement** |
| `chrome_browser_extensions_chrome_extensions_browser_client.cc` | — | port |
| `chrome_browser_extensions_browser_context_keyed_service_factories.cc` | — | relocate (file gone) |

The core plumbing is roughly **58 added lines**. The 58% conflict rate in
[`rebase-trial.md`](rebase-trial.md) is mostly **context drift against one-line
changes** — the code around them moved, not the changes themselves. That is a
much cheaper kind of conflict than the number suggests.

## The three that need real work

**`event_router.cc` — drop.** One `LOG(INFO)` line and one deleted blank line.
Nothing else. Measuring the whole series found only 13 patches containing debug
logging and 3 that are *nothing but* logging; this is one of them.

**`command.cc` and `pref_names.cc` — rewrite, don't apply.** Both are
`BUILDFLAG(...) || true`. The intent is recoverable and reasonable: report the
Linux keybinding platform on Android so extension shortcuts declared for
`linux` work, and enable `kChromeAppsEnabled`. Re-express as real Android
conditions. Applying `|| true` verbatim ports a landmine — upstream restructuring
silently changes what it disables.

**`zipfile_installer.cc` — reimplement.** Real feature: install a zip whose
manifest sits in a subdirectory, which is what every GitHub source download looks
like. Worth keeping. But it is implemented with a **file-scope mutable global**
(`base::FilePath additional_path`) written from `IsManifestFile()` and read
later — two concurrent installs corrupt each other, and it makes a predicate
function stateful. Keep the behaviour, thread the path through properly.

**`extension_registrar.cc` — reimplement.** Two real behaviours: auto-reload on
`TerminateExtension` (Android kills background processes, so extensions
otherwise die silently), and notifying the Android app menu which extensions are
running. Both wanted. But it reaches `chrome/browser/android/...` from inside
`extensions/browser`, which inverts Chromium's layering, and forward-declares
`AppMenuBridge_GetRunningExtensionsInternal` by hand instead of including a
header. Needs an observer on our side of the boundary.

## Landing order

1. The 8 take-as-is patches — verify the tree still builds.
2. MV2 defaults via `tools/cobalt-mv2-defaults.py` — no Kiwi patch involved.
3. The 2 rewrites.
4. The 2 reimplementations.
5. `chrome_extensions_browser_client.cc` and the relocated factory file.

Steps 1 and 2 together should already load an MV2 extension. That is the first
real proof point, and it is reachable before any of the hard work in 3–5.

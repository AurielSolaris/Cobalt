# Trial run: Kiwi's patches against Chromium 140

The first real measurement of Stage 5a's cost, taken before compiling anything.
`git apply --check` reports whether each patch would apply without modifying the
tree, so this ran against `src` while its dependencies were still downloading.

Produced by `tools/try-patches.sh`; cross-referenced with the keep/drop
classification by `tools/trial-vs-class.sh`.

**Series:** 277 patches extracted from Kiwi at Chromium 105.0.5195.24
**Target:** Chromium 140.0.7339.264 — 35 milestones, three years

## Headline

| Result | Patches | Share |
|---|---:|---:|
| **clean** — applies as-is | 46 | 16% |
| **fuzzy** — applies with relaxed context | 35 | 12% |
| **conflict** — file exists, patch no longer fits | 162 | 58% |
| **gone** — target file deleted upstream | 34 | 12% |

81 patches (29%) are essentially free. The rest is work.

A 58% conflict rate across three years of Chromium is not an anomaly — it is
roughly what three years of refactoring does to any out-of-tree patch set. It is
also precisely the number that would have been unknown until Stage 5a actually
started, which is why the trial was worth running early.

## The number is better than it looks

Two corrections make the real burden smaller.

**Dropped patches inflate it slightly.** Both hand-rolled Blink ad-blockers land
in `conflict`, exactly as predicted — they sit in the most heavily refactored
files in the tree. But dropping the ad-blocking and metadata patches only removes
6 from the trial, so the carried rate is still ~58%. The decision was right for
other reasons; it is not a conflict-rate fix.

**Android UI is not ported as code at all.** Per
[`module-map.md`](module-map.md), Kiwi's `chrome/android` patches are read as a
specification for which Chromium surfaces the shell must call — Cobalt's UI is
already Compose. That removes **26 conflicts and 9 "gone" files** from the
porting burden outright: the tab switcher, toolbar colours, menu XML, and
dimension resources are not our problem.

Net: roughly **160 patches genuinely need porting**, not 196.

## Extensions — the set that must survive

70 patches touch extensions. This is the work Cobalt cannot fail at.

| | Patches |
|---|---:|
| clean | 13 |
| fuzzy | 8 |
| conflict | 35 |
| **gone** | **14** |

The 14 deleted files are the ones to worry about, and they split into two very
different problems:

**Five C++ files moved or were restructured** — `extension_system_factory.cc`,
`browser_context_keyed_service_factories.cc`, `extension_message_bubble_controller.cc`,
`global_shortcut_listener.cc`, `proxy_overridden_bubble_delegate.cc`,
`extension_install_ui_default.cc`. The extension system did not go away; these
are refactors. Each needs its new home located and the change re-applied there.
Tedious, not dangerous.

**Five WebUI files no longer exist in that form** — `manager.html`,
`detail_view.html`, `item_list.html`, `toggle_row.html`, `toolbar.html`.
Chromium migrated its WebUI off Polymer/HTML to Lit and TypeScript.

This was initially flagged as the trial's top risk. **That was an overestimate,
made before measuring Kiwi's actual change**, and the measurement (via
`tools/check-webui-migration.sh`) is reassuring on both counts:

- **Every file has an obvious successor.** `manager.html` became `manager.html.ts`
  plus `manager.ts` and `manager.css`, and the same for the other four. The
  migration was a mechanical template split, not a redesign, so Kiwi's edits can
  be located in the new structure rather than reverse-engineered.
- **Kiwi's total change across all five files is 55 added lines** — 21 in
  `toggle_row`, 7 in `toolbar`, 3 each in `detail_view` and `item_list`, and
  none at all in `manager`.

55 lines of HTML re-expressed as Lit templates is a day's work with a clear
target, not an open-ended unknown. M140's extensions WebUI is 55 TypeScript
files, 23 of them Lit components, with zero Polymer remaining — so the
destination is consistent and current.

**The revised top risk is the 35 extension C++ conflicts and the 5 relocated C++
files**, which is ordinary porting rather than reimplementation.

## Everything else deleted upstream

Of the 34 gone files, beyond extensions:

- **8 Android tab UI** (`PseudoTab.java`, `TabSwitcherCoordinator`,
  `TabSwitcherMediator`, `TabGroupModelFilter`, …) — not ported; Cobalt's tab
  model is its own.
- **3 Blink paint files** (`collapsed_border_painter.cc`,
  `text_painter_base.cc`, `multi_column_set_painter.cc`) — almost certainly part
  of the element-hiding work. Expected to be dropped with the rest of it.
- **Misc**: `base/android/sys_utils.h`, `content/public/common/url_constants.cc`,
  `signin_view_controller.h`, `find_shortcut_behavior.js`,
  `shared_style_css.html`, and Kiwi's Remoting privacy-policy activity.

## Where the conflicts are

| Area | Conflicts |
|---|---:|
| `chrome/browser` | 81 |
| `chrome/android` | 26 (not ported) |
| `third_party` (Blink) | 14 |
| `extensions/browser` | 5 |
| `content/public` | 4 |
| `chrome/renderer` | 4 |
| `ui/webui` | 3 |
| `base` | 3 |
| others | 3 |

`chrome/browser` dominating is expected: it is where Kiwi did most of its work
and where Chromium churns most.

## What this changes

Nothing about the plan, which is the point — [decision
0003](decisions/0003-staged-chromium-rebase.md) chose M140 precisely so this
measurement would happen at three years rather than four, and with a checkpoint
behind it. The trial confirms the shape of the job rather than upsetting it.

Two things it does add:

1. **The extensions work is ordinary porting, not reimplementation.** The WebUI
   migration looked like the top risk until it was measured at 55 added lines
   against files with direct successors. The real cost sits in the 35 extension
   C++ conflicts and 5 relocated files — tedious, well-understood work.
2. **A regression baseline now exists.** Re-running `tools/try-patches.sh` after
   each hop gives a number to compare against, so progress on Stage 5 is
   measurable rather than felt.

## Reproducing

```sh
tools/try-patches.sh patches/kiwi-105 /build/chromium/m140/src /build/patch-trial
tools/trial-vs-class.sh /build/patch-trial patches/kiwi-105
```

Read-only — safe to run against a tree that is still syncing.

# Classifying Kiwi's 277 patches

What Cobalt carries forward to Chromium 140, what it drops, and why.

Signals gathered by `tools/patches/classify-patches.sh`; the verdicts below are reviewed
by hand. Numbers are added lines, which is the honest measure of rebase cost —
a 700-line insertion into Blink's fetch path is not comparable to a 3-line
string change.

## Summary

| Class | Patches | Added lines | Share |
|---|---:|---:|---:|
| **drop** — ad-blocking hacks | 3 | 1,208 | 25% |
| **drop** — branding, metadata | ~30 | ~400 | 8% |
| **keep** — extension system | 44 | 1,248 | 26% |
| **rebase** — everything else | ~200 | ~1,900 | 40% |
| Total | 277 | 4,799 | |

The headline: **roughly a third of Kiwi's diff is worth dropping outright**, and
it happens to be the third that would be hardest to rebase.

---

## Drop: the ad blocker

Three patches, **1,208 added lines — 25% of the entire delta** — implement ad
blocking by hand, inside the engine.

| Patch | Lines | What it does |
|---|---:|---|
| `blink/renderer/core/loader/base_fetch_context.cc` | 700 | Hardcoded domain and URL substring matching in Blink's resource-fetch path |
| `blink/renderer/core/layout/layout_object.cc` | 452 | Hides elements by matching DOM `id`/`class`/tag names during layout |
| `net/http/http_network_transaction.cc` | 56 | Further URL filtering in the network stack |

This is not a close call. The code is a long `if` chain of
`url.GetString().Contains("…")` calls spliced into a hot path, with commented-out
alternatives left in place. `layout_object.cc` hides elements by matching literal
strings like `"G-BOTTOM-SHEET"`, `"mealbar:0"`, and `"bvSecurePageWarning"` —
site-specific DOM hacks aimed at particular Google surfaces as they looked in 2022.

**Why it goes:**

- **It is stale by four years.** A hardcoded 2022 domain list against the 2026 ad
  ecosystem is close to worthless, and there is no update mechanism.
- **It sits in the worst possible place to rebase.** Blink's fetch and layout
  paths are heavily refactored upstream; these two files alone would likely
  account for most of the conflict pain across 35 milestones.
- **Cobalt already has a better answer, and it is the entire point of the
  project.** uBlock Origin via the extension system does this properly, with
  maintained filter lists. Carrying a broken built-in blocker *and* shipping
  extension support is strictly worse than shipping extension support.

Dropping these removes the two largest patches in the series and a quarter of the
rebase cost, and loses nothing a user would want.

**Caveat, honestly stated:** a built-in blocker works before any extension is
installed, so out-of-the-box behaviour changes. That is a defaults question for
Stage 7 — ship a recommended extension, or adopt a maintained declarative
ruleset — not a reason to port 1,208 lines of dead string matching.

---

## Drop: branding and metadata

Kiwi naming, its update and telemetry endpoints, `README.md`, `LICENSE`,
`VERSION`, `KIWI_VERSION`, `CHROMIUM_VERSION`, `.gitignore`, and the
`toolbox/` scripts.

36 patches carry Kiwi branding strings, but most are *mixed* — a Kiwi name inside
an otherwise legitimate change. Those get the string swapped, not the patch
dropped. Stage 4 handles this properly; it is mechanical.

Endpoints are a hard drop regardless: a Cobalt build must never phone Kiwi's
servers.

---

## Keep: the extension system

**44 patches, 1,248 added lines.** This is what Cobalt exists for.

Spread across `chrome/browser/extensions`, `extensions/common`,
`extensions/browser`, `chrome/renderer`, and the Android UI that surfaces
extensions — `AppMenuPropertiesDelegateImpl.java` (281 lines) is the app menu
that makes extensions reachable on a phone.

These patches are ported forward whatever it costs. If a conflict here cannot be
resolved, that is a project-level problem, not a patch-level one.

---

## Rebase: the rest

Roughly 200 patches, ~1,900 lines. Ordinary feature and UI work: the toolbar,
download settings, night mode, the new-tab page, and assorted Android
integration.

Ported case by case. Anything that no longer applies gets ported properly or
deleted with an issue filed — never force-applied.

### A wrinkle: 76 patches disable upstream code with `#if 0`

76 patches contain `#if 0`, `&& 0`, or `|| true` — upstream behaviour switched
off by editing preprocessor conditions rather than by configuration.
`chrome/browser/search/search.cc` is the worst, with seven such edits and
constructs like `#if !BUILDFLAG(IS_ANDROID) || true`.

These are cheap in line count (529 lines total) and expensive in every other way:
they carry no explanation of intent, and upstream restructuring silently changes
what they disable. Each needs the *intent* recovered and re-expressed as a real
build flag or feature toggle. Mechanically re-applying them would be porting a
bug.

---

## Mixed patches need splitting

Four patches carry ad-blocking *and* legitimate work, so they cannot simply be
dropped:

| Patch | Lines | Split |
|---|---:|---|
| `AppMenuPropertiesDelegateImpl.java` | 281 | keep the extension menu, drop the ad-block toggle |
| `ChromeActivity.java` | 241 | keep lifecycle wiring, drop blocker init |
| `chrome_java_resources.gni` | 196 | keep resource entries, drop blocker assets |
| `res/menu/main_menu.xml` | 40 | keep extension entries, drop blocker items |

Splitting happens during Stage 5a, against the real conflicts.

---

## What this changes about Stage 5a

The port is meaningfully smaller than the raw numbers suggested:

- Raw delta: **4,799 added lines** across 277 patches.
- After dropping ad-blocking and branding: **roughly 3,100 lines**.
- Of that, ~1,250 is extension work that must survive, and ~1,900 is ordinary
  porting.

The two files most likely to fight a 35-milestone rebase — Blink's fetch and
layout paths — are on the drop list. That is the single biggest risk reduction
available to this project, and it came free with reading the patches.

## Status

Classification is by area and signal, reviewed against samples of the largest
patches. It has **not** been validated by applying anything to Chromium 140 —
that is Stage 5a, and it will certainly move some patches between classes.

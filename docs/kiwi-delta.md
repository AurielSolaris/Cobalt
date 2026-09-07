# Kiwi's delta from Chromium

What Kiwi Browser actually changes, and what Cobalt has to carry forward.

**Status:** partial. The inventory below is complete; the classification is not,
and cannot be until the overlay is diffed against real Chromium 105 sources. See
[Blocked on the base checkout](#blocked-on-the-base-checkout).

---

## The pinned reference

| | |
|---|---|
| Repository | <https://github.com/kiwibrowser/src.next> |
| Branch | `kiwi` |
| Commit | `7be7edd1532148f22103cb4c5a1964d96297836f` |
| Commit date | 2025-04-08 |
| Subject | *Update README.md to point to the latest release of Kiwi* |
| Chromium base | **105.0.5195.24** (`CHROMIUM_VERSION`) |
| Kiwi version | 105.0.5195.33 (`KIWI_VERSION`) |

Reproduce it exactly:

```sh
git clone https://github.com/kiwibrowser/src.next .ref/kiwi
git -C .ref/kiwi checkout 7be7edd1532148f22103cb4c5a1964d96297836f
```

`.ref/` is not committed. The tree is a reference, not a dependency.

### A stale version file

The repository carries both `KIWI_VERSION` (105.0.5195.33) and `VERSION`
(93.0.4577.21). `CHROMIUM_VERSION` agrees with the former, and the last
substantive commits do too, so **105 is the real base** and `VERSION` is a leftover
from the M93 era that was never updated. Worth knowing before someone builds
against the wrong branch point.

### The project is archived

Kiwi was archived in January 2025. The final commit is a README edit pointing
users at Microsoft Edge. Nothing further is coming from upstream, which is the
whole reason Cobalt exists — and it also means this pin never needs to move.

---

## What the tree actually is

**`src.next` is not a Chromium checkout.** It is an *overlay*: 8,313 files that
are copied on top of a real Chromium tree at build time. Chromium itself is
around 400,000 files.

There are **zero `.patch` or `.diff` files** in it. Every one of those 8,313 files
is a complete source file. This is the single most important fact about the port,
and it shapes Stages 3 and 5:

- The overlay tells us **which** files Kiwi touched. It does not tell us **what**
  changed inside any of them.
- It carries along an unknown number of files that are byte-identical to upstream
  M105 — copied in for build convenience, not because Kiwi modified them.
- Applying the overlay onto a newer Chromium would **overwrite four years of
  upstream fixes** in every file it covers, including security fixes. It is not a
  rebase strategy; it is a way to silently revert Chromium.

So Stage 5 cannot "apply Kiwi's patch series" — there is no series. Creating one
is Stage 2's real deliverable.

---

## Inventory

8,313 files, excluding `.git`.

### By area

| Area | Files | Notes |
|---|---:|---|
| `chrome` | 3,134 | Browser UI, Android front end, extensions UI |
| `third_party` | 2,459 | 2,437 of them Blink |
| `extensions` | 569 | The extension system itself |
| `net` | 574 | Network stack |
| `content` | 488 | Content layer |
| `base` | 462 | Base library |
| `components` | 438 | Shared components |
| `remoting` | 68 | Chrome Remote Desktop |
| `ui` | 59 | UI toolkit |
| `services` | 35 | Mojo services |
| `toolbox` | 2 | Kiwi's own build helpers |

### By file type

| Type | Files | |
|---|---:|---|
| `.cc` | 3,301 | C++ implementation |
| `.h` | 2,472 | C++ headers |
| `.png` | 679 | Resources — branding and UI assets |
| `.java` | 671 | Android front end |
| `.xml` | 270 | Android resources and manifests |
| `.idl` | 154 | Blink interface definitions |
| `.grdp`, `.gn`, `.gni` | 164 | Strings and build files |
| `.ts`, `.html`, `.css` | 113 | WebUI |

### Where the Android work sits

| Path | Files |
|---|---:|
| `chrome/android/java/res` | 644 |
| `chrome/browser/ui/android` | 354 |
| `chrome/android/java/src` | 149 |
| `chrome/android/features/tab_ui` | 86 |
| `chrome/browser/download/android` | 80 |
| `chrome/browser/resources/extensions` | 76 |
| `chrome/browser/ui/extensions` | 61 |
| `chrome/browser/tabmodel/android` | 38 |

### Extension surface

**1,299 files** have "extension" in their path — roughly 16% of the overlay. That
is consistent with extensions being Kiwi's headline feature and the bulk of its
real work, and it makes Stage 8 the second-largest stage after the rebase.

### Branding surface

Only **21 source files** mention "kiwi" by name (`.java`, `.cc`, `.h`, `.gn`,
`.gni`), spread across `chrome/browser` (10), `chrome/android` (3),
`third_party/blink` (2), `components/embedder_support` (2), and one each in
`remoting/android`, `components/search`, and `components/ntp_tiles`.

That is a small, tractable rename surface for Stage 4 — but it is **only the
name**. Branding also lives in the 679 PNGs and in `.grdp` string files, and a
name grep says nothing about the functional changes, which are the hard part.

---

## The real delta — measured

Chromium `105.0.5195.24` was checked out (391,558 files, 5.6 GB) and every overlay
file compared against its upstream counterpart. Source only; **it was never built**,
and it has served its purpose and can be deleted.

| | Files | Share |
|---|---:|---:|
| Total in the overlay | 8,297 | |
| **Identical to upstream** | **7,746** | **93%** |
| Modified | 277 | 3% |
| Kiwi-only | 184 | 2% |
| Binary differs | 90 | 1% |

**93% of the overlay was upstream code Kiwi merely copied.** The real delta is 551
files — 6.6% of what the tree appeared to hold — and the modified portion comes to
**1.1 MB, 11,220 diff lines, 4,136 added and 755 removed**.

That is the most encouraging number the project has produced. Carrying ~4,100 added
lines across 35 milestones is ordinary work, not an epic.

The series is committed at [`../patches/`](../patches), which documents the areas
touched, the largest patches, and how to reproduce the extraction.

**Exactly one patch touches V8**, which independently supports
[`decisions/0001-keep-v8.md`](decisions/0001-keep-v8.md): Kiwi barely goes near the
JavaScript engine, so replacing it would buy nothing Kiwi ever wanted.

### A trap worth recording

The first extraction reported **0 identical out of 378** — every file changed. That
was wrong. `.ref/kiwi` had been cloned on Windows, where git rewrote it to CRLF;
upstream Chromium is LF, so every text file differed by invisible `\r` bytes alone.

The overlay is now cloned inside Linux, and `tools/extract-kiwi-delta.sh` refuses to
run if it samples an overlay with CRLF endings. A result of "100% modified" is a bug
report, not a finding.

### Classification — next

The 277 patches are extracted but **not yet sorted**. Each needs a class:

- **keep** — the extension system and the Android UI that makes extensions usable
  on a phone. 39 modified files mention extensions. This is what Cobalt is for.
- **drop** — branding, telemetry, update-check endpoints, the Edge migration
  README, and anything upstream has since absorbed.
- **rebase** — real changes needing a port forward across the gap.

| Patch | Area | Class | Rationale |
|---|---|---|---|
| _to be filled in_ | | | |

---

## V8 integration points

Recorded for documentation only. Cobalt ships V8 and is not replacing it; this
section exists so the Stage 12 experiment has a map if it ever runs, and for no
other reason. See [`decisions/0001-keep-v8.md`](decisions/0001-keep-v8.md).

**Of Kiwi's 277 patches, exactly one references `v8::` at all.** Kiwi's changes sit
in the browser UI, the extension system, and a little of Blink's loader and layout —
not in the JS engine. Whatever Stage 12 would cost, none of it is inherited from
Kiwi, and none of Kiwi's value depends on it.

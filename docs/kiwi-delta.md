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

## Blocked on the base checkout

Everything below needs Chromium **105.0.5195.24** sources to diff against. Until
then the classification cannot be honest, because we cannot tell a modified file
from a copied one.

The procedure, once the base exists:

1. Check out Chromium at exactly `105.0.5195.24`.
2. For each of the 8,313 overlay files, diff against its upstream counterpart.
3. **Discard every file that is byte-identical.** Expectation: most of them.
   Recording the real number is a Stage 2 deliverable in itself.
4. Files with no upstream counterpart are Kiwi's own additions — keep whole.
5. Generate a real patch series into `patches/`, one logical change per patch,
   each with a header saying what it does and why.
6. Classify each patch **keep** / **drop** / **rebase**, in the table below.

### Classification (to be filled in)

| Patch | Area | Class | Rationale |
|---|---|---|---|
| _pending the base checkout_ | | | |

Expected classes:

- **keep** — the extension system, the Android UI changes that make extensions
  usable on a phone. This is what Cobalt is for.
- **drop** — Kiwi branding, telemetry, update-check endpoints, the Edge migration
  README, and anything upstream has since absorbed.
- **rebase** — everything else: real changes that need porting forward across the
  gap.

---

## V8 integration points

Recorded for documentation only. Cobalt ships V8 and is not replacing it; this
section exists so the Stage 12 experiment has a map if it ever runs, and for no
other reason. See [`decisions/0001-keep-v8.md`](decisions/0001-keep-v8.md).

_To be filled in during the diff pass._

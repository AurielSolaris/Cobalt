# Kiwi's patch series

The real difference between Kiwi Browser and stock Chromium, recovered by diffing
Kiwi's overlay against pristine Chromium sources.

## Why this directory exists

Kiwi ships [`src.next`](https://github.com/kiwibrowser/src.next) as a **whole-file
overlay**: 8,297 complete source files copied over a Chromium tree at build time,
with no patches and no record of what changed inside any of them. That form is
unusable for a port — it says *which* files were touched, never *what* was done to
them, and applying it to a newer Chromium would overwrite four years of upstream
fixes, security fixes included.

So the series was reconstructed. `tools/extract-kiwi-delta.sh` compares every
overlay file against its upstream counterpart at the exact base version and emits
a diff for each one that genuinely differs.

## What it found

| | Files | Share |
|---|---:|---:|
| Total in the overlay | 8,297 | |
| **Identical to upstream** | **7,746** | **93%** |
| Modified | 277 | 3% |
| Kiwi-only (no upstream counterpart) | 184 | 2% |
| Binary differs (icons, resources) | 90 | 1% |

**93% of the overlay is upstream code Kiwi merely copied.** The real delta is 551
files — 6.6% of what the tree appeared to contain.

The modified portion is small enough to read:

- **1.1 MB** of patches, **11,220** lines of diff
- **4,136** lines added, **755** removed

That is a genuinely portable change set, and it is the single most encouraging
number in the project so far. Carrying ~4,100 added lines across 35 Chromium
milestones is a real job but an ordinary one.

### Where the changes are

| Area | Modified files |
|---|---:|
| `chrome/browser` | 137 |
| `chrome/android` | 45 |
| `third_party/blink` | 22 |
| `extensions/common` | 8 |
| `ui/webui` | 6 |
| `extensions/browser` | 6 |
| `content/public` | 6 |
| `chrome/renderer` | 6 |
| everything else | ≤3 each |

The largest single patches are `blink/renderer/core/loader/base_fetch_context.cc`
(718 lines), `blink/renderer/core/layout/layout_object.cc` (474), and the Android
UI work in `AppMenuPropertiesDelegateImpl.java` (452), `ChromeActivity.java` (338),
and `ToolbarPhone.java` (317).

**Exactly one patch touches V8.** That is worth stating plainly: Kiwi barely goes
near the JavaScript engine, which independently supports
[`../docs/decisions/0001-keep-v8.md`](../docs/decisions/0001-keep-v8.md).

39 modified files mention extensions — Kiwi's headline feature and the reason
Cobalt exists.

The 184 kiwi-only files are mostly `chrome/android` (161) resources and branding,
plus 13 in `chrome/browser`, Kiwi's `toolbox/` scripts, and its version metadata.
The 90 binary differences are icons and drawables in `chrome/android` (40),
`chrome/browser` (35), and `components/browser_ui` (15) — Stage 4 branding work.

## Layout

```
kiwi-105/            277 patches, one per modified file, against 105.0.5195.24
modified.txt         the 277 files Kiwi actually changed
kiwi-only.txt        the 184 files that exist only in Kiwi
binary-differs.txt   the 90 binary files that differ
modified-by-area.txt modified-file counts per area
summary.txt          raw output of the extraction run
```

Patch filenames are the source path with `/` replaced by `_`. The real path is in
each patch's own header, which is what `patch`/`git apply` reads.

`identical.txt` is deliberately **not** committed — 7,746 lines naming upstream
files we do not care about.

## Reproducing it

```sh
# Clone the overlay on Linux. Cloning on Windows converts it to CRLF, which
# makes every file falsely read as modified; the tool refuses if it detects this.
git -c core.autocrlf=false clone --depth 1 --branch kiwi \
    https://github.com/kiwibrowser/src.next /build/kiwi-overlay
git -C /build/kiwi-overlay checkout 7be7edd1532148f22103cb4c5a1964d96297836f

# Pristine Chromium at Kiwi's exact base. Source only — never built.
git clone --depth 1 --branch 105.0.5195.24 --single-branch \
    https://chromium.googlesource.com/chromium/src.git /build/chromium/src-105

tools/extract-kiwi-delta.sh /build/kiwi-overlay /build/chromium/src-105 /build/delta
```

## Status

These patches are **extracted, not yet classified**. The next step is to sort each
into keep / drop / rebase, and it is tracked in
[`../docs/kiwi-delta.md`](../docs/kiwi-delta.md).

They have also **not been applied to anything**. Stage 5a ports them onto Chromium
140; nothing here has been tested against a newer tree yet.

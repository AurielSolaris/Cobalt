# Security backports

The public record [decision 0016](decisions/0016-pin-chromium-140-and-backport.md)
requires: which CVEs Cobalt has taken, from which upstream commits, in which
release, and what it has not taken and why. Cobalt is built on Chromium 140 and
takes security fixes by backport. This page is how to check that claim.

How fixes are found, adapted and landed is in [`backporting.md`](backporting.md).
Each patch file in `patches/security/` carries its own provenance header.

## 0.4.2 (September 2026)

Seven of the eight 0.4.1 deferrals: the three PowerVR fixes that 0.4.1 put
first in line, and the File System Access, GPU client, Omnibox and DevTools
fixes. All seven are from the same two Chrome releases, found the same way, and
none needed a rebase. Only #19 is left, the one that might.

| Issue | CVE | Severity | Component | Upstream | Backport |
|---|---|---|---|---|---|
| #4 | CVE-2026-87464 | Critical | Use after free in WebGL (ANGLE GL, PowerVR Android) | angle `82f9e7ef4530` (M153), with its prerequisite `a17d5224d83f` (M151) | adapted, prerequisite included |
| #23 | CVE-2026-85050 | High | Out of bounds write in WebGL (ANGLE GL, PowerVR) | angle `7df613367a1d` (M152) | verbatim logic |
| #3 | CVE-2026-87438 | Critical | Out of bounds write in WebGL (ANGLE GL, PowerVR) | angle `fca5efdfeff5` (M153) | adapted |
| #14 | CVE-2026-84354 | High | Incorrect authorization in FileSystem | src `a4775d86854c` + `da77132fcf66` (main, Chrome 152) | adapted, both changes |
| #13 | CVE-2026-84351 | High | Out of bounds write in GPU (GLES2 client) | src `4bb7d52b63ad` (M152) | regenerated from the generator |
| #15 | CVE-2026-84357 | High | Omnibox (document provider) | src `a9d6126968cd` (M152) | verbatim code |
| #17 | CVE-2026-85042 | High | Use after free in DevTools | src `4dfbc081b5e3` (M152) | adapted |

- **#4** extends a workaround, `reattachTextureToFboAfterLayerIncrease`, that
  M140's ANGLE does not have. So the M151 change that introduced it (itself a
  fix, bug 541748549) is carried in the same patch. It touches ANGLE's core
  `Framebuffer` and `Texture` observers, with the new code behind a PowerVR
  Android feature.
- **#3** has a companion change in `gpu/command_buffer/service`, which patches
  the validating decoder; Cobalt never runs that decoder (see #1), so only the
  ANGLE half is taken. Upstream reverted it on M152 for lack of bake time and
  kept it on M153. In Cobalt it is limited to PowerVR, the same as upstream.
- **#14** is the rename check (`FileSystemHandle.move()` now needs write access
  to the parent directory) and the change that enabled it by default, carried
  together with the defaults Chrome shipped. File System Access on local files
  is stable on Android in M140, so any page reaches it.
- **#13** is 40 KB, most of it generated. It was not hand-edited. The
  generator changes were applied, and M140's own generator was run; before
  the change it reproduces M140's checked-in files byte for byte, so its
  output differs only by the fix. The fix clamps every `glGet*` result to
  `GLGetNumValuesReturned(pname)`, so a pname missing from that table would
  come back empty and break WebGL. So every pname M140's clamped getters
  accept was checked against the table, by enum value: M140 leaves out exactly
  the pnames upstream leaves out, which the client answers from its caches
  and which never reach the clamp.
- **#15** is in Chrome's omnibox document provider, which is compiled into
  Cobalt but not driven by Cobalt's address bar. It is taken for completeness.
- **#17** is reachable only with remote debugging on, or through an extension
  using `chrome.debugger`.
- Each ANGLE patch declares its feature at its own place in ANGLE's feature
  lists. The first draft appended each one after the last, and the series'
  "already applied" check, a reverse apply, then failed for every patch whose
  context a later one had changed, 0.4.1's CVE-2026-87488 included. No two
  backports may share diff context; see `tools/patches/series.txt`.

The ANGLE fixes only run on ANGLE's GL backend on PowerVR GPUs. They were
built and smoke-tested on a Mali phone (Galaxy M31), where ANGLE runs on Vulkan,
so that test shows they break nothing there. It does not exercise them. They
have not been run on PowerVR hardware.

The #13 clamp was checked on the same phone with WebGL Report's WebGL 2 page,
which reads every limit through `glGet*`. All of them came back, including the
two-value ones (line width range, viewport dimensions). The WebGL Aquarium ran
at 34 fps.

## 0.4.1 (September 2026)

Source: the 25 issues filed by the security watcher for **Chrome
152.0.7977.75** (1 September) and **153.0.8010.36** (8 September). Every fix
was located on the M152 or M153 release branch by its bug id, read, and checked
against what Cobalt compiles and runs before anything was taken.

### Taken

| Issue | CVE | Severity | Component | Upstream | Backport |
|---|---|---|---|---|---|
| #5 | CVE-2026-87488 | Critical | Use after free in WebGL (ANGLE GL, Mali) | angle `99f12d73e026` (M153) | adapted |
| #7 | CVE-2026-87628 | Critical | Use after free in Cast (Open Screen) | src `6cfbc1f240eb` (M152) | verbatim |
| #8 | CVE-2026-84324 | High | Use after free in Proxy | src `a87859ad7a79` (M152) | adapted |
| #10 | CVE-2026-84326 | High | Uninitialized resource in V8 (Turboshaft) | v8 `32f541982747` (15.2) | adapted, test included |
| #11 | CVE-2026-84333 | High | Use after free in Dawn (Vulkan, PowerVR) | dawn `4178cb7e771b` (M152) | adapted |
| #18 | CVE-2026-85043 | High | Incomplete cleanup in Network (auth) | src `71067ec79dbb` (M152) | adapted |
| #21 | CVE-2026-85048 | High | Use after free in Compositing (viz) | src `1e944c7f193a` (M152) | adapted |
| #22 | CVE-2026-85049 | High | Use after free in Skia | skia `0873ec164a06` (M152) | verbatim |
| #24 | CVE-2026-85051 | High | Type confusion in Compositing (cc) | src `29a203e805e4` (M152) | adapted |
| #25 | CVE-2026-85052 | High | Out of bounds read in CrashReporting | src `4d2a8274815f` (M152) | verbatim |

"Adapted" means the surrounding code differs in M140 and the change was
re-expressed against it. The logic is unchanged in every case, and each patch
file says exactly what differed. Tests are carried where M140's test files
allow.

### Does not apply to Cobalt

Checked against the build, not assumed.

| Issue | CVE | Why |
|---|---|---|
| #1 | CVE-2026-84352 (Critical, WebGL) | The fix is in the **validating** GLES2 decoder. Cobalt sets `is_desktop_android`, which sets `enable_validating_command_decoder = false` (`ui/gl/features.gni`): it always uses the passthrough decoder (ANGLE), so this code never runs. |
| #6 | CVE-2026-87527 (Critical, WebGL) | Same: validating decoder only (`texture_manager.cc`). |
| #2 | CVE-2026-84353 (Critical, Shared Tab Groups) | Tab-group sync UI; not built for Cobalt, which has no sync. No fix commit is visible on the M152/M153 branches either. |
| #9 | CVE-2026-84325 (DataTransfer) | ChromeOS only (ARC VM paths). |
| #12 | CVE-2026-84349 (Browser) | Desktop `BrowserView`; not built on Android. |
| #16 | CVE-2026-84359 (Skia) | Skia Graphite, which this build does not compile (0 objects). |
| #20 | CVE-2026-85046 (V8 type confusion) | The bug is in *inlined* `Array.prototype.sort`. V8 14.0 does not inline sort in either compiler, so the vulnerable code does not exist in M140. |

### Deferred, with the reason

| Issue | CVE | Why not now |
|---|---|---|
| #19 | CVE-2026-85045 (High, V8 Maglev) | **Possible rebase trigger 1.** V8 14.0 represents HeapNumber virtual objects differently: a raw number materialised as one shared literal, which is very likely the same bug. Upstream's fix is written against a representation 14.0 does not have, so carrying it means writing new deoptimisation code with nothing upstream to check it against. 0016: a subtly wrong security fix is worse than a missing one, because it reports as fixed. Needs V8-specialist review before it is either written or declared a rebase trigger. |

PowerVR matters more than it looks: budget MediaTek phones (Helio G35/G37 and
similar) ship PowerVR GPUs, which is exactly Cobalt's 4 GB target class. #3, #4
and #23 were taken in 0.4.2, with #13, #14, #15 and #17; see above. Only #19
is still deferred: the one that may force a rebase.

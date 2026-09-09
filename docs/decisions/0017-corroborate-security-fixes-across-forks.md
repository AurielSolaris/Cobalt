# 0017 — Identify security fixes by corroboration across Chromium forks

**Status:** accepted
**Date:** 2026-09-10
**Refines:** [0016](0016-pin-chromium-140-and-backport.md) — the sourcing half
**Related:** [0011](0011-refuse-mechanical-disablers.md)

## Context

[Decision 0016](0016-pin-chromium-140-and-backport.md) pins Chromium 140 and
backports security fixes, and named its own load-bearing risk:

> The hard part is not applying backports. It is knowing what to backport.

Chromium restricts visibility on security bugs until well after a fix ships. A
strategy that waits for Google to confirm which commit was a security fix is a
strategy that ships known-vulnerable code in the meantime.

Cobalt is not the only project with this problem. **Every Chromium fork that
carries an out-of-tree patch set has to solve it**, and several have been doing
so for years. That is a signal Cobalt can read rather than a wheel to reinvent.

## Decision

**Security fixes are identified by corroboration across independent Chromium
forks, on top of upstream's own public record — and the patches themselves are
taken from upstream Chromium, not from the forks.**

That separation is the whole decision. Forks are a **triage signal**. Upstream is
the **source of code**.

## Why forks are a usable signal

A fork shipping an out-of-band release that does nothing but bump its Chromium
base is making a statement: *that version diff was security-critical enough to
ship immediately*. That is precisely the discriminator this decision needs —
"was this a security fix, or an app-specific change?" — and it is public,
timestamped, and produced by people with no reason to mislead.

Corroboration across several independent forks turns one project's judgement
into a much stronger signal, and a fork that ships a hotfix while others do not
is itself informative.

**The method is already proven in Cobalt's exact niche.** Cromite — an Android
Chromium fork with a large patch series, de-Googling and ad blocking, which is
to say Cobalt's shape — assembles its patch set from *Iridium, Inox, Brave,
ungoogled-chromium and GrapheneOS*. Aggregating across forks is established
practice here, not a novel idea being tried for the first time on Cobalt's
users.

Cromite is also worth reading structurally: a `RELEASE` file pinning the exact
Chromium tag, `bromite_patches_list.txt` as an ordered series applied with
`git am`, and `bromite.gn_args`. That is the same shape as Cobalt's pinned tag,
[`series.txt`](../reproducible-build.md) and `args.gn`, arrived at
independently, which is mild evidence both are right.

## What forks do not provide, stated plainly

**They will not hand us a patch that applies to M140.** Brave, Cromite,
ungoogled-chromium and the rest all track *forward* — they rebase onto newer
Chromium rather than backporting into an older one. Cobalt is the project
choosing to stay put, so the adaptation work is ours regardless.

Expecting otherwise would be the central error available here, so it is written
down: **forks tell us what to look for; they do not do the looking.**

## Upstream's public record comes first

Corroboration is the second layer. The first is that Chromium is open source and
much of this does not require anyone to "admit" anything:

1. **Release branch history.** Merges to a *stable* milestone branch are almost
   exclusively security and stability fixes — that is what the branch is for.
   `git log <tag>..<tag>` between consecutive releases on a branch is therefore a
   high-precision security set. Cobalt has a local Chromium checkout, so this is
   a git command, not an API.
   **Caveat:** M140 is end-of-life, so this stream has stopped *for our base*.
   Cobalt is pinned at `140.0.7339.264`, which
   [0003](0003-staged-chromium-rebase.md) chose as the last M140 patch precisely
   so it carries every fix on that line. Future fixes exist only on later
   branches and must be adapted.
2. **Chrome Releases security sections** — per-release CVE lists with severity,
   affected component and upstream bug ID.
3. **chromiumdash** (`chromiumdash.appspot.com`) — `fetch_milestones` returns
   each milestone's `chromium_branch`, plus per-component branches for `v8`,
   `skia`, `angle` and `webrtc`, and a `schedule_phase` that includes
   `"extended"`. `fetch_releases` gives shipped versions. This is how to find
   which branch carries a given fix, and it is also the reminder that **Chrome
   backports security fixes to every other milestone branch for extended
   stable** — a partially curated stream worth reading, since an extended-stable
   branch is closer to M140 than trunk is.
4. **Dependency advisories** — V8, BoringSSL, ANGLE, Skia, libwebp, FreeType.
   These are DEPS-pinned and do not arrive with Chromium.

## Firefox is a cross-check on dependencies, not on Chromium

Tracking Firefox for Chromium fixes does not work: different engine, different
code. Mozilla's advisories are still worth reading for one narrow purpose —
**shared third-party libraries**. Image and media codecs in particular are used
by both, and a serious bug in one of them is a serious bug for both. `libwebp`
is the standing example.

That is a supplementary feed for the dependency layer, not a source of Chromium
fixes.

## The discipline this inherits from 0011

[Decision 0011](0011-refuse-mechanical-disablers.md) exists because a Kiwi patch
was applied mechanically and turned out to have disabled a security check —
undetected for two builds. The same failure is available here, with more
sources.

Therefore:

- **A patch from a fork is not automatically correct for Cobalt.** Forks encode
  their own product decisions, and much of Brave's or Cromite's series is
  feature work, not security. Only patches reviewed and understood are taken.
- **Where a fix exists upstream, take it from upstream**, adapted to M140 by us.
  A fork's adaptation was made for a different base and a different patch set.
- **A fork's hardening patch may be genuinely additive** — GrapheneOS and Cromite
  carry mitigations upstream does not have. Those are welcome, but they are a
  *feature* decision with its own review, not a security backport, and they are
  recorded as such.
- **Everything lands through the declared series**, never applied by hand to a
  working tree, and the series' existing rules apply unchanged: every step
  asserts, and a partial apply fails loudly.

## Consequences

- The backport process gains a **corroboration step** before the adaptation
  step: check what the tracked forks did with a given upstream release.
- Cobalt takes on a small dependency on other projects' release discipline,
  which is acceptable because it is used as evidence rather than as code, and
  because upstream's own record remains the primary source.
- `docs/backporting.md` — which [0016](0016-pin-chromium-140-and-backport.md)
  called for — writes this up operationally: which projects are tracked, how
  their releases are watched, the `git log` procedure against milestone
  branches, and the applied-fix log.
- The published backport record ([0016](0016-pin-chromium-140-and-backport.md))
  should cite **why** a fix was identified as security-relevant, not only that it
  was applied. Corroboration is only checkable if the corroboration is written
  down.

## Sources

- [uazo/cromite](https://github.com/uazo/cromite) — patch series structure, and
  its aggregation from Iridium, Inox, Brave, ungoogled-chromium and GrapheneOS
- [Chromium Dash](https://chromiumdash.appspot.com/fetch_milestones) —
  milestone, branch and `schedule_phase` data
- [Working with Release Branches](https://www.chromium.org/developers/how-tos/get-the-code/working-with-release-branches/)
- [Chromium Branch Sheriffing](https://chromium.googlesource.com/chromium/src/+/dcd286f70fd2dd92675c8eb935ee8eac218d4b22/docs/branch_sheriff.md)
- [Chrome Release Cycle](https://chromium.googlesource.com/chromium/src.git/+/da2cc0e375fc3bdd096017354ac51cd6ad1408b1/docs/process/release_cycle.md)
  — extended stable and the four-week backport window

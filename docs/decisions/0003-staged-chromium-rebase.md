# 0003 — Rebase to Chromium 140 first, not to current stable

**Status:** accepted
**Date:** 2026-09-07

## Context

Kiwi's Chromium base is **105.0.5195.24**, released 30 August 2022. Current
Chromium stable is **152**, released 3 September 2026. That is 47 milestones and
just over four years.

Stage 5 originally said "choose a target Chromium stable branch", which in
practice meant current stable. One jump of 47 milestones is the single most
likely way for this project to die: every conflict lands at once, nothing builds
for months, and there is no intermediate point at which the work can be shown to
be going well or badly.

## Decision

Stage 5 rebases onto **Chromium 140** (stable 2 September 2025), not current
stable. A second hop to current follows as its own stage, once 140 is green.

## Why 140

- **It is three years from Kiwi's base**, to within days. M105 was 30 August
  2022; M140 was 2 September 2025. That is the largest step that still leaves a
  meaningful remainder rather than being the whole distance.
- **It leaves a 12-milestone second hop** instead of a 47-milestone first one.
  Twelve is a normal-sized rebase; forty-seven is a rewrite wearing a rebase's
  clothes.
- **It gives a real checkpoint.** A build that runs on 140 proves the patch
  series survived porting at all. If it does not, we learn that after three years
  of drift rather than after four, and the second hop is cancelled rather than
  wasted.
- **It de-risks the security story.** M140 is far enough forward that the
  four-year backlog of security fixes is mostly absorbed, so a slip on the second
  hop is not a slip on shipping a browser full of 2022 CVEs.

## Consequences

- Stage 5 splits: **5a** ports the patch series onto M140, **5b** hops M140 →
  current. 5b does not block Stage 6; Blink bring-up can start on 140.
- `UserAgent.CHROMIUM_VERSION` is already `140.0.0.0`, which is the Stage 5a
  target. It stays a stated intention until 5a lands — the constant's own
  documentation says so — and moves to the real number at each hop thereafter.
- **Chrome moved to a two-week release cycle in September 2026.** The plan's
  "quarterly rebase" cadence would now mean falling six milestones behind between
  rebases. `docs/rebasing.md` has to be written against the two-week cadence, and
  the rebase interval revisited in Stage 10 once we know what one actually costs.
- Cobalt will be visibly behind current Chromium for a while. That is worth
  saying out loud in the README rather than discovering in a bug report.

## Rejected alternatives

**Jump straight to current stable (152).** Rejected: all 47 milestones of
conflict arrive simultaneously, with no checkpoint and no way to tell progress
from thrash.

**Apply Kiwi's overlay directly to a new Chromium.** Rejected, and it is worth
being explicit about why: `src.next` is a whole-file overlay with no diffs, so
applying it to a newer tree overwrites four years of upstream changes — security
fixes included — in every file it covers. It would build, and it would be a
browser that had quietly reverted Chromium. See
[`../kiwi-delta.md`](../kiwi-delta.md).

**Stay on 105 and only rebrand.** Rejected: shipping a browser on a four-year-old
engine with four years of unpatched CVEs is worse than not shipping one.

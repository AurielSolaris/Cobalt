# 0016 — Pin Chromium 140 and backport security fixes, rather than track stable

**Status:** accepted
**Date:** 2026-09-10
**Amends:** [0003](0003-staged-chromium-rebase.md) — cancels Stage 5b as a default
**Related:** [0004](0004-performance-budget.md)

## Context

[Decision 0003](0003-staged-chromium-rebase.md) chose M140 as the rebase target
and planned a second hop, **Stage 5b**, from M140 to current stable. It also
recorded a fact that has since changed the arithmetic, and deferred the
conclusion:

> **Chrome moved to a two-week release cycle in September 2026.** The plan's
> "quarterly rebase" cadence would now mean falling six milestones behind between
> rebases. […] the rebase interval revisited in Stage 10 once we know what one
> actually costs.

We now know roughly what one costs, and the revisit cannot wait for Stage 10.

**At a two-week cadence, tracking stable means about 26 rebases a year.**
[`rebase-trial.md`](../rebase-trial.md) measured a 58% conflict rate for Kiwi's
series across three years, and even a well-behaved single-milestone rebase is
days of work against an out-of-tree patch set of this size. Twenty-six of those
per year is not a schedule; it is the entire schedule, permanently, with nothing
left over for the browser itself.

**This is precisely how Kiwi died.** Kiwi did not fail because its ideas were
wrong — its patches still apply, and this project is built on them. It failed
because keeping a large out-of-tree patch set on Chromium's treadmill is more
work than one team can sustain, and when the treadmill won, the project stopped
at M105 and simply rotted. Cobalt exists because of that failure mode and should
not walk into it.

## Decision

**Cobalt stays on Chromium 140 and aggressively backports security fixes from
current stable.** Rebasing to a newer Chromium happens only when a defined
trigger fires, not on a calendar.

Stage 5b — the M140 → current hop — is **cancelled as a default**. It becomes
one possible outcome of the trigger criteria below, not a planned stage.

### What "aggressively backport" means

Every security fix that reaches a component Cobalt actually ships, applied to
M140, on a rolling basis. Not a subset judged severe enough; the default is to
take it and the exception is to skip it with a written reason.

## The precedent: this is how Android ships a kernel

The model is not improvised. **Android runs on Linux kernels years behind
mainline and has done so for its entire life.** Android Common Kernels branch
from upstream LTS, a shipping device typically runs a kernel two to four years
old, and Google moves to a newer base at platform transitions rather than
chasing mainline's release pace. Security is maintained by a continuous
backport stream published monthly as the Android Security Bulletin.

Nobody considers Android insecure for running an old kernel, because *old* and
*unpatched* are different properties, and only the second one matters. A version
number is a proxy for patch level, not a substitute for it.

The mapping to Cobalt is close:

| Android | Cobalt |
|---|---|
| LTS kernel branch | pinned Chromium 140 |
| monthly Security Bulletin | rolling backports from current stable |
| new kernel base at a platform release | rebase on a trigger, not a calendar |
| stable KMI so drivers survive | no major web-platform API break |

**Where the analogy breaks, and it is the part that matters.** Android inherits
a *curated* stream: upstream LTS maintainers do the backporting, and Google
consumes their work. **Chromium publishes no LTS branch and no equivalent
maintainer.** There is no curated feed of "fixes applicable to M140" for anyone
to consume.

So Cobalt has to do the curation itself, and that is the entire difficulty of
this decision. Android's model works because the backport stream is a named,
staffed, published process — not because pinning is inherently safe. Pinning
without that process is not Android; it is Kiwi.

## The load-bearing risk, stated plainly

**The hard part is not applying backports. It is knowing what to backport.**

Chromium restricts visibility on security bugs until well after a fix ships,
typically until most users have updated. From outside Google you frequently
cannot see which commit fixed what, or that a given commit was a security fix at
all. A strategy that assumes "we will just take the security patches" without
solving identification is a strategy that quietly ships known-vulnerable code
while believing otherwise.

That risk is why this decision includes a sourcing method rather than an
intention — and why it has since been given a decision of its own.
**[0017](0017-corroborate-security-fixes-across-forks.md) refines this section**:
security fixes are identified by corroborating across independent Chromium forks
on top of the upstream record below, because every fork carrying an out-of-tree
patch set faces this same problem and several have been solving it for years.
Cromite already assembles its series from Iridium, Inox, Brave,
ungoogled-chromium and GrapheneOS, so the method is established practice in
Cobalt's exact niche rather than an experiment.

### Sourcing, in priority order

1. **Chrome Releases security sections.** Every stable release publishes its CVE
   list with severity, affected component, and the upstream bug ID. This is the
   authoritative feed and it is public.
2. **Milestone branches, not trunk.** Chromium maintains release branches with
   merges back to them. Cherry-picking from the branch nearest M140 that carries
   the fix gives a far cleaner apply than taking it off trunk, where twelve
   milestones of refactoring sit on top.
3. **The public issue tracker, once bugs open.** Visibility is granted in
   batches after the fact; a periodic sweep catches what was invisible when the
   release shipped.
3. **chromiumdash** — `fetch_milestones` gives each milestone's Chromium branch,
   its per-component branches for V8, Skia, ANGLE and WebRTC, and a
   `schedule_phase` including `"extended"`. It is how to find which branch
   carries a fix, and it is the reminder that Chrome backports security fixes to
   every other milestone branch for extended stable — a stream closer to M140
   than trunk is.
4. **DEPS'd third parties directly** — V8, BoringSSL, ANGLE, Skia, libwebp,
   FreeType publish their own advisories, and a meaningful share of Chromium
   CVEs originate there. These are pinned by M140's DEPS and must be tracked as
   separate feeds, not assumed to arrive with Chromium.

### What legitimately reduces the surface

Not every Chromium CVE applies to Cobalt, and saying so is not an excuse to skip
work — it is how the backlog stays finishable.

- **Code we do not compile.** `enable_vr`, `enable_openxr`, `enable_arcore` and
  `enable_cardboard` are all off. Those CVEs do not apply, and this must be
  checked against `args.gn` rather than assumed.
- **Google Play Services**, once [0013](0013-remove-google-play-services.md)
  lands, takes its attack surface with it.
- **uBlock Origin preinstalled** ([0006](0006-bundle-ublock-origin.md)) removes
  a large share of the drive-by delivery path. It mitigates exposure; it does
  not fix a bug, and must never be cited as a reason to skip a backport.

## A side effect worth naming: pinning protects MV2

[Decision 0005](0005-support-mv2-and-mv3.md) records that upstream is removing
MV2 code paths, not merely disabling them, and that past some milestone keeping
MV2 stops being a matter of flipping defaults and becomes carrying restored code
— "a permanent, growing delta of exactly the kind this project is trying to
escape".

Staying on M140 means those paths are simply still there. Cobalt keeps MV2 for
free rather than maintaining a restoration patch that grows with every
milestone. That was not the reason for this decision, but it is a real benefit
of it, and it raises the cost of any future rebase in a way the triggers below
must account for: **whether MV2 survives at a candidate target gates the choice
of target.**

## When we do rebase

The trigger is deliberately concrete, because "when there are major changes" is
the kind of criterion that gets deferred forever by whoever is busy.

Any **one** of these forces a rebase:

1. **A security fix that cannot be backported.** The surrounding architecture
   changed enough that the fix does not exist for M140 in any faithful form.
   This is the most important trigger: it is the point at which the pin stops
   being defensible, and it is a fact rather than a judgement.
2. **A web-platform capability Cobalt's users need** that cannot be backported —
   a feature real sites require, not a nice-to-have.
3. **Site breakage attributable to the version**, past the point where it is
   individual sites rather than a pattern.
4. **A pinned dependency that can no longer be updated in place** — most likely
   V8 or BoringSSL, where the M140 tree stops accepting current releases.
5. **The drift ceiling below.**

### The drift ceiling

**No more than 12 months on one base without a rebase, regardless of the other
triggers.**

> This ceiling is an addition to the decision as originally framed, not something
> that was asked for. The reason: backport fidelity degrades with distance. A fix
> written twelve milestones ahead often has to be hand-adapted, and a
> hand-adapted security fix that is subtly wrong is worse than a missing one,
> because it reports as fixed. Without a ceiling, "rebase only on major changes"
> has no upper bound and is the exact mechanism by which a pin becomes permanent
> — which is what happened to Kiwi at M105.

Twelve months is a starting figure and should be revised once the first year of
backporting shows what it actually costs.

## Honesty requirements

A browser's version number is a security claim, and users read it that way.

- **The README must state the model plainly**: Cobalt is built on Chromium 140
  with security fixes backported, it is not current Chromium, and here is how to
  see what has been applied.
- **The user agent must not lie.** `UserAgent.CHROMIUM_VERSION` is `140.0.0.0`
  and stays truthful to the tree Cobalt is actually built from. Claiming a
  version we are not running would break feature detection and misrepresent the
  security posture at once.
- **A public record of applied backports** — which CVEs, which upstream commits,
  which release. Without it, "we backport aggressively" is unfalsifiable, and an
  unfalsifiable security claim is worth nothing.

## Rejected alternatives

**Track current stable.** Rejected on arithmetic: ~26 rebases a year against
this patch set consumes the whole team and leaves no capacity for Cobalt itself.
This is the failure that killed Kiwi.

**Rebase quarterly.** 0003's original cadence. At two weeks per milestone that is
six milestones of drift between rebases, so it has the cost of tracking stable
and the exposure of pinning, without either one's benefit.

**Pin and backport nothing, shipping M140 as-is.** Rejected outright. It is the
Kiwi outcome with the reasoning written down, and it would make Cobalt actively
unsafe within months.

**Backport only Critical and High severity.** Tempting, and rejected as the
default: severity is assigned for Chrome's threat model and deployment, not
Cobalt's, and Medium-rated bugs chain. Skipping is the exception, and it is
written down when it happens.

## Consequences

- Stage 5b is cancelled as a planned stage; [`roadmap.md`](../roadmap.md) and
  [`branching.md`](../branching.md) change accordingly.
- **Backporting becomes standing work**, not a project — it needs an owner, a
  cadence matched to Chrome's two-week releases, and a place in the release
  checklist.
- `docs/rebasing.md`, which 0003 called for and which does not exist yet, is now
  better written as **`docs/backporting.md`**: the sourcing feeds above, the
  cherry-pick procedure against milestone branches, and the applied-fix log.
- The patch series ([`series.txt`](../reproducible-build.md)) gains a security
  section, and backports go through the same declared, re-runnable pipeline as
  everything else — never applied by hand to a working tree.
- A backport that silently fails to apply is the worst possible outcome, so the
  series' existing rule holds with force here: every step asserts, and a partial
  apply fails loudly.
- Cobalt is visibly behind current Chromium indefinitely, by design. 0003
  already said that needed saying out loud in the README; now it is permanent
  rather than temporary.

# 0004 — Target device: 4 GB RAM, 2 cores

**Status:** accepted
**Date:** 2026-09-07

## Context

Chromium is built and tuned by people holding flagship phones. Its defaults —
process-per-site-instance, aggressive prerendering, a large media cache, no real
tab eviction until memory pressure is already critical — assume headroom that a
budget device does not have. A browser that is merely *acceptable* on 4 GB is
unusable once three tabs and an extension are open.

Cobalt is a revival of a browser whose users largely have older Android hardware.
Optimising for the machine we happen to develop on would quietly abandon them.

## Decision

**The target device is 4 GB of RAM and 2 CPU cores.** This is the machine Cobalt
is built for, not the floor it merely tolerates. Every stage from 0.2.0 onward is
measured against it, and "it is fast on my phone" is not evidence.

## What follows from it

**Memory is the binding constraint, not CPU.** On 4 GB with an Android system
holding 1.5–2 GB, a browser has roughly 1–1.5 GB before the low-memory killer
takes an interest. Every decision below falls out of that number.

- **Tab eviction is a feature, not a failure mode** (Stage 7). Background tabs are
  serialised and their renderers released well before the system is under
  pressure, because a tab that restores in 300 ms is better than a browser the OS
  kills. The tab model is designed for this from the start rather than retrofitted.
- **Process model is a tuning decision, not a default** (Stage 6). Chromium's
  site-isolation defaults are the right security answer and the wrong memory
  answer at this size. Android already lowers the process limit below 2 GB;
  whatever we choose above that has to be measured, and any weakening of site
  isolation has to be written down as a security decision, not slipped in as a
  performance one.
- **Two cores means compositing and raster contend with everything.** Thread
  counts that assume 8 cores make a 2-core device slower, not faster.
- **Prerendering and memory saving are settings, not silent policy.** Both trade
  something the user can feel, so both are theirs to decide:
  - *Memory saver* — evict background tabs. Always / auto / never, with a per-site
    exception list for the tab you cannot afford to lose.
  - *Prerendering and preconnect* — off / on-tap / predictive, defaulting to
    conservative on the target device.

  Each setting says plainly what it costs rather than describing itself as
  "optimisation": memory saver costs a reload when you come back, prerendering
  spends data and battery on a guess. A browser that silently drops the page you
  were reading, with no way to turn that off, has made itself untrustworthy in
  exchange for a benchmark number.
- **Startup is measured cold, on the target, with tabs to restore** — not warm,
  not empty, not on a development machine.

## Consequences

- **Stage 9 gains performance baselines** on real 4 GB / 2-core hardware:
  cold start, memory at 1 / 5 / 10 tabs, and time-to-first-paint on a heavy page.
  These become regression gates, not a one-time report.
- **Stage 11 cannot ship 1.0 without them.** A "performance and memory baseline"
  line already exists there; it now has a specific device attached.
- **Stage 8 has to account for extension memory.** Extensions are Cobalt's reason
  to exist and each one costs a process. The extension limit and its memory
  behaviour on this device is a real question, not a footnote.
- The 0.1.0 shell is already inexpensive — a lazy list over a flattened block
  tree — and should stay that way. But it is not evidence of anything: it renders
  a structural subset with no CSS, no images, and no JavaScript. The real numbers
  start when Blink does.

## What this does not mean

It is not a licence to reimplement engine internals for speed. The rule from
[`0001-keep-v8.md`](0001-keep-v8.md) and `CONTRIBUTING.md` still holds: the engine
is forked, not rewritten. Optimising here means **configuration, defaults, and
the shell we own** — build flags, feature toggles, process limits, eviction
policy — not patches to Blink's internals. A configuration change survives a
rebase; a hand-optimised renderer does not.

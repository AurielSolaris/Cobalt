# 0008 — Making Cobalt fast on a 4 GB, 2-core phone

**Status:** accepted
**Depends on:** [0004](0004-performance-budget.md) — the 4 GB / 2-core target
**Applies from:** Stage 10 (release engineering), except where noted

Performance work that is **not** in the ported patches. Kiwi's patch set
contains almost nothing that makes the browser faster; the speed comes from how
Chromium is configured and built, and from what our own shell does at startup.

## 1. The build configuration is the biggest single lever

We currently build with `is_official_build = false`. That is correct for Stage 3,
which only has to prove the tree compiles, and wrong for anything we ship.

| Argument | Now | Ship | Effect |
|---|---|---|---|
| `is_official_build` | false | **true** | Unlocks ThinLTO and PGO together |
| `chrome_pgo_phase` | 0 | **2** | Gated on `is_official_build` in `pgo.gni` |
| `checkout_pgo_profiles` | False | **True** | `.gclient` var; without it there is no profile to use |
| `symbol_level` | 0 | 0 | Keep. Debug info costs GB per link and buys us nothing |

PGO is the one to want most. Chromium's profiles come from real browsing
workloads, and the layout changes they drive help exactly the cases a slow
device feels: startup, first paint, script execution.

**The honest cost:** an official build is far slower to produce and *much*
hungrier at link time — ThinLTO holds the whole program. On a 10 GB host,
`concurrent_links = 2` will likely need to drop to 1. Expect a substantially
longer wall time than the ~5 hours a bring-up build takes.

So there are **two configurations**, not one: a fast bring-up config for
iterating on patches, and a release config that is slow to build and fast to
run. Conflating them means either slow iteration or shipping an unoptimised
browser.

## 2. `is_high_end_android` is currently wrong for our target

`build/config/chrome_build.gni` defaults it from the CPU:

```gn
is_high_end_android = target_cpu == "arm64" || target_cpu == "x64"
```

We build arm64, so it is **true**, and upstream describes it as:

> Set to true to enable settings for high end Android devices, typically
> **enhancing speed at the expense of resources such as binary sizes and
> memory.**

That is precisely backwards for a browser aimed at 4 GB devices. arm64 is a
statement about the instruction set, not about how much RAM the phone has —
plenty of 4 GB phones are arm64, which is the entire target.

**Set `is_high_end_android = false` explicitly** and measure both ways. It is
referenced from the compiler config, the toolchain, and PGO selection, so the
effect is broad and needs numbers rather than assumption.

## 3. Already correct: V8 pointer compression

`v8_enable_pointer_compression` resolves to true on arm64 by default, which
roughly halves the size of V8 heap pointers. On a memory-constrained device
that is one of the largest single wins available, and we get it for free.

Worth stating because the tempting nearby knob is a trap: **`v8_enable_lite_mode`
must stay off.** It cuts V8's memory further by disabling the optimising tiers,
which makes JavaScript dramatically slower. On a slow device that is the wrong
trade — the pages that hurt are the script-heavy ones.

## 4. Runtime: the process model is where the memory goes

Each renderer costs tens of megabytes before it renders anything. On 4 GB the
process model matters more than any compiler flag.

- **Cap renderer processes.** Chromium will happily spawn one per site.
- **Evict background tabs early**, per [0004](0004-performance-budget.md).
  Already Stage 7 work; it is a performance feature, not a memory-safety
  afterthought.
- **Site isolation is a real cost and a real protection.** Full site isolation
  costs meaningful memory; Chrome on low-memory Android reduces it rather than
  disabling it. Any reduction here is a **security trade** and gets recorded as
  one in Stage 9's threat model — never made quietly for a benchmark number.

## 5. Our own shell: startup is ours to lose

The engine is Chromium's problem; the first second belongs to us.

- **Baseline Profiles** (`androidx.profileinstaller`). Compose is interpreted
  until JIT warms up; a shipped profile AOT-compiles the startup path and
  typically takes 20–30% off cold start. This is the single best return in the
  Android half of the project.
- **R8 in full mode**, with the shrinker actually verified rather than assumed.
- **Nothing heavy in `Application.onCreate`.** Every initialiser there is on the
  critical path to first frame.

## 6. Measure before optimising, and keep the numbers

None of the above is worth acting on without a baseline, and [0004] already
commits to one: cold start, memory at 1/5/10 tabs, and time-to-first-paint on a
heavy page, taken on real 4 GB hardware.

Add **Speedometer** and **Jetstream** for the engine side, so a regression from
a rebase is visible as a number rather than a feeling.

The order is deliberate: baseline first, then `is_high_end_android=false`, then
the official build. Turning on three things at once and measuring afterwards
tells you the total and nothing about which one paid.

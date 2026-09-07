# Acceptance: what has to work before Cobalt is pushed

Nothing goes to GitHub until an APK has been built, patched, exercised, and
confirmed. This file says what "exercised" means, written **before** the APK
exists so it cannot quietly become "seems fine".

Two gates. Gate A is the unpatched M140 build — it proves the toolchain, not
Cobalt. Gate B is the patched build — it proves the port.

## Gate A — unpatched M140

The baseline. Failures here are build-environment problems, not porting problems,
and must not be confused with them.

- [ ] `out/Default/apks/ChromePublic.apk` exists and is a plausible size (>100 MB).
- [ ] Installs on the test device (`RZ8R81K1NBP`) via `adb install`.
- [ ] Launches without crashing.
- [ ] Loads a heavy real page over HTTPS and renders it correctly.
- [ ] Scrolling, pinch-zoom, and back/forward work.
- [ ] Survives rotation and a background/foreground cycle.

If Gate A fails, no patch is at fault. Fix the build first.

## Gate B — patched, batch 1 + MV2

The port's first real proof point.

**Build**
- [ ] Applies batch 1 and still compiles.
- [ ] `tools/cobalt-mv2-defaults.py --check` exits 0 (all four flags correct).

**MV2 is genuinely supported, not tolerated**
- [ ] An MV2 extension installs from a `.crx`.
- [ ] It stays enabled after a browser restart — not auto-disabled.
- [ ] **No deprecation warning appears anywhere.** `MV2ExperimentStage` is
      `kNone`; if a warning shows, a flag is wrong.
- [ ] An MV3 extension also installs and runs. Both, not either.

**The thing MV2 exists for**
- [ ] uBlock Origin installs and loads.
- [ ] A `webRequestBlocking` listener actually **fires and blocks** — verified by
      a request that should not complete failing to complete, not by the
      extension merely appearing in the list.
- [ ] Ads are visibly absent on a page known to carry them.

**Bundled-extension semantics** (once Stage 8 lands the bundling)
- [ ] uBO appears preinstalled on a clean profile.
- [ ] It **can be disabled**, and disabling actually stops the blocking.
- [ ] It **cannot be uninstalled** — no Remove button.

**Regression against Gate A**
- [ ] Everything in Gate A still passes on the patched build.

## Reporting

Record the result against each line — pass, fail, or not-run. A checklist with
unexplained blanks is not a passed gate, and "it launched" is not a test.

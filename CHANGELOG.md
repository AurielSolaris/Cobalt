# Changelog

All notable changes to Cobalt are recorded here. Every change is logged as it lands,
not reconstructed at release time.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions
follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Stage 3 — upstream build (complete, 0.3.0)
- **Chromium 140.0.7339.264 builds from source and runs on a device.** 303 MB APK,
  209 MB `libchrome.so`, verified as a real artifact rather than by exit code.
  Loads pages over HTTPS, renders correctly, scrolls, navigates, survives rotation
  and a background cycle with zero crashes — `docs/gate-a-results.md`.
- Build configuration tuned to the host rather than to defaults. `autoninja` sizes
  `-j` from CPU count and ignores memory; at 16 jobs against a 10 GB cap the build
  exhausted RAM and all 16 GB of swap and thrashed at 4 objects/min, which reads as
  slowness rather than misconfiguration. `-j 6` is the working figure.
- Build tree moved from an external SSD to the internal disk after that drive failed
  four times: write errors forcing a read-only remount, objects corrupted in a way a
  clean `e2fsck` does not detect, then unreadable source files. All three failure
  modes and the repair procedure are in `docs/build.md`.
- ccache configured for subsequent builds; not enabled retroactively, since
  `cc_wrapper` changes every compile command and would have invalidated the tree.
- Decisions recorded: MV2 support (0005), bundled uBlock Origin (0006), user themes
  (0007), performance strategy (0008), page diagnostics (0009).
- Cobalt has its own mark — inverted from Kiwi's, attributed under BSD 3-Clause.
- `tools/` reorganised into build/, monitor/, disk/, patches/, device/, assets/.

### Stage 2 — classification (completes 0.2.0)
- Classified all 277 patches — `docs/patch-classification.md`. **About a third of
  the diff is dropped, and it is the third hardest to rebase.** Three patches
  (1,208 added lines, 25% of the delta) implement ad blocking by hand inside
  Blink: a 700-line chain of URL substring matches in the fetch path, and
  element-hiding by literal DOM id during layout. Both are 2022-specific, have no
  update mechanism, and sit in the Blink files most likely to be refactored
  upstream. Extensions do this properly, which is the point of the project.
- 44 patches / 1,248 lines are extension work and are kept whatever the cost.
- Flagged 76 patches that disable upstream code with `#if 0`, `&& 0`, or `|| true`
  rather than by configuration; each needs its intent recovered as a real flag.
- Mapped Kiwi onto Cobalt's structure — `docs/module-map.md`. No Kiwi patch lands
  in `modules/core` or `modules/engine`, and the 45 Android UI patches are read as
  a specification for which Chromium surfaces to call rather than ported as code,
  since Cobalt's shell is already written in Compose.

### Stage 2 — reference pinning (in progress)

- Pinned the Kiwi reference to `7be7edd1532148f22103cb4c5a1964d96297836f`
  (2025-04-08, `kiwibrowser/src.next`, branch `kiwi`), with the fetch command
  recorded in `docs/kiwi-delta.md`. The project was archived in January 2025, so
  this pin never needs to move.
- Recorded the version gap: Kiwi is on Chromium **105.0.5195.24** (Aug 2022);
  current stable is **152** (Sep 2026). Forty-seven milestones, four years.
- Noted that Kiwi's `VERSION` file (93) is stale and contradicts `CHROMIUM_VERSION`
  and `KIWI_VERSION` (105). The real base is 105.
- Inventoried the overlay in `docs/kiwi-delta.md`: 8,313 files by area, type, and
  Android path; 1,299 touch extensions; only 21 name "kiwi".
- **Found that `src.next` is a whole-file overlay, not a patch series** — zero
  `.patch` files, every file complete. It says which files Kiwi touched, never what
  changed inside them, and applying it to a newer Chromium would overwrite four
  years of upstream fixes including security fixes. Recovering real diffs, by
  checking out Chromium 105 and comparing, is the rest of Stage 2 and now blocks
  Stage 5.

### Added
- Stage 0 groundwork: `README.md`, `CONTRIBUTING.md`, this changelog, `docs/build.md`,
  `docs/branching.md`, and the `docs/decisions/` log.
- CI: assemble and unit-test on every pull request into `nightly` and `stable`.
- Branch model: `nightly` (integration) and `stable` (release), documented in
  `docs/branching.md`.
- Stage 1 chassis: URL normalization and resolution, HTTP fetching with typed errors,
  content-type and charset handling, a tolerant HTML tokenizer and tree builder, and a
  Compose renderer covering a structural subset of HTML.
- Browser shell: bottom navigation (Home, Extensions, Tabs, Downloads), an address bar
  with a scheme indicator and overflow menu, an in-memory tab list, a tab switcher, and
  incognito tabs. Design rationale in `docs/decisions/0002-shell-design.md`.
- One Dark theme with a blue accent, near-square corners, and three bundled variable
  fonts: Open Sans (interface), EB Garamond (display and document headings), and
  JetBrains Mono (code).
- Cobalt reports a Chromium user agent with a `Cobalt/<version>` product appended,
  in one shared place (`UserAgent`), rather than a novel token that earns bot
  challenges and fallback pages.
- `settings.gradle.kts` declares plugin and dependency repositories; the build could
  not resolve anything without them.
- `.gitattributes` normalizes line endings and keeps `gradlew` LF and executable.

### Changed
- **Stage 5 splits into two hops**: M105 to M140, then M140 to current, rather than
  one 47-milestone leap. M140 is three years on from Kiwi's base to within days and
  leaves a 12-milestone remainder. See `docs/decisions/0003-staged-chromium-rebase.md`.
- **Target device is 4 GB RAM and 2 cores** — the machine Cobalt is built for, not a
  floor it tolerates. Memory is the binding constraint at that size, so tab eviction
  is designed into Stage 7 rather than retrofitted, and the process model becomes a
  measured decision instead of an inherited default. Optimising means configuration
  and the shell we own, never engine internals: a config change survives a rebase.
  See `docs/decisions/0004-performance-budget.md`.
- **Memory saving and prerendering are user settings**, not silent policy. Both
  trade something the user can feel, so both say what they cost and offer a choice.
- **Per-version branches** from 0.2.0 to 1.0.0: each milestone keeps a
  `nightly-0.x.0` and a `stable-0.x.0`, never deleted, so any point in the project's
  history stays somewhere a fix can land rather than merely a tag to look at.
- Nightly build tags move from `nightly-<YYYYMMDD>` to `nightly/<YYYYMMDD>`, so they
  do not read like the per-version branches.
- Noted that Chrome moved to a two-week release cycle in September 2026, which makes
  the plan's "quarterly rebase" cadence six milestones of drift per run. The interval
  is set from what one hop actually costs.
- Project plan restructured into twelve stages. The Chromium port, not the JavaScript
  engine, is now the spine of the roadmap.
- Cobalt keeps V8. Replacing it with JavaScriptCore is demoted to Stage 12: post-1.0,
  on `experiment/jsc`, behind a build flag that defaults to off. See
  `docs/decisions/0001-keep-v8.md`.
- `NOTICE` corrected — it previously stated that Cobalt replaces V8 with
  JavaScriptCore — and given a V8 notice and a licence-stacking note.

### Fixed
- The renderer flattened whole pages into a single paragraph: `html` and `body` were
  missing from its block-element set, so the document root was treated as an inline
  run and every heading, list, and rule was concatenated into one line.
- The address bar kept focus after a navigation, leaving the caret blinking over the
  loaded URL and the keyboard up, so the first tap on a page dismissed a keyboard
  instead of following a link.

### Removed
- `JsEngine.isJsc`. A flag naming one candidate backend is precisely the
  engine-specific leak the interface exists to prevent; backends identify themselves
  through `name`.

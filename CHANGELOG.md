# Changelog

All notable changes to Cobalt are recorded here. Every change is logged as it lands,
not reconstructed at release time.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions
follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

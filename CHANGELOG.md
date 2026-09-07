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

### Changed
- Project plan restructured into twelve stages. The Chromium port, not the JavaScript
  engine, is now the spine of the roadmap.
- Cobalt keeps V8. Replacing it with JavaScriptCore is demoted to Stage 12: post-1.0,
  on `experiment/jsc`, behind a build flag that defaults to off. See
  `docs/decisions/0001-keep-v8.md`.
- `NOTICE` corrected — it previously stated that Cobalt replaces V8 with
  JavaScriptCore — and given a V8 notice and a licence-stacking note.

### Removed
- `JsEngine.isJsc`. A flag naming one candidate backend is precisely the
  engine-specific leak the interface exists to prevent; backends identify themselves
  through `name`.

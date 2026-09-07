# Contributing to Cobalt

## Before anything else

Cobalt is a fork, a port, and a rewrite in different places, and knowing which one you
are standing in determines what a good patch looks like:

- **The engine is forked.** Blink, V8, the network stack, the extension system. The
  default answer to "should I implement this?" is "does Chromium already do it?" — and
  if it does, we use theirs. Patches that reimplement engine functionality will be
  declined regardless of quality.
- **Kiwi's patch set is ported.** Carried onto a current Chromium as an ordered
  series. Changes here are surgical and each one is justified in its patch header.
- **The Android shell is rewritten.** UI, address bar, tabs, settings — Kotlin and
  Compose, written fresh. This is the part we own outright, and normal design
  judgement applies.

Two standing rules follow from that:

- **Do not add features to the 0.1.0 Compose renderer.** It is a bring-up shim with a
  scheduled deletion date (Stage 6). If a bug in it needs CSS or layout to fix, the
  answer is WONTFIX.
- **Do not work on the JavaScript engine swap.** V8 stays. JavaScriptCore is a
  post-1.0 experiment with written entry conditions; see
  `docs/decisions/0001-keep-v8.md`.

## Branches

`nightly` is the integration line; `stable` is the release line. Full rules are in
[`docs/branching.md`](docs/branching.md). The short version:

```sh
git switch nightly
git switch -c feat/1-html-parser
# work
git push -u origin feat/1-html-parser   # then open a PR into nightly
```

Never commit directly to `stable`.

Branch naming: `feat/<stage>-<slug>`, `fix/<slug>`, `docs/<slug>`,
`experiment/<slug>`.

## Before you open a pull request

```sh
./gradlew check
```

- Unit tests cover the change. Parsers and URL handling especially — those are where
  malformed real-world input arrives.
- `CHANGELOG.md` has an entry under `## Unreleased`.
- A decision that closes off an alternative gets a file in `docs/decisions/`,
  including what was rejected and why.

## Commits

One logical change per commit. Present tense, imperative subject, wrapped at 72
columns, and a body that explains *why* when the reason is not obvious from the diff.

```
Add tolerant HTML tree builder

Real-world markup nests badly and closes tags out of order. The builder
keeps an open-element stack and never throws, so a malformed page still
renders something rather than an error screen.
```

## Code

- Kotlin official style; the Gradle build enforces it.
- Shared code lives in `commonMain` and must not reach for JVM APIs. Platform
  behaviour goes behind an interface implemented in `androidMain`, not an `expect`
  declaration — the `linuxX64` and `mingwX64` targets exist to catch exactly this.
- Public types carry KDoc explaining their role, not restating their signature.

## Licensing

Cobalt is GPL-3.0. By contributing you agree your work is licensed under it. Anything
copied from an upstream project keeps its original notice and is recorded in `NOTICE`.

## Contact

Debaditya Malakar — <debadityamalakar@gmail.com>

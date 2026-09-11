# 0018 — Build the shell first; remove GMS when it gets in the way

**Status:** accepted
**Date:** 2026-09-11
**Supersedes:** the ordering in [0014](0014-gms-removal-before-shell.md)
**Depends on:** [0013](0013-remove-google-play-services.md)

## Context

0014 put GMS removal ahead of the shell. Since then the shell has moved from
"proven by a dependency graph" to running code: Chromium's browser process
starts from Cobalt's Gradle app and a `WebContents` renders a real page into a
Compose window ([`shell-integration.md`](../shell-integration.md)). The shell
work has momentum and a clear next step; the GMS work has an inventory.

## Decision

**The shell continues.** GMS is removed in either of two cases:

1. **On encounter** — when a GMS dependency blocks or breaks shell work, that
   dependency comes out then, as part of the change that hit it. The
   `GooglePlayServicesMissingManifestValueException` on the way to the first
   rendered page is the model: found by running, fixed where it was found.
2. **After the shell is done** — whatever GMS remains is removed as the next
   piece of work, following 0014's order (password store, `components/` and
   `services/`, `chrome/browser`, then whatever of `chrome/android` still
   exists).

## What does not change

- 0013 stands: GMS removal is still a `stable` gate.
- The telemetry notice still goes **with** the code that reports, not before
  it. It stays on screen longer; that was 0014's cost of shell-first and it is
  accepted.
- 0014's measurement — 3 of the GMS-referencing `BUILD.gn` files are in the
  layer the shell replaces — still means little work is wasted in either order.

## Consequences

- Passwords stay unavailable ([0015](0015-passwords-local-store-and-system-autofill.md))
  until the shell is done, unless the shell needs them sooner.
- Every GMS removal made "on encounter" is recorded in
  [`gms-removal.md`](../gms-removal.md), so the post-shell pass starts from an
  accurate table.

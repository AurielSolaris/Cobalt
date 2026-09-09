# 0014 — Remove Google Play Services before building Cobalt's shell

**Status:** accepted
**Date:** 2026-09-10
**Depends on:** [0013](0013-remove-google-play-services.md), [0002](0002-shell-design.md)

## Context

Two of the three gates on a `stable` release ([`branching.md`](../branching.md))
remained open once uBlock Origin landed: removing Google Play Services, and
replacing Chromium's Android UI with Cobalt's own Compose shell. They are the
two largest pieces of work left, and the order was not obvious.

The intuitive argument was **shell first**: most of the GMS coupling is Android
Java, the shell replaces the Android Java, therefore removing GMS first means
doing work that the shell throws away.

**That argument is wrong, and measuring it is what settled this decision.**
Counting the `BUILD.gn` files that reference `google_play_services`:

| Area | Files |
|---|---:|
| `chrome/browser` | 7 |
| `third_party` (android_deps, androidx, cardboard) | 7 |
| `components/*` — gcm_driver, signin, omnibox, webauthn, media_router, module_installer, externalauth | 7 |
| `services/*` — shape_detection, device | 3 |
| `content`, `device` | 4 |
| `chrome/test` | 1 |
| **`chrome/android` — the layer the shell replaces** | **3** |
| Total | 32 |

**Three of thirty-two.** Roughly a tenth of the GMS surface sits in the UI layer.
Everything else is below the shell and survives a shell rewrite untouched. The
two jobs are very nearly independent, so almost nothing is wasted whichever
order they run in — which means the order has to be decided on other grounds.

## Decision

**GMS removal comes first.** The shell follows it.

### Why

**It finishes.** [Decision 0013](0013-remove-google-play-services.md) already
inventories the work. It closes gate 2 of 3 and can be declared done. The shell
is the longest of the three gates by a wide margin and does not close anything
until it is complete.

**Passwords are broken right now.** `IsPasswordManagerAvailable` returns false
before it ever reaches a GMS version check, because `isBackendPresent()` is
hardcoded false without the internal backend. Cobalt today cannot save a single
password. Shell-first leaves that broken for however long the shell takes. See
[0015](0015-passwords-local-store-and-system-autofill.md).

**The telemetry notice is currently a contradiction.** A browser whose bundled
content blocker exists to stop Google's tracking tells users on first run that
it "sends usage and crash data to Google". The text goes with the code
(0013), so shell-first keeps that on screen through the longest phase.

**It makes the shell easier.** Less Java in the way, and a shell built on a tree
with no Google dependencies cannot quietly acquire new ones.

### Why not risk-first

This project orders by risk, not cost — that is why the extension batch went
first in Stage 5a. The precedent does not apply here. That ordering is for
**viability** risk, where a bad outcome ends the project; extensions in week one
genuinely answered "can Cobalt exist". The shell's risk is **schedule** risk:
`content_shell_apk` proves the content layer is embeddable on Android, so the
question is how long, not whether. Large is not the same as uncertain.

## Order of work

1. **The password store** ([0015](0015-passwords-local-store-and-system-autofill.md))
   — the live functional hole, pure browser layer, survives the shell.
2. **`components/` and `services/`** — gcm_driver, signin, omnibox, webauthn,
   media_router, module_installer, externalauth, shape_detection, device.
3. **`chrome/browser`** — the seven files.
4. **`chrome/android`** — last, and partly moot by then.
5. **The telemetry notice and its endpoints** — removed with the code, not
   before it. The notice stays honest until the behaviour changes.

Safe Browsing stays throughout: it is an HTTPS service, not a GMS library
(0013).

## One thing to check before the shell starts

**Does `chrome://extensions` depend on `chrome/android`?**

This is the shell's one genuine viability risk. If the extension UI cannot be
reached from Cobalt's own shell, that changes the plan rather than the schedule,
and it is the sort of thing better learned early. A `gn path` spike answers it
in under an hour and needs no build.

## Consequences

- `stable` stays dormant until gates 2 and 3 close; gate 1 is met.
- Work on the shell is deferred, so the app ships Chromium's interface with
  Cobalt's branding for longer. [`README.md`](../../README.md) says so plainly
  rather than implying the shell exists.
- Every GMS removal is durable: none of it is repeated when the shell lands.

# 0013 — Remove Google Play Services entirely

**Status:** accepted
**Date:** 2026-09-09

## Context

Cobalt targets devices without Google Play Services and does not want to depend
on it. That is a product position, not a compatibility accident: a browser whose
selling point is user control should not require a proprietary layer from the
company whose tracking its bundled content blocker exists to stop.

Chromium's Android build assumes GMS in more places than the phrase "Play
Services dependency" suggests. `chrome/android/BUILD.gn` alone carries **14**
`google_play_services_*` dependencies, and **32** `BUILD.gn` files across the
tree reference them. The modules pulled in include:

```
auth_base   base   basement   cast_framework   cast
gcm   iid   tasks   vision_common   vision
```

These are not one feature. They are sign-in, push messaging, casting, barcode
and face detection, and the shared plumbing beneath them.

[Decision 0012](0012-own-password-store.md) found the sharpest edge: the Android
password manager is not merely degraded without GMS, it is **disabled**, and it
would be disabled even with GMS present because it also gates on a closed-source
internal backend. The device confirms it in Settings — *"Google Password Manager:
Stopped working on this device."*

## Decision

GMS comes out of the build entirely, and every feature that depended on it is
either reimplemented locally, replaced with an open alternative, or dropped with
that stated plainly.

This is a **release gate**, not a nice-to-have: no promotion to `stable` happens
while any GMS dependency remains. See [branching](../branching.md).

## Shape of the work

Not yet started. Recorded now because it is large, it interacts with almost
everything else, and the reasoning is easy to lose once the work fragments.

| Area | What GMS did | Cobalt's answer |
|---|---|---|
| Passwords | Unified Password Manager storage | Own store over `LoginDatabase` — [decision 0012](0012-own-password-store.md) |
| Sign-in / sync | Account auth | Dropped. Cobalt has no account, and sync is not a 1.0 goal |
| Push (GCM/IID) | Web Push delivery | Open question. Web Push without GMS needs its own transport, or the feature is dropped and said so |
| Cast | Casting to devices | Dropped, unless an open receiver path proves cheap |
| Vision | Barcode / face detection | Dropped. Not a browser feature |
| Safe Browsing | Reputation lookups | Keep — it is a Google *service* over HTTPS, not a GMS *library*, and works without Play Services |

Safe Browsing is the one worth stating explicitly, because "remove Google" and
"remove GMS" are different goals and conflating them would cost real user
protection for no packaging benefit.

## The telemetry string is left alone, deliberately

The first-run screen currently reads:

> the app, Cobalt sends usage and crash data to Google

After the string rebrand that sentence is **false**, which is worse than it was
when it said Chrome. It stays anyway, for now.

Removing it is not a string edit. The sentence describes real behaviour —
metrics and crash reporting wired to Google endpoints — and deleting the notice
while the behaviour remains would turn a wrong disclosure into a missing one.
The text goes when the reporting goes, and both happen as part of this decision's
work rather than ahead of it.

**Cobalt will eventually have basic telemetry of its own.** It is for the
maintainer's own reference, it reports to Cobalt's own endpoint and never to
Google, and it is a long way out — well after the removal here. It is recorded
now only so that "remove GMS" is not later misread as "Cobalt must never have
any telemetry".

Do not delete the notice as a branding fix. It is accurate about *something*
happening, and until the endpoints change, that is the more useful failure.

## Consequences

- **Web Push is the hard one.** It is a genuine web platform feature, and without
  GMS there is no delivery channel on Android. Either Cobalt ships its own, or
  the feature does not exist and the docs say so rather than letting it fail
  silently.
- The APK gets smaller and the permission surface shrinks.
- Divergence from upstream grows in a subsystem upstream keeps changing, so this
  carries permanent rebase cost — the same category as the extension work.
- Builds become reproducible without a Play Services SDK in the checkout.

# 0015 — Passwords: Chromium's local store plus system autofill, and nothing bespoke

**Status:** accepted
**Date:** 2026-09-10
**Supersedes the framing of:** [0012](0012-own-password-store.md)
**Depends on:** [0013](0013-remove-google-play-services.md), [0014](0014-gms-removal-before-shell.md)

## Context

[Decision 0012](0012-own-password-store.md) established that Cobalt has no
password manager at all: `IsPasswordManagerAvailable` returns false before it
reaches any GMS version check, because `isBackendPresent()` is hardcoded false
without Google's internal backend. Passwords do not degrade on Cobalt — they are
switched off.

0012 called the answer "ship our own password store", which invited the question
this decision settles: build one, or lean on 1Password, Proton Pass, Bitwarden
and the like?

**Neither, as posed.** The question contained a false premise, and finding it
changed the answer.

### There is nothing to build

```gn
# components/password_manager/core/browser/buildflags.gni:12
use_login_database_as_backend = !is_android
```

Chromium's password store is complete and cross-platform. It is disabled on
Android by **one GN argument**, because Google routes Android through the
Unified Password Manager and GMS instead. Turning it on gives Cobalt the same
`LoginDatabase` that Chrome desktop ships to a billion people.

"Build our own password manager" would mean writing credential storage from
scratch — the highest-consequence code in a browser — while a reviewed,
widely-deployed implementation sits in the tree behind a flag.

### The third-party path is already plumbed too

```
kAutofillUsingVirtualViewStructure
kAutofillThirdPartyPasswordManagersAllowed
```

`chrome/browser/ui/autofill/autofill_client_provider.cc` uses these to hand form
filling to the **Android Autofill Framework** — API 26+, pure AOSP, **no GMS**.
That is precisely how 1Password, Proton Pass and Bitwarden's apps fill on
Android today.

So both options are close to free, and they were never alternatives.

## The real tradeoff: no sync

Chromium's password sync runs through Google account sync, which
[0013](0013-remove-google-play-services.md) removes. Cobalt's built-in store is
therefore a **local-only vault**: no cross-device sync, no passkey ecosystem, no
breach monitoring.

For many people that is not a password manager, it is a convenience cache. That
is the whole reason third-party support has to be a first-class choice rather
than a fallback.

## Decision

Cobalt ships **both**, with distinct roles, and **writes no credential code of
its own**.

### 1. Chromium's local store, on by default

Enable `use_login_database_as_backend` on Android, and neutralise
`MaybeDeleteLoginDatabases` — it exists to delete the local database after UPM
migration and would eat users' passwords on a build that never had UPM.

Same reasoning as [bundling uBlock Origin](0006-bundle-ublock-origin.md): a
browser that remembers nothing out of the box is a worse browser.

### 2. CSV import and export

The local store is local. Import and export are the entire portability story, so
they are a requirement rather than a nicety: they are how a user arrives from
another browser, how they leave, and how they back up. Chromium already
implements both in `components/password_manager`; Cobalt surfaces them.

### 3. System autofill as a first-class choice

Wire `kAutofillUsingVirtualViewStructure` and
`kAutofillThirdPartyPasswordManagersAllowed`, and present them in settings as a
real option — "use another password manager" — not a buried flag. This is the
honest answer for anyone who needs sync, and it is the Android Autofill
Framework, so it survives GMS removal intact.

### 4. Nothing bespoke, ever

No Cobalt vault format, no Cobalt crypto, no Cobalt sync service. Shipping our
own sync would mean owning a server and a breach surface, and there is no
version of that Cobalt is better at than the people who do it full time.

### 5. No Credential Manager below Android 14

`androidx.credentials` is backed by GMS below Android 14. Using it there would
reintroduce exactly the dependency 0013 removes. The Autofill Framework is the
AOSP-clean route and it is the one Cobalt takes.

## Extensions are a third route, for free

Cobalt is the only Android browser that runs desktop extensions, and Bitwarden
and Proton Pass both ship standalone browser extensions. Those should simply
work. 1Password's extension expects a desktop companion app, so it probably will
not — **to be tested rather than promised**, and not counted on in either case.

## The unknown, stated rather than assumed

`use_login_database_as_backend = true` on Android is a configuration **upstream
does not test**, and Chrome Android's password *settings* UI routes through GMS.
The store will work; the settings screen may not, and the size of that gap is
unmeasured.

So the first task is a spike, not an implementation: turn the flag on, build, and
find out what the settings surface actually does. Half a day. Committing to this
as the default before measuring that would be guessing.

## Consequences

- Passwords work again out of the box, using code Cobalt does not maintain.
- Cobalt's vault is local-only, and the UI must say so plainly rather than let
  users assume sync.
- CSV import/export becomes a release requirement, not a backlog item.
- Settings gains a genuine choice between the built-in store and a system
  provider.
- `MaybeDeleteLoginDatabases` is a data-loss hazard and needs a regression test
  guarding it, because a future rebase could quietly restore it.
- This is the first piece of [0013](0013-remove-google-play-services.md), per
  [0014](0014-gms-removal-before-shell.md), and none of it is repeated when the
  shell lands.

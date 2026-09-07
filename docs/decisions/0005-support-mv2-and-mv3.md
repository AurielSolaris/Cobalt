# 0005 — Support both Manifest V2 and Manifest V3 extensions

**Status:** accepted
**Applies from:** Stage 8 (extensions), constrains Stage 5 (rebase)

## Decision

Cobalt supports **both** MV2 and MV3 extensions. MV2 is not removed, not
deprecated, and not gated behind a warning the user has to dismiss.

## Why

MV2 is the only manifest version that grants `webRequestBlocking` — a
synchronous, blocking view of network requests. MV3 replaces it with
`declarativeNetRequest`, which is a static rule list evaluated by the browser.
The distinction is not academic: a blocking extension can make decisions with
full request context, and a declarative one cannot. Content blockers, request
rewriters, and anything that needs to reason about a request before it leaves
the device are strictly less capable under MV3.

Kiwi's reason for existing was extension support on Android. Shipping that with
the more capable half removed would be a downgrade wearing a revival's clothes.

Users who want MV3-only can simply install MV3 extensions. Users who want MV2
have nowhere else to go on Android. The asymmetry decides it.

## What this costs, measured against M140

Upstream is removing MV2 by **feature flag**, not by deletion. M140 contains:

- `kExtensionManifestV2DeprecationWarning`
- `kExtensionManifestV2Unsupported`
- `kExtensionManifestV2ExceptionList`
- `kExtensionManifestV2Disabled`

in `extensions/common/extension_features.cc`, plus
`manifest_v2_experiment_manager.h` and `mv2_experiment_stage.h`.

The implementation beneath them is intact: 53 files under `extensions/` still
reference the MV2 background-page model, `extensions/common/manifest.cc` still
parses `manifest_version: 2`, and `webRequestBlocking` is still listed in
`chrome/common/extensions/api/_permission_features.json`.

So on M140 this decision costs us **a default-off flag configuration**, not a
code fork. That is close to free.

## The cost is in the future, not now

Flag-gating is the step before deletion. On some later Chromium the MV2 code
paths will be removed outright, and from that release onward keeping MV2 means
carrying restored code ourselves — a permanent, growing delta of exactly the
kind this project is trying to escape.

This is a real argument for **not** rebasing past that point casually. When
Stage 5b picks a target newer than M140, the first thing to measure is whether
MV2 is still present or has become a restoration job. That measurement gates the
choice of target; it is not a detail to discover afterwards.

## Consequences

- Stage 5 must not port any Kiwi patch that assumes MV2 is the only manifest
  version. Both paths stay live.
- Stage 8 needs the extension installer to accept both, and the UI to show which
  version an extension uses — the user should be able to see why one blocker
  works better than another.
- Stage 5b's target selection is now partly an MV2 question. See
  [0003](0003-staged-chromium-rebase.md).
- MV2 being retained is a user-visible feature and belongs in the README, not
  buried in a flag.

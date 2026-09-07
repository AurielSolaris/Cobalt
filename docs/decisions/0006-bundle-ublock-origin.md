# 0006 — Bundle uBlock Origin, uninstallable but disableable

**Status:** accepted
**Depends on:** [0005](0005-support-mv2-and-mv3.md) — uBO is MV2-only
**Applies from:** Stage 7 (defaults) and Stage 8 (extensions)

## Decision

Cobalt ships **uBlock Origin preinstalled**, unmodified, as a recommended-mode
extension: the user **cannot uninstall it**, but **can disable it** from the
extension list at any time.

## The mechanism already exists upstream

No custom patch is needed. Chromium's `ManagedInstallationMode::kRecommended`
has exactly this semantic, and upstream says so in
`chrome/browser/extensions/standard_management_policy_provider.cc`:

```cpp
// Disallow removing of recommended extension, to avoid re-install it
// again while policy is reload. But disabling of recommended extension is
// allowed.
if (mode == ManagedInstallationMode::kForced ||
    mode == ManagedInstallationMode::kRecommended) { ... return true; }
```

`MustRemainInstalled` returns true; `MustRemainEnabled` does not. The policy
string is `normal_installed` (`extension_management_constants.cc`), as distinct
from `force_installed`, which would also block disabling — too strong.

This matters for rebase cost: recommended mode is a maintained upstream feature,
so we configure it rather than carry a patch. It should survive Stage 5 for free.

## Why bundle rather than recommend

The earlier plan was a first-run prompt offering a one-tap install. Two facts
moved it:

**uBO is MV2-only, and MV2 extensions have been progressively removed from the
Chrome Web Store as the deprecation advanced.** A "one tap to install from the
store" flow may simply not have a store to install from. *Needs verification
before Stage 7* — but if it holds, bundling is not a convenience, it is the only
reliable delivery path, and the recommend option was never real.

**A browser that blocks nothing out of the box is a worse browser.** Dropping
Kiwi's built-in blocker (see `docs/patch-classification.md`) was justified by
"extensions do this properly". That justification only pays out if the extension
is actually there.

## Licensing: clean

uBlock Origin is **GPLv3**. Cobalt is **GPLv3**. Redistribution inside the APK is
straightforwardly permitted — this is the licence working as intended, not a
loophole.

Obligations we take on:

- Ship uBO's **source** for the exact bundled version, or a written offer for it.
- Preserve its **copyright notice and licence text** verbatim.
- Ship it **unmodified**. If we ever modify it, say so prominently — GPLv3
  requires marking changed versions.
- Do **not** imply endorsement by uBO's author. Cobalt bundles uBO; uBO's author
  has not blessed Cobalt.

## Risks

**We implicitly vouch for it.** Anything uBO does, users will attribute to
Cobalt. Mitigated by shipping unmodified upstream releases and naming the exact
version in settings and release notes, so the provenance is visible.

**Bundled code goes stale.** Filter lists self-update inside uBO, which covers
most of the risk — the lists are what rot fastest. The extension code itself
does not self-update when installed this way, so **refreshing the bundled uBO
version is a release-checklist item**, not something to notice later.

**It is a large default.** Some users want no blocker at all. They can disable
it in two taps, which is why disable had to stay available and why
`force_installed` was rejected.

## Consequences

- Stage 8 bundles the `.crx` in the APK and installs it on first run in
  recommended mode.
- Settings must show the bundled uBO version and link to its source.
- The release checklist gains: *update the bundled uBO before tagging.*
- `LICENSES/` gains uBO's GPLv3 text and attribution.
- If uBO ever ships an MV3 build, none of this changes — MV2 stays supported per
  [0005](0005-support-mv2-and-mv3.md).

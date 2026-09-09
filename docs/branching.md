# Branching

Cobalt maintains two long-lived branches. Both are always buildable.

## `stable` is dormant, not parallel

**Nothing has been promoted to `stable`, and nothing will be for a while.** It
exists so the model is in place before it is needed; it is not a release line
running alongside `nightly` today, and the docs should not imply that it is.

Three things gate the first promotion, and they are product requirements rather
than a quality bar to be argued down:

1. **uBlock Origin works** as a bundled extension — installed, blocking, and
   disableable but not uninstallable. See
   [decision 0006](decisions/0006-bundle-ublock-origin.md).
2. **Google Play Services is gone.** No GMS dependency anywhere in the build,
   including the password path, which today is disabled outright without it. See
   [decision 0012](decisions/0012-own-password-store.md).
3. **Cobalt's own shell**, not Chromium's Android UI. Stage 6 joins the Compose
   shell to the content layer; until then the app ships Chromium's interface with
   Cobalt's name on it, which is a bring-up state and not a release.

Until all three hold, `nightly` is the only line that means anything. Treat a
`stable` reference in older docs as aspirational.

| Branch | Purpose | Receives | Gate |
|---|---|---|---|
| `nightly` | Integration line. All feature work, patches, and upstream rebases land here first. | feature branches, upstream rebases | builds clean, unit tests pass |
| `stable` | Release line. What users install. | merges from `nightly` only | full test matrix, manual smoke on a device, and a soak period on `nightly` |

## Rules

1. **Nothing is committed directly to `stable`.** It receives merges from `nightly`,
   and hotfixes, and nothing else.
2. **Feature work** happens on `feat/<stage>-<slug>` — for example
   `feat/1-html-parser` — and is merged into `nightly` by pull request.
3. **Hotfixes** branch from `stable` as `fix/<slug>`, land on `stable`, and are then
   **immediately cherry-picked back to `nightly`**. A fix that exists only on `stable`
   is a regression waiting to happen on the next promotion.
4. **Promotion** `nightly` → `stable` is a merge, never a squash, so history is
   continuous across both lines.
5. A change may skip `nightly` only when `stable` is actively broken.

## Soak

A change sits on `nightly` before it is promoted. The minimum is a working day for
routine changes; for anything touching the Chromium tree — a rebase, an engine change,
a build-system change — it is a week.

## Per-version branches

From 0.2.0 through 1.0.0, every milestone also keeps its own pair of branches:

| Branch | Cut from | Purpose |
|---|---|---|
| `nightly-0.x.0` | `nightly`, when work on 0.x.0 begins | The integration line *for that milestone*, frozen once it ships |
| `stable-0.x.0` | `stable`, when 0.x.0 is promoted | The released state of that milestone, kept forever |

The reason is that this project's risk is concentrated in a handful of very large
steps — the Chromium rebase above all — and a tag is not enough to work from when
one of them goes wrong. A tag is a point; a branch is a place you can commit to. If
Stage 5 turns out to have broken something that only surfaces two milestones later,
`stable-0.4.0` is still there, still buildable, and can take a fix without
disturbing the lines that moved on.

Rules:

- **Per-version branches are never deleted.** They are the project's memory.
- `nightly-0.x.0` is cut when the milestone's work starts, and stops receiving
  commits when the milestone is promoted to `stable`.
- `stable-0.x.0` is cut at promotion and is frozen, except for backported fixes.
- A fix backported to a per-version branch is **cherry-picked forward** to
  `nightly` in the same session, exactly as with hotfixes on `stable`.
- The unversioned `nightly` and `stable` remain the live lines. The versioned ones
  are for looking backwards, not for daily work.

After 1.0.0 this is reconsidered: once releases are routine and the rebase process
is proven, tags plus release branches for supported versions are likely enough.

## Tags

- Stable releases: `v<major>.<minor>.<patch>`, tagged on `stable`.
- Nightly builds: `nightly/<YYYYMMDD>`, tagged on `nightly`. The slash keeps build
  tags from reading like the `nightly-0.x.0` branches above.

Tags and per-version branches are complementary, not redundant: the tag records
the exact commit that shipped, the branch is where a fix to it can land.

## Channels

The two lines ship side by side and can be installed on the same device at once:

| Channel | applicationId |
|---|---|
| stable | `app.auriel.cobalt` |
| nightly | `app.auriel.cobalt.nightly` |

They carry different icons and display names so a bug report is never ambiguous about
which build it came from.

## Experiments

Speculative work that is not on the road to a release lives on `experiment/<slug>` and
never merges into `nightly` while it is speculative. The JavaScriptCore backend
(Stage 12) is the standing example: it lives on `experiment/jsc`, behind a build flag
that defaults to off, and it is allowed to be abandoned outright.

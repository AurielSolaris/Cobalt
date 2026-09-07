# Branching

Cobalt maintains two long-lived branches. Both are always buildable.

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

## Tags

- Stable releases: `v<major>.<minor>.<patch>`, tagged on `stable`.
- Nightly builds: `nightly-<YYYYMMDD>`, tagged on `nightly`.

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

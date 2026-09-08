# Cobalt's own patches

Changes Cobalt makes to Chromium that are **not** ported from Kiwi. Kept apart
from `patches/kiwi-105/` so the provenance of every change stays obvious: what
we inherited, and what we decided.

Each patch here states its intent in a comment. That is the whole point — 76 of
Kiwi's patches disable upstream behaviour with `#if 0` or `|| true` and record
no reason at all, so nobody can tell what breaks if they are dropped. Ours say
why, or they do not go in.

## Applying

Prefer a script over a context patch wherever the change is "set this named
thing to that value". A context diff against a file upstream edits every
milestone breaks on every rebase; matching by name survives.

- `tools/cobalt-mv2-defaults.py` — Manifest V2 enabled by default (decision 0005)

## Contents

| Patch | Replaces | Intent |
|---|---|---|
| `0001-android-keybinding-platform.patch` | Kiwi's `command.cc` | Extension keyboard shortcuts declared for `linux` work on Android |
| `0002-chrome-apps-on-android.patch` | Kiwi's `pref_names.cc` | `kChromeAppsEnabled` available on Android |

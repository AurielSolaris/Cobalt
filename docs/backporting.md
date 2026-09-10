# Backporting security fixes to Chromium 140

Standing work, not a project. Cobalt is pinned to Chromium 140 and takes
security fixes by backport rather than by rebase
([0016](decisions/0016-pin-chromium-140-and-backport.md)); fixes are identified
by corroborating across independent Chromium forks on top of upstream's own
record ([0017](decisions/0017-corroborate-security-fixes-across-forks.md)).

Both decisions called for this file. It is the operational half.

**The pin is only defensible while this runs.** 0016 says so in as many words:
Android's model works because the backport stream is a named, staffed, published
process, and pinning without one is not Android — it is Kiwi.

## The watcher

`.github/workflows/security-watch.yml` runs
`tools/security/watch-security-patches.py` daily at 07:00 UTC, and on demand
from the Actions tab.

It reads:

| Source | For |
|---|---|
| Chrome Releases feed | the authoritative CVE list: severity, component, bug id |
| `GrapheneOS/Vanadium` | corroboration |
| `uazo/cromite` | corroboration |
| `brave/brave-core` | corroboration |

It joins them **on the CVE id** — the only identifier all four share — and files
one issue per CVE, labelled `security`, `needs-triage`, and `source:<wherever it
was seen first>`. A fork is named as the source whenever one saw it, because a
fork moving before Chrome Releases posts is the entire reason for watching them.

Run it locally without filing anything:

```
python tools/security/watch-security-patches.py --days 14
GITHUB_TOKEN=... python tools/security/watch-security-patches.py --days 14
```

### What it will not tell you

- **A fix that names no CVE is invisible to it.** A CVE-keyed join cannot see a
  fork commit that says "fix crash in foo". This is a floor on what gets
  noticed, not a ceiling.
- **It decides nothing.** 0017: forks are a triage signal, upstream is the
  source of code. 0011 exists because a patch was applied mechanically and
  turned out to disable a security check, undetected for two builds.
- **Relevance is a hint.** The script flags components it recognises and only
  claims "probably not applicable" for subsystems provably not compiled
  (`enable_vr`, `enable_openxr`, `enable_arcore`, `enable_cardboard`, NaCl).
  That list is **kept in sync by hand** with the args.gn block in
  `tools/build/build-chromium.sh`, because the workflow runs on a GitHub runner
  with no Chromium checkout. If args.gn changes, change `NOT_COMPILED`.

### Failure is loud

An earlier version of the script printed `nothing to triage` while all three
forks were returning 403. For a security tool that is the worst available
outcome, so unreachable sources are now tracked separately from empty ones:

- every source unreachable → exit **2**, "that is a failure, not an all-clear"
- some unreachable → exit **1**, and the sources are named on stderr
- everything read → exit **0**

A green run means every source was actually read.

### On user agents

The script sends a descriptive User-Agent, which GitHub's API documents as a
requirement. Both sources were checked with that and with a Chrome-like string
and returned 200 either way; the 403s seen during development were
`x-ratelimit-remaining: 0`, i.e. unauthenticated quota, not user-agent
filtering. `COBALT_WATCH_UA` overrides it if a source ever does start
discriminating — a configuration change rather than a code change.

## Triaging one issue

1. **Does the code exist in M140, and does Cobalt compile it?** Check the file
   against the pinned tree and against `args.gn`. A CVE in code Cobalt does not
   build is closed with that written down.
2. **Find the upstream fix.** The bug id in the issue links to
   `issues.chromium.org`; visibility is often restricted at first and granted in
   batches later, so a sweep of previously-invisible bugs is part of the job.
3. **Take it from the nearest milestone branch, not trunk.** Trunk carries
   twelve milestones of refactoring on top. `chromiumdash`'s `fetch_milestones`
   gives each milestone's `chromium_branch` plus per-component branches for V8,
   Skia, ANGLE and WebRTC, and its `schedule_phase` marks extended-stable
   milestones — which receive backports themselves and are therefore closer to
   M140 than trunk.
4. **Adapt it to M140, and read it.** Not mechanically. If it cannot be
   backported faithfully, that is
   [rebase trigger 1](decisions/0016-pin-chromium-140-and-backport.md) — the
   most important one, and a fact rather than a judgement.
5. **Land it through the series.** `tools/patches/series.txt`, never by hand
   into a working tree. A backport that silently fails to apply is worse than a
   missing one, because it reports as fixed; every step in the series asserts,
   and a partial apply fails loudly.
6. **Record it**, including *why* it was identified as security-relevant.
   Corroboration is only checkable if the corroboration is written down.

## Dependencies are separate feeds

A meaningful share of Chromium CVEs originate in DEPS-pinned third parties —
V8, BoringSSL, ANGLE, Skia, libwebp, FreeType. They do not arrive with Chromium
and must be tracked separately.

Mozilla's advisories are worth reading for exactly this: not for Chromium fixes,
which are a different engine, but for **shared third-party codecs**. `libwebp` is
the standing example — one bug, both browsers.

## What legitimately shrinks the backlog

Per 0016, and only these:

- **Code Cobalt does not compile** — checked against `args.gn`, not assumed.
- **Google Play Services**, as [0013](decisions/0013-remove-google-play-services.md)
  removes it ([progress](gms-removal.md)).
- **Device APIs Cobalt does not ship** — WebXR, WebUSB, Web NFC, WebHID
  ([device-apis.md](device-apis.md)).

Preinstalled uBlock Origin is **not** on this list. It mitigates the drive-by
delivery path; it does not fix a bug, and it must never be cited as a reason to
skip a backport.

## Not yet built

- **The published backport record.** 0016 requires one — which CVEs, which
  upstream commits, which release — and calls an unfalsifiable security claim
  worth nothing. The `security` label is a start, not that record.
- **Fork release-watching.** The script reads commits, not releases. An
  out-of-band fork release that only bumps its Chromium base is 0017's sharpest
  signal and is currently invisible to it.

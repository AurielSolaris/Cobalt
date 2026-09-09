# 0011 — Refuse Kiwi patches that disable upstream code

**Status:** accepted
**Date:** 2026-09-09

## Context

[`patch-classification.md`](../patch-classification.md) measured that **76 of
Kiwi's 277 patches** switch upstream behaviour off by editing preprocessor
conditions — `#if 0`, `&& 0`, `#if !BUILDFLAG(IS_ANDROID) || true` — rather than
by configuration. It concluded:

> They carry no explanation of intent, and upstream restructuring silently
> changes what they disable. Each needs the *intent* recovered and re-expressed
> as a real build flag or feature toggle. Mechanically re-applying them would be
> porting a bug.

That conclusion was written down and then not enforced. `apply-batch.sh` applied
patches verbatim, so batch 1 carried two of them into the tree.

## What got in

**`extensions/browser/extension_protocols.cc`** — two hunks:

1. `#if 0` around `AllowExtensionResourceLoad`, the check that decides which
   processes may load a `chrome-extension://` resource. With it compiled out,
   the `web_accessible_resources` manifest key stops being enforced: any page
   can read any installed extension's files. That is an extension-enumeration
   and asset-disclosure hole, and it is precisely the surface a browser whose
   selling point is extensions cannot get wrong.
2. Component-extension bundle resources restricted to directory paths
   containing `cryptotoken`. Cryptotoken was the legacy U2F component
   extension; it no longer exists in M140, where the only surviving references
   are a docs page and retained enum values kept for histogram stability. So
   the guard is false for every real extension, and bundle resource loading
   never happens.

**`extensions/common/manifest_handlers/default_locale_handler.cc`** — `#if 0`
around `ShouldSkipValidation`, changing which locale layouts validate.

## Why it went unnoticed

`enable_extensions = !is_android && …`, so **none of this code was compiled**
in the Gate A build or in the first patched build. Both builds passed. The
defect was only reachable once Route B set `is_desktop_android = true` and the
extension subsystem started compiling — and then it surfaced as four
`-Wunused-variable` errors, because the variables feeding the disabled call had
nothing left to feed.

The compiler caught this. `-Werror` on an unused variable is the only reason a
disabled security check announced itself rather than shipping quietly.

## Decision

Both hunks are reverted; the files are pristine upstream.

`apply-batch.sh` now **refuses** any patch whose added lines contain a disabling
construct, printing the offending lines. `COBALT_ALLOW_DISABLERS=1` overrides,
for the case where the intent has been recovered and the construct is genuinely
the right expression of it.

Refusing is right even though it will block patches we eventually want. A
refused patch is a scheduling cost; a silently applied one is a security
regression that two green builds failed to reveal.

## Consequences

- The remaining ~74 disabling patches will each be refused on first contact and
  must be ported by hand, with the intent recovered and written down.
- Anything already applied before this gate existed is suspect. The audit that
  found these two swept the whole tree for added disabling constructs and found
  no others, so the current tree is clean.
- Gate B gains a check: `web_accessible_resources` must actually be enforced —
  a page that is not listed must fail to load an extension resource.

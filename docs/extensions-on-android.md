# Turning extensions on for Android

The finding that reframes Stage 5, made while landing batch 1.

## Extensions are compiled out of Android entirely

`extensions/buildflags/buildflags.gni`:

```gn
enable_extensions = !is_android && !is_ios && !is_castos && !is_fuchsia
```

Our Gate A build therefore contains **zero extension code**:

```
extensions objects        : 0
chrome extension objects  : 0
command.o                 : not built
```

This matters because it means the patch triage was measuring the wrong thing.
Batch 1's eight patches to `extensions/` applied cleanly — and compile nothing,
because none of those files are in the build. The same is true of all 70
extension-touching patches.

**Kiwi's achievement was never the 44 extension patches. It was making
`enable_extensions` work on Android at all.** The patches are the consequence of
that, not the cause.

## It is a GN argument, not a patch

`enable_extensions` sits inside `declare_args()`, so it can be overridden from
`args.gn` without touching a single source file. Nothing needs to be patched to
*attempt* it.

What happens next is the work.

## The assertion cascade

Setting `enable_extensions = true` for Android fails `gn gen` in sequence — each
fix revealing the next:

| # | Assertion | Where | Meaning |
|---|---|---|---|
| 1 | `!enable_extensions \|\| enable_guest_view` | `extensions/BUILD.gn:16` | Extensions need the `<webview>` plumbing; fixed by `enable_guest_view = true` |
| 2 | `assert(!is_android)` | `components/keep_alive_registry/BUILD.gn:5` | Pulled in by `//extensions/shell` — app_shell, a desktop demo binary we do not ship |

The second is the shape of the whole task: the extension subsystem's **target
graph** assumes desktop, and pulls in components that assert they are not on
Android. The C++ mostly does not care; the build files do.

## This is what Kiwi's BUILD.gn patches are for

`patches/kiwi-105/extensions_BUILD.gn.patch` deletes `extensions_unittests` and
its dependencies. Read in isolation that looks like removing tests to save build
time. In context it is obvious: **those targets do not build on Android, and
`gn gen` evaluates them whether or not we build them.**

The same reading applies to Kiwi's other `BUILD.gn` patches —
`chrome_browser_extensions_BUILD.gn`, `chrome_renderer_BUILD.gn`,
`content_browser_BUILD.gn`, `ui_webui_BUILD.gn`. They are not incidental. They
are the port.

## What this changes

**Nothing about the strategy, and the order of the work.**

- We are not switching to building Kiwi. Kiwi is M105, three years of security
  fixes behind, and building it would mean shipping known-vulnerable code. The
  whole point of [0003](decisions/0003-staged-chromium-rebase.md) stands.
- We are not backporting to M105. Same reason.
- Batch 1's C++ patches are correct and stay applied. They simply cannot be
  *verified* until the subsystem compiles, so Gate B moves behind this work.

**Batch 0 now exists, and comes first:** make `enable_extensions = true` reach a
successful `gn gen` and then a successful build on Android, by pruning the
desktop-only targets from the graph — which is precisely what Kiwi's `BUILD.gn`
patches do, ported forward.

The estimate for Stage 5 does not change much. The 160 patches were always
going to be the work; this says which of them to do first, and it says the
`BUILD.gn` ones are load-bearing rather than housekeeping.

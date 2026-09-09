# 0010 — Reach extensions through upstream's `is_desktop_android`, not by forcing `enable_extensions`

**Status:** proposed — needs a build to confirm
**Supersedes in part:** the enablement half of [`extensions-on-android.md`](../extensions-on-android.md)
**Stage:** 5, and it reshapes it

## The finding

Chromium M140 is **already building extensions for Android**, behind a flag we
did not know about.

```gn
# extensions/buildflags/buildflags.gni
enable_extensions = !is_android && !is_ios && !is_castos && !is_fuchsia

enable_desktop_android_extensions = is_desktop_android

enable_extensions_core = enable_extensions || enable_desktop_android_extensions
```

`enable_extensions_core` exists precisely so callsites do not need
`if (enable_extensions || enable_desktop_android_extensions)` everywhere. Upstream
has been separating "the core extensions system" from "the desktop extensions
platform", and the TODO on it (crbug.com/356905053) says the scope is still
expanding.

## Measured, not assumed

Three configurations, same tree:

| Route | Config | `gn gen` |
|---|---|---|
| **A** — Kiwi's route | `enable_extensions = true` | **fails**: `guest_view/renderer` asserts `!is_android` |
| **B** — upstream's | `is_desktop_android = true` | **passes**, 160 extension targets in the graph |
| **B2** | `enable_desktop_android_extensions = true` alone | **fails**: other components check `is_desktop_android` itself |

And the question that decides it — is the extension system actually wired into
the APK we ship, or merely present in the graph?

```
gn path chrome_public_apk //extensions/browser
  is_desktop_android = true   REACHABLE
  plain Android (out/Default) Label not found
```

Under `is_desktop_android`, `//extensions/browser`, `//extensions/common` and
`//chrome/browser/extensions` are all reachable from `chrome_public_apk`. In our
current build those labels **do not exist at all**.

`chrome_public_apk` still exists in that configuration, and only **33 build files**
branch on `is_desktop_android`, so the surface is small.

## Why this changes the strategy

Route A is a fork-local fight that gets harder every milestone: each Chromium
release adds desktop assumptions to code we would be forcing onto Android, and
we would re-resolve them forever. That is exactly the growing delta this project
exists to escape.

Route B is an **upstream-maintained flag that is actively expanding**. Every
milestone makes it more capable rather than more expensive. It is the difference
between pushing against Chromium and being carried by it.

## What is not yet known

This is proposed, not accepted, and it must not be treated as settled until built:

- **`gn gen` passing is not compiling, and compiling is not running.** Nothing
  has been built with this flag yet.
- Upstream calls the extensions half **"very much in-development, non-stable, and
  likely to crash at any given moment."** That was written about
  `enable_desktop_android_extensions`, and it is a direct warning.
- `is_desktop_android` means *"defaults that enable features on Android that are
  more typically available on desktop"* — it will flip things beyond extensions.
  Those 33 build files need reading before this is accepted, because a desktop UI
  on a phone is not what Cobalt is.
- Whether MV2 works under it is unmeasured. [0005](0005-support-mv2-and-mv3.md)
  remains the gate.

## What Kiwi is still for

This does not make the port pointless, and the distinction matters:

- Kiwi's **enablement** work — the `BUILD.gn` pruning, forcing the desktop
  platform onto Android — is superseded if Route B holds. That was the hardest,
  least portable part.
- Kiwi's **UI and integration** work is not. The app menu that makes extensions
  reachable on a phone, the install flow, the management surface: upstream's
  desktop-android variant is aimed at desktop-shaped Android devices, not at a
  phone browser. That remains our work, and per
  [`module-map.md`](../module-map.md) it is read as specification and written in
  Compose rather than ported as Java.

## Next step

Build it. One configuration, `is_desktop_android = true`, and find out whether it
compiles and whether an extension loads. That answer decides Stage 5's shape.

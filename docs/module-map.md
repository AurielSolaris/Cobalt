# Mapping Kiwi onto Cobalt's modules

Where each part of Kiwi's delta lands in Cobalt, and — more importantly — which
parts have no Cobalt module at all because they live inside the Chromium tree.

## The two trees

This is the thing to be clear about before any mapping makes sense. Cobalt is
**two source trees that meet at one boundary**, not one codebase:

| | Where it lives | What it is |
|---|---|---|
| **Chromium tree** | `/build/chromium/m140` (not in this repo) | Blink, V8, network stack, extension system, the `chrome/` browser layer. Fetched, patched, built. |
| **Cobalt tree** | this repository | The Android shell — Kotlin and Compose — plus the patch series, tooling, and docs that describe what to do to the Chromium tree. |

Kiwi has no equivalent of the second tree. Its Android UI is Java living *inside*
`chrome/android/`, patched in place. Cobalt's is Kotlin in `modules/app`, outside
the Chromium tree entirely.

That difference is the whole point, and it decides most of this mapping.

## Where Kiwi's 277 patches land

| Kiwi area | Patches | Lands in | Notes |
|---|---:|---|---|
| `chrome/browser` | 137 | Chromium tree | Patch series only. No Cobalt module. |
| `chrome/android` (Java UI) | 45 | **Mostly nowhere** | See below — this is the interesting one. |
| `third_party/blink` | 22 | Chromium tree | 2 of 3 are the dropped ad blocker. |
| `extensions/*` | 14 | Chromium tree | Kept whatever the cost. |
| `content/public`, `ui/webui`, `chrome/renderer` | 18 | Chromium tree | |
| `base`, `components`, `net`, `services`, `remoting` | ~11 | Chromium tree | |
| Branding, metadata, `toolbox/` | ~30 | Dropped | Stage 4. |

**No Kiwi patch maps into `modules/core` or `modules/engine`.** Those hold the
0.1.0 bring-up code — URL handling, the HTML parser, the JS engine interface —
and Blink supersedes all of it in Stage 6.

## The Android UI question

Kiwi's 45 `chrome/android` patches are its phone UI: the app menu that reaches
extensions, the toolbar, download settings, night mode. Cobalt already has an
app menu, toolbar, and tab switcher — written in Compose, working today, and
themed.

So these patches are **not ported as code.** They are read as a **specification**:
they show which Chromium C++ surfaces the UI must call to make extensions usable
on a phone. Cobalt reimplements the calling side in Kotlin.

Concretely, `AppMenuPropertiesDelegateImpl.java` (281 lines) matters not for its
Java but for revealing that the menu talks to `AppMenuBridge`, `Profile`,
`WebContents`, and `WebsitePreferenceBridge`. Those are the seams Cobalt's shell
binds to.

Two patches in that set are exceptions and get ported as-is: anything modifying
C++ or JNI signatures that the Kotlin side depends on. The Java UI itself is not
carried forward.

## The boundary

Everything crosses at one place, and Stage 6 builds it:

```
modules/app      Compose shell — tabs, address bar, settings, theme
       │
       │  JNI / the content layer's public API
       ▼
Chromium tree    content/ → chrome/browser/ → extensions/ → Blink → V8
```

Cobalt's shell must depend only on Chromium's *public* interfaces — the content
layer's API and the JNI surfaces Kiwi's patches reveal. Reaching past that into
Blink internals would create a dependency that every rebase has to repair, which
is the cost that killed Kiwi.

## What happens to `modules/core` and `modules/engine`

| Module | Today | After Stage 6 |
|---|---|---|
| `modules/core` | URL handling, HTTP loader, charset detection, JS engine interface | HTTP loader and charset code **deleted** — Chromium's network stack replaces them. URL handling likely deleted in favour of GURL. **The `JsEngine` interface stays**: it is the one seam Stage 12 needs, and it costs nothing. |
| `modules/engine` | HTML tokenizer, tree builder, stub JS engine | **Deleted entirely.** Blink parses; V8 executes. |
| `modules/app` | Compose shell + the throwaway renderer | Renderer **deleted**; shell kept and rebound to the content layer. |

The deletions are the plan working, not a setback. `CONTRIBUTING.md` already
forbids growing these modules for exactly this reason.

## Kiwi's `toolbox/`

Two scripts — an icon updater and a Crowdin translation puller. Neither is
ported. Cobalt's equivalents live in `tools/` and are written as needed; the
translation pipeline is a Stage 7 question, once there are strings worth
translating.

## Status

Derived from the extracted patch series and the 0.1.0 module layout. The
boundary above is a **plan, not an implementation** — nothing has been bound to
the content layer yet. Stage 6 will move things between these rows.

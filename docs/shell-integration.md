# Joining Cobalt's shell to the content layer

Release gate 3, and the largest remaining piece. What the shell *looks like* was
settled long ago — [0002](decisions/0002-shell-design.md) and
[0007](decisions/0007-user-themes.md) cover palette, shape, fonts and the four
bottom sections. This is about the seam: how a Compose UI drives Chromium.

**Status: investigated, not started.** Nothing here is built. The point of
writing it now is that the two questions that could have changed the plan are
both answered, and they came back favourably.

## The two viability questions, both closed

### 1. Does `chrome://extensions` need `chrome/android`? — No

```
$ gn path out/Default //chrome/browser/ui:ui //chrome/android:chrome_java
No non-data paths found between these two targets.
```

Asked by [0014](decisions/0014-gms-removal-before-shell.md), which called it the
shell's one genuine viability risk. The extension UI is a WebUI page built under
`enable_extensions_core`; all the shell must do is navigate a tab to it.

### 2. Can an embedder host content without `chrome/android`? — Yes

```
$ gn path out/Default //content/shell/android:content_shell_java \
                      //chrome/android:chrome_java
No non-data paths found between these two targets.
```

And the embedder is **three Java files**:

```
content/shell/android/java/src/org/chromium/content_shell/
    Shell.java                    446 lines
    ShellManager.java             150 lines
    ShellViewAndroidDelegate.java
```

That is the whole reference implementation of "host Chromium in an Android
view". `chrome/android` is Chrome's *product*, not the embedding requirement.

## The seam, concretely

Everything needed comes from `//content/public/android` and
`//components/embedder_support/android` — both already in `chrome_public_apk`'s
graph (98 edges), so nothing new has to be enabled.

| Piece | What it is | Cobalt's use |
|---|---|---|
| `BrowserStartupController` | `startBrowserProcessesAsync(...)` | start the browser process from Cobalt's Application/Activity |
| `WindowAndroid` | binds native to the Activity | one per Activity |
| `ContentViewRenderView` | the compositor surface, an Android `View` | wrapped in a Compose `AndroidView` |
| `ContentView` | input and accessibility over a `WebContents` | per tab |
| `WebContents` | **the tab** | one per Cobalt tab |
| `NavigationController` | back/forward/load | drives the address bar and gestures |
| `ViewAndroidDelegate` | glue for anchored views | Cobalt's own implementation |

**Cobalt owns its tab model.** Chrome's `Tab`, `TabModel` and `TabModelSelector`
live in `chrome/android` and are not required: a tab is a `WebContents` plus
Cobalt's own state. That is a Kotlin data structure
([0009](decisions/0009-kotlin-for-new-code.md) — new code is Kotlin), not a port
of Chrome's.

This is also where the deliberately-guarded region from the webNavigation port
finally connects. `WebNavigationEventRouter` is compiled out on Android, so
`onTabReplaced` and `onCreatedNavigationTarget` do not fire; when Cobalt has a
tab model, that guard is where it hooks in. See
[`ublock-bundling.md`](ublock-bundling.md).

## What is genuinely unknown

Everything above is a dependency-graph result. These are not, and each needs a
spike of its own before anything is committed to:

- **Compose over a SurfaceView.** `ContentViewRenderView` owns a
  `SurfaceView`/`TextureView`. Putting that inside `AndroidView` inside a Compose
  tree, with correct z-order against Compose-drawn chrome, is the single most
  likely source of unpleasant surprises. **Spike this first** — a Compose
  activity showing one page, nothing else.
- **What `chrome/browser` expects that only `chrome/android` provides.** The
  extension WebUI is clean, but other browser-layer code reaches into Java
  through interfaces Chrome's Android layer implements. This is the same
  question `gn path` answered twice; it needs asking per subsystem rather than
  once.
- **Downloads, permissions prompts, and the intent surface** — none of which
  `content_shell` implements, because it is a test harness. These are Cobalt's
  to write, and they are not small.
- **Incognito**, which 0002 gives a dedicated surface, needs an off-the-record
  `BrowserContext` and a second `WebContents` family.

## Order this should go in

1. **Compose + `ContentViewRenderView` spike.** One activity, one hardcoded URL,
   no tabs, no chrome. Answers the riskiest unknown for a day's work.
2. **Tab model in Kotlin** — a list of `WebContents`, create/close/switch.
3. **Bottom bar wired to it** — the four sections from 0002, with the address
   bar and `NavigationController`.
4. **The surfaces that are not content**: extensions (a WebUI navigation),
   downloads, settings.
5. **Incognito.**

None of this blocks [gate 2](gms-removal.md), and gate 2 should still land first
— 0014's ordering holds, and this document does not change it. It exists so the
shell starts from measurements rather than from a blank page.

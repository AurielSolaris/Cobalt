# Joining Cobalt's shell to the content layer

Release gate 3, and the largest remaining piece. What the shell *looks like* was
settled long ago — [0002](decisions/0002-shell-design.md) and
[0007](decisions/0007-user-themes.md) cover palette, shape, fonts and the four
bottom sections. This is about the seam: how a Compose UI drives Chromium.

**Status: the two viability questions are closed and the first spike is done.** Nothing here is built. The point of
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

## The architecture this actually implies — and it inverts the obvious one

The obvious plan is "write Cobalt's Compose shell inside Chromium's build,
next to `chrome_java`". **That does not work**, and it is better to know now:

- Chromium **ships** the Compose runtime — `third_party/androidx` carries
  `androidx_compose_runtime`, `_ui`, `_foundation`, `_animation` and
  `_material3`, 227 references in one BUILD.gn.
- Chromium **cannot compile** Compose source. `@Composable` requires the Compose
  Kotlin compiler plugin, and `build/android/gyp/kotlinc.py` has no plugin
  support at all — no `-Xplugin`, nothing. Nothing in the tree uses Compose
  today; the libraries are transitive baggage.

So the dependency runs the other way round. Chromium's own build has the
template for it:

```
build/config/android/rules.gni:1687   template("dist_aar")
chromecast/BUILD.gn:717               dist_aar("cast_browser_dist_aar")
```

`dist_aar` packages Java, **native libraries**, assets, resources and a manifest
into an `.aar`. So:

**Chromium's content layer is exported as an AAR, and Cobalt's existing Gradle
app consumes it.** The Compose interface stays where Compose already works —
`modules/app`, which has Material3, the theme, and the screens from 0.1.0 — and
Chromium becomes a dependency of Cobalt rather than Cobalt becoming a patch to
Chromium.

That is also better for every reason
[`gms-removal.md`](gms-removal.md) already argues: the shell is Cobalt-owned code
in Cobalt-owned files, and it never conflicts on rebase.

### Verified: the AAR builds, and the seam is in the right place

`tools/patches/cobalt-content-aar.py` adds `dist_aar("cobalt_content_dist_aar")`
over exactly the embedding surface above — `content_full_java` plus
`embedder_support:content_view_java`. It built in **21 seconds, 2 steps**, over
Java that was already compiled.

| | |
|---|---:|
| `cobalt_content.aar` | **55.3 MB** (58,031,439 bytes) |
| files | 1,567 |
| classes in `classes.jar` | **22,364** |
| **`org/chromium/chrome/` classes** | **0** |

That last row is the result that matters. **Nothing from `chrome/android` comes
along** — no Chrome UI, no tab model, no toolbar. The closure is the content
layer and its dependencies:

```
5489  org/chromium/blink      (mojom bindings, the bulk of it)
2165  org/chromium/network
1931  com/google/common
1446  org/chromium/media
1039  org/chromium/device
 509  org/chromium/ui
 433  org/chromium/content
 414  org/chromium/base
```

So the seam is cut in the right place: Cobalt gets the engine without inheriting
Chrome's interface, which is the whole premise of gate 3.

**55 MB of Java is not the shipped cost.** Most of those 5,489 blink mojom
classes are unreachable from any given embedder and R8 in the consuming app
shrinks accordingly — but that is an expectation, not a measurement, and it
should be measured once the app actually links against it.

**Java only, deliberately.** No native libraries and no assets: `libchrome.so`
is 205 MB and the paks another 70 MB, and bundling them would have made every
failure a twenty-minute failure while answering nothing about the question that
was actually open. Adding them is mechanical — `cast_browser_dist_aar` sets
`native_libraries` and an assets dep — and is the next step.

## Closed: Compose draws over the content surface

The riskiest unknown, and it is answered.

`ContentViewRenderView` composites into a `SurfaceView`, which SurfaceFlinger
composites rather than the view hierarchy — so "does Compose draw on top" is not
answerable from Compose's painting rules. If it did not, Cobalt's entire
interface would need a different structure.

`modules/app/.../content/ContentSurface.kt` hosts a real `SurfaceView` in an
`AndroidView`, painted through its holder with `lockCanvas` so the pixels come
from the surface rather than from Compose. `ContentSurfaceSpike.kt` puts Compose
chrome above and below it and offers a switch on `setZOrderOnTop`.

| `setZOrderOnTop` | Result |
|---|---|
| `false` (default) | **Compose chrome draws over the surface** — address bar and bottom bar both visible |
| `true` | **all Compose chrome disappears** behind the surface |

Verified on device, both states, screenshots taken. The negative control is what
makes it conclusive: the difference is entirely `setZOrderOnTop`, and the
default is the one Cobalt needs. `ContentViewRenderView` defaults to `false` for
the same reason, so this is the supported direction rather than a lucky accident.

Kept as a runnable screen rather than deleted — it is the regression test for
the seam. When `ContentViewRenderView` replaces the placeholder, the screen
should behave identically.

## What is still genuinely unknown

Everything above is a dependency-graph result. These are not, and each needs a
spike of its own before anything is committed to:

- **Native libraries and assets in the AAR.** The Java half is done (below);
  `libchrome.so` is 205 MB and the pak/ICU assets another 70 MB, and how a
  Gradle consumer handles those is untested. `cast_browser_dist_aar` shows the
  mechanism.
- **Browser process startup from a Gradle app.** `BrowserStartupController`
  needs the native library loaded and the command line initialised, which
  `ChromeBrowserInitializer` does in `chrome/android` — the layer Cobalt is not
  taking. `content_shell` does it in about thirty lines; that is the model.
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

1. ~~**Compose + SurfaceView spike.**~~ **Done** — Compose draws over it.
2. ~~**A content `dist_aar`.**~~ **Done** — builds, 55.3 MB, no Chrome UI in it.
3. **The AAR with native libraries and assets**, consumed by `modules/app`: one
   hardcoded URL, no tabs, no chrome. This is where browser-process startup
   gets solved.
4. **Tab model in Kotlin** — a list of `WebContents`, create/close/switch,
   behind the `BrowserEngine` seam in `modules/app/.../browser/engine/`, which
   `DocumentEngine` already implements for the 0.1.0 pipeline.
5. **Bottom bar wired to it** — the four sections from 0002, with the address
   bar and `NavigationController`.
6. **The surfaces that are not content**: extensions (a WebUI navigation),
   downloads, settings.
7. **Incognito.**

None of this blocks [gate 2](gms-removal.md), and gate 2 should still land first
— 0014's ordering holds, and this document does not change it. It exists so the
shell starts from measurements rather than from a blank page.

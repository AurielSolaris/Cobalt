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

### Then the native library, and one thing that does not work

Adding `native_libraries = [ "$root_build_dir/libchrome.so" ]` takes the
artifact from 55.3 MB to **260.3 MB**, in 24 seconds. `jni/arm64-v8a/libchrome.so`
is in it, all 205 MB.

**Assets do not come through, and cannot.** Adding
`chrome_apk_pak_assets`, `chrome_apk_locale_pak_assets` and
`chrome_public_non_pak_assets` to `deps` builds them and then discards them —
measured, `assets/` count **zero**. The reason is in
`build/android/gyp/dist_aar.py`, which takes

```
--jars  --dependencies-res-zips  --r-text-files
--proguard-configs  --native-libraries  --abi
```

and **has no assets argument of any kind**. The template's doc comment lists
`assets/` because that is what an `.aar` may contain in general, not because
this template writes any.

So the `.pak` bundles, ICU data and the bundled uBlock Origin CRX have to reach
the app by another route. Cheapest is copying them out of the Chromium build
into `modules/app/src/main/assets/` as a build step — no Chromium patch, and
the paths the engine looks for are unchanged. Patching `dist_aar.py` to support
assets would be tidier and is a change to a shared script for one consumer's
benefit.

### The mismatch I predicted, the fix I built, and why both were wrong

`libchrome.so` is built for `chrome_public_apk` and its JNI registration is
generated from *that* APK's Java — `chrome/android`'s, which this AAR does not
carry. That looked like a startup failure waiting to happen, and the fix looked
obvious: give Cobalt its own `shared_library` whose registration comes from the
Java Cobalt actually ships.

**It was built. It achieves nothing.**

`chrome_common_shared_library("libcobalt")` with
`java_targets = [ "//chrome/android:cobalt_content_dist_aar" ]` is valid GN,
links a 205 MB `libcobalt.so` in 46 seconds, and produces:

```
libcobalt__jni_registration.srcjar   642860 bytes   md5 1dbf7ae576d3...
libchrome__jni_registration.srcjar   642860 bytes   md5 1dbf7ae576d3...
```

**Byte-identical.** Pointing `java_targets` at the AAR changed no part of the
generated registration, because a shared library's JNI surface is decided by the
C++ it contains, not by the Java it is told about. Cobalt wants Chrome's browser
layer — extensions live there, and extensions are why Cobalt exists — so it
necessarily wants Chrome's JNI surface. There is no version of "Chrome's browser
layer with content's JNI".

And that undermines the problem as well as the fix. `third_party/jni_zero/jni_zero.gni:597`
defaults to:

```gn
add_stubs_for_missing_jni = true
remove_uncalled_jni = true
```

So a registration **already** tolerates Java that is not present: missing
implementations become stubs and uncalled natives are dropped. The startup
failure predicted here may simply not occur.

**Both the fix and the problem are now unproven, and only one test settles
either:** load the library from the Gradle app and call
`BrowserStartupController`. Until that runs, "the AAR needs Chrome's Java" is a
guess in both directions.

`libcobalt` was removed rather than kept — it doubled a 205 MB link for no
measured benefit. If the startup test does find a real mismatch, that target is
where the fix goes, and it is in git history.

"Just use content_shell's library" remains unavailable for the reason above:
Cobalt needs Chrome's browser layer, not bare content.

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

## The app consuming the AAR — and the mismatch, confirmed

`tools/build/export-aar.sh` copies the artifacts out of the WSL checkout into
the Gradle project. Both destinations are gitignored: a 250 MB AAR and 70 MB of
assets do not belong in a repository and rebuild in a minute.

Getting Gradle to accept it took four fixes, each a real incompatibility rather
than a configuration nicety:

| Symptom | Cause | Fix |
|---|---|---|
| `Duplicate class _COROUTINE...`, `androidx...` | the AAR carries its whole closure | `jar_excluded_patterns` for androidx, kotlin, kotlinx |
| `Duplicate class ...ListenableFuture` | androidx pulls the empty `listenablefuture` stub; the AAR has real Guava | exclude the stub in Gradle |
| `AAPT: res/0_res/anim: resource file cannot be a directory` | `dist_aar` writes `res/<n>_res/…`, which is not an AAR layout | `flatten-aar-res.py`, which refuses if there is ever more than one index |
| duplicate `attr/elevation` *inside* the AAR | androidx resources travel even with androidx classes excluded | `resource_excluded_patterns = ["*"]`, a real limitation |

Result: a **257.8 MB APK** built by Gradle from Compose source, carrying
`libchrome.so` uncompressed and 341 assets.

### Two classes the AAR can never carry

`org.chromium.build.BuildConfig` and `org.chromium.build.NativeLibraries` are
generated by the `android_apk` template, not by any library — everything in them
describes an APK. A `dist_aar` produces neither, and Chromium's base fails at
the first call with `NoClassDefFoundError`. Cobalt declares both itself, in
`modules/app/src/main/java/org/chromium/build/`, which is the right home: the
Gradle app *is* the APK. A search of the whole `gen` tree finds only these two.

`BuildConfig.IS_DESKTOP_ANDROID` must stay `true` — the AAR's Java was compiled
from a tree built that way, and it is what gives Cobalt extensions at all. The
app's `minSdk` moved from 24 to **29**, Chromium's floor.

### The JNI mismatch is real after all

With both classes present, `libchrome.so` loads and the process aborts:

```
java.lang.AssertionError: JNI multiplexing hash lookup failed with J.N
    at org.jni_zero.JniInit.crashIfMultiplexingMisaligned(JniInit.java:46)
```

**So the mismatch predicted two steps ago exists, and the revision that talked
it away was wrong.** `add_stubs_for_missing_jni` does not save you: JNI
multiplexing hashes the *whole* surface and dispatches through a generated `J.N`
class, so the app's `J.N` — generated from the AAR's Java — cannot agree with a
native table generated from `chrome_public_apk`'s.

It also explains why `libcobalt` produced a byte-identical registration and
fixed nothing: the multiplexing table is not what `java_targets` controls.

The actual lever is a GN argument, `enable_jni_multiplexing`, now `false` in
`args.gn`. Without multiplexing, registration is by name and jni_zero's
defaults handle the rest. **`chromecast` sets it false too — and chromecast is
the one build in the tree that ships a `dist_aar`.** The two go together, and
that is not a coincidence.

Cost: multiplexing exists to shrink the JNI table, so this gives up binary size.
Unmeasured, and worth measuring once the shell runs.

## What is still genuinely unknown

Everything above is a dependency-graph result. These are not, and each needs a
spike of its own before anything is committed to:

- **Whether `libchrome.so` loads and starts from an app that does not carry
  `chrome/android`'s Java.** jni_zero stubs missing JNI by default, so it may
  simply work. This is the largest open question and it can only be answered by
  running it.
- **How the runtime assets reach the app**, since `dist_aar` cannot carry them.
- **Whether a 260 MB AAR is workable for Gradle at all** — untested, and a real
  risk: the Java half alone is 55 MB before R8.
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
3. ~~**The AAR with native libraries.**~~ **Done** — 260 MB, and it surfaced
   two things: assets cannot travel in an AAR, and `libchrome.so`'s JNI
   registration expects Java this AAR does not carry.
4. **`modules/app` consuming the AAR**: one hardcoded URL, no tabs, no chrome.
   This is where browser-process startup gets solved, and it is also the only
   test that settles whether the JNI surface is a real problem — the separate
   `libcobalt` step was tried first and proved to be neither a fix nor
   necessary.
5. **Tab model in Kotlin** — a list of `WebContents`, create/close/switch,
   behind the `BrowserEngine` seam in `modules/app/.../browser/engine/`, which
   `DocumentEngine` already implements for the 0.1.0 pipeline.
6. **Bottom bar wired to it** — the four sections from 0002, with the address
   bar and `NavigationController`.
7. **The surfaces that are not content**: extensions (a WebUI navigation),
   downloads, settings.
8. **Incognito.**

None of this blocks [gate 2](gms-removal.md), and gate 2 should still land first
— 0014's ordering holds, and this document does not change it. It exists so the
shell starts from measurements rather than from a blank page.

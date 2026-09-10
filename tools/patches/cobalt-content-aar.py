#!/usr/bin/env python3
"""A dist_aar over the embedding surface, so Gradle can consume Chromium.

The spike `docs/shell-integration.md` calls the riskiest remaining shell
question, after Compose-over-SurfaceView was answered.

## Why an AAR at all

Chromium **ships** the Compose runtime in `third_party/androidx` but **cannot
compile** Compose source: `@Composable` needs the Compose Kotlin compiler
plugin and `build/android/gyp/kotlinc.py` has no plugin support of any kind.
Nothing in the tree uses Compose today.

So Cobalt's interface cannot be built inside Chromium's build, and the
dependency has to run the other way: Chromium becomes an artifact that Cobalt's
Gradle project consumes. `dist_aar` is Chromium's own supported way to do that
(`build/config/android/rules.gni:1687`), with a live precedent in
`chromecast/BUILD.gn:717` that packages Java, native libraries, assets and a
manifest into one `.aar`.

## What this target is, and is not

**It is Java only.** The deps are exactly the embedding surface
`shell-integration.md` identified — `content_full_java` for `WebContents`,
`NavigationController` and `BrowserStartupController`, plus
`embedder_support:content_view_java` for `ContentView` and
`ContentViewRenderView`.

The first version was **Java only**, to learn cheaply whether `dist_aar` coped
with the dependency closure at all and how much of Chromium came along
uninvited. It did, in 21 seconds, at 55.3 MB and 22,364 classes with **zero**
`org/chromium/chrome/` classes — the seam is in the right place.

It now also carries `libchrome.so`, at 205 MB, which takes the artifact to
**260 MB**.

**It does not carry the runtime assets, and it cannot.** `dist_aar` has no
mechanism for them: `build/android/gyp/dist_aar.py` accepts `--jars`,
`--dependencies-res-zips`, `--r-text-files`, `--proguard-configs` and
`--native-libraries`, and nothing else. Adding asset targets to `deps` builds
them and discards them — measured, `assets/` count zero. The template's doc
comment lists `assets/` because that is what an `.aar` may contain in general,
not because this template writes any.

So the `.pak` bundles, ICU data and the bundled uBO CRX have to reach the app by
another route, and that is now a known piece of work rather than an assumption.

## The mismatch this is expected to expose

`libchrome.so` is built for `chrome_public_apk`, and its JNI registration is
generated from *that* APK's Java — `chrome/android`'s, which this AAR
deliberately does not carry. A native library whose registration references Java
classes that are not present is not a packaging problem, it is a startup
problem, and it will not show up until something calls
`BrowserStartupController`.

So this step is expected to produce a **working artifact and an open question**,
and the question is the interesting part:

1. **Ship `chrome/android`'s Java in the AAR too.** Straightforward, and it
   undoes the "zero Chrome classes" result — Cobalt would carry Chrome's
   interface without using it and rely on R8 to strip it.
2. **Give Cobalt its own `shared_library` target**, with Chrome's browser layer
   but registration generated from the Java Cobalt actually ships. More work,
   and the honest shape.

Cobalt needs Chrome's browser layer rather than bare content — extensions live
there, and extensions are why Cobalt exists — so "just use content_shell's
library" is not an option.

## What to look at when it builds

    ls -la out/Default/apks/cobalt_content.aar
    unzip -l out/Default/apks/cobalt_content.aar | tail -5
    unzip -l out/Default/apks/cobalt_content.aar | grep -E "jni/|assets/" | head

Idempotent; the edit asserts.
"""
import sys
from pathlib import Path

SRC = Path(sys.argv[1] if len(sys.argv) > 1 else "/opt/cobalt/chromium/m140/src")

REL = "chrome/android/BUILD.gn"

MARKER = "cobalt_content_dist_aar"

TARGET = '''
# ---------------------------------------------------------------------------
# Cobalt: the content layer, packaged for a Gradle consumer.
#
# Chromium cannot compile Jetpack Compose -- its kotlinc has no compiler-plugin
# support -- so Cobalt's interface is built in its own Gradle project and this
# is how Chromium reaches it. See docs/shell-integration.md.
#
# Java only, deliberately: libchrome.so is 205 MB and the pak/ICU assets are
# another 70 MB, and bundling them would make every failure slow while telling
# us nothing about the question this answers, which is whether dist_aar copes
# with this dependency closure and how much comes along uninvited.
# native_libraries and asset_deps are mechanical afterwards -- see
# chromecast/BUILD.gn's cast_browser_dist_aar.
dist_aar("cobalt_content_dist_aar") {
  # The engine itself. 205 MB, and the reason the APK is what it is.
  native_libraries = [ "$root_build_dir/libchrome.so" ]

  deps = [
    # The engine's native half has to be built before it can be packaged.
    "//chrome/android:libchrome",

    # The embedding surface: WebContents, NavigationController,
    # BrowserStartupController.
    "//content/public/android:content_full_java",

    # ContentView and ContentViewRenderView -- the SurfaceView that
    # modules/app/.../content/ContentSurface.kt already stands in for.
    "//components/embedder_support/android:content_view_java",

    # NOTE: the runtime assets -- .pak bundles, ICU data, the bundled uBlock
    # Origin CRX -- are deliberately NOT listed here, because listing them does
    # nothing. dist_aar cannot package assets: build/android/gyp/dist_aar.py
    # takes --jars, --dependencies-res-zips, --r-text-files, --proguard-configs
    # and --native-libraries, and has no assets argument of any kind. Adding
    # asset targets to deps builds them and drops them on the floor.
    #
    # They have to reach the app another way. See docs/shell-integration.md.
  ]

  output = "$root_build_dir/apks/cobalt_content.aar"

  # Signatures and build metadata are not API and only cause conflicts in a
  # consumer's merge step. Same exclusion cast_browser_dist_aar uses.
  jar_excluded_patterns = [
    "META-INF/*",
    "*.aidl",
  ]
}
'''


def main() -> int:
    path = SRC / REL
    if not path.exists():
        print(f"missing {REL}", file=sys.stderr)
        return 1

    text = path.read_text(encoding="utf-8")
    if MARKER in text:
        print("  chrome/android/BUILD.gn (cobalt_content_dist_aar)  already applied")
        return 0

    # Appended rather than inserted at an anchor: this target belongs to Cobalt
    # and has no natural neighbour, and the end of the file is the least likely
    # place for an upstream edit to land on top of it.
    path.write_bytes((text.rstrip("\n") + "\n" + TARGET)
                     .replace("\r\n", "\n").encode("utf-8"))
    print("  chrome/android/BUILD.gn (cobalt_content_dist_aar)  patched")
    print("\nBuild it with:\n"
          "  autoninja -C out/Default -j 6 chrome/android:cobalt_content_dist_aar")
    return 0


if __name__ == "__main__":
    sys.exit(main())

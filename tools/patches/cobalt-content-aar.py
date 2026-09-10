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

**It carries no native libraries and no assets**, and that is the point of doing
it this way first. `libchrome.so` is 205 MB and the pak/ICU assets are another
70 MB; bundling them in would make every failure a twenty-minute failure and
tell us nothing extra. What is genuinely unknown is the *Java* side: whether
`dist_aar` copes with this dependency closure at all, and how much of Chromium
comes along uninvited. That is answerable in one build over Java that is already
compiled.

Adding `native_libraries` and `asset_deps` is mechanical once this works —
`cast_browser_dist_aar` shows exactly how — and is the next step rather than
this one.

## What to look at when it builds

    ls -la out/Default/apks/cobalt_content.aar
    unzip -l out/Default/apks/cobalt_content.aar | tail -5

The size and the class count are the answer. A closure that drags in most of
`chrome/android` would mean the seam is in the wrong place and the AAR should be
cut lower.

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
  deps = [
    # The embedding surface: WebContents, NavigationController,
    # BrowserStartupController.
    "//content/public/android:content_full_java",

    # ContentView and ContentViewRenderView -- the SurfaceView that
    # modules/app/.../content/ContentSurface.kt already stands in for.
    "//components/embedder_support/android:content_view_java",
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

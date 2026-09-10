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

It carries `libchrome.so`, at 205 MB, taking the artifact to **260 MB**.

A Cobalt-owned `libcobalt` was built and then removed; see the note in the
target and `docs/shell-integration.md`. Short version: it builds, its JNI
registration comes out byte-identical to `libchrome`'s, and it therefore earns
nothing while doubling a 205 MB link.

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

# The first line of Cobalt's appended block, and the handle used to find it
# again so the block can be rewritten in place. Must match TARGET's opening.
BANNER = "# Cobalt: the content layer, packaged for a Gradle consumer."

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
# NOTE: there is no separate "libcobalt" here, and there was, briefly.
#
# The plan was a Cobalt-owned shared_library whose JNI registration was
# generated from the Java this AAR ships, rather than from chrome_public_apk's.
# It builds -- chrome_common_shared_library accepts it and links a 205 MB
# libcobalt.so in 46 seconds -- and it achieves **nothing**:
#
#   libcobalt__jni_registration.srcjar   642860 bytes   md5 1dbf7ae5...
#   libchrome__jni_registration.srcjar   642860 bytes   md5 1dbf7ae5...
#
# Byte-identical. Pointing java_targets at the AAR changed no part of the
# generated registration, because a shared library's JNI surface is determined
# by the C++ it contains, not by the Java it is told about. Cobalt wants
# Chrome's browser layer -- extensions live there -- so it necessarily wants
# Chrome's JNI surface.
#
# Which also undermines the problem it was meant to solve: jni_zero defaults to
# add_stubs_for_missing_jni = true and remove_uncalled_jni = true
# (third_party/jni_zero/jni_zero.gni:597), so a registration already tolerates
# Java that is not present. The startup failure predicted here may simply not
# happen. It is still untested, and the only honest test is loading the library
# from the Gradle app.
#
# So the second library is gone rather than kept: it doubled a 205 MB link for
# no measured benefit. If the startup test does find a real mismatch, this is
# where the fix goes, and the removed target is in git history.
# NOTE: the JNI registration is NOT here, and cannot be.
#
# jni_zero generates org.jni_zero.GEN_JNI and J.N -- the classes libchrome.so
# registers its native methods against -- into
# out/.../libchrome__jni_registration.srcjar. Wrapping that in an
# android_library and adding it to the AAR does not work: every Java target
# *filters GEN_JNI out of its main jar on purpose*, keeping it in a separate
# .compliment.jar for d8, so that exactly one copy reaches the APK
# (build/config/android/internal_rules.gni:3965). dist_aar packages main jars,
# so it gets J.N and never GEN_JNI, and the library loads and then:
#
#   java.lang.NoClassDefFoundError: Failed resolution of: Lorg/jni_zero/GEN_JNI;
#
# So the registration goes where BuildConfig and NativeLibraries went: into the
# app's own source, copied there by tools/build/export-aar.sh. That is the right
# home for the same reason -- "exactly one copy per APK" is a fact about the
# APK, and Cobalt's Gradle app is the APK.
dist_aar("cobalt_content_dist_aar") {
  # The engine itself.
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

    # Chrome's Java, and it turns out not to be optional.
    #
    # libchrome.so is Chrome's browser layer -- which is the point, since
    # extensions live there -- and Chrome's C++ calls Chrome's Java during
    # startup. Without this, ContentMain::start gets as far as:
    #
    #   ClassNotFoundException:
    #     org/chromium/chrome/browser/app/flags/ChromeCachedFlags
    #       at J.N.M1Y_XVCN(Native Method)
    #       at GEN_JNI.org_chromium_content_app_ContentMain_start
    #
    # This is the mismatch predicted several steps back, in its true form. It
    # was never about JNI *registration* -- that was a separate, real problem,
    # fixed by turning multiplexing off and compiling GEN_JNI into the app. It
    # is that a browser layer and its Java are one thing.
    #
    # It costs the "zero org/chromium/chrome classes" property the first spike
    # measured. That was a pleasing number measuring the wrong property: what
    # matters is that Cobalt does not *use* Chrome's interface, not that the
    # artifact never contains it. R8 strips what the app never reaches.
    # NOT chrome_java: chrome_all_java.
    #
    # chrome_java is one library among the ~40 that make up Chrome's Java.
    # The APK depends on the java_group `chrome_all_java`
    # (chrome/android/chrome_public_apk_tmpl.gni:452), and the pieces it adds
    # are the `internal_java` halves of features whose public interfaces
    # chrome_java already has -- data_sharing, tabmodel, hub, settings, the
    # autofill and password_manager internals. Chrome's C++ calls straight into
    # those implementations during startup, so chrome_java alone gets as far as:
    #
    #   jni_zero.cc:38 Failed to find class
    #     org/chromium/components/data_sharing/DataSharingNetworkLoaderImpl
    #   Fatal signal 5 (SIGTRAP)
    #
    # Those targets restrict their `visibility` to chrome_all_java, which is the
    # build saying the same thing: they are reached through the group or not at
    # all. Depending on the group is also what keeps this correct as the list
    # changes upstream, rather than a copy of it that rots.
    "//chrome/android:chrome_all_java",



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

  jar_excluded_patterns = [
    # Signatures and build metadata are not API and only conflict in a
    # consumer's merge step. Same exclusion cast_browser_dist_aar uses.
    "META-INF/*",
    "*.aidl",

    # Everything below is a library the consuming app already has, and shipping
    # a second copy is a hard build failure rather than a waste:
    #
    #   Duplicate class _COROUTINE.ArtificialStackFrames found in modules
    #   cobalt-content.aar and kotlinx-coroutines-core-jvm-1.9.0.jar
    #
    # Cobalt's app needs androidx and Kotlin from Maven for Compose, so the AAR
    # is the copy that gives way. Guava and protobuf are deliberately NOT
    # excluded -- Chromium's Java uses them and the app does not depend on them
    # otherwise, so the AAR is their only source.
    #
    # This leaves a real risk worth naming: Chromium's Java was compiled against
    # Chromium's androidx, and now runs against the app's. A version skew shows
    # up at runtime, not here.
    "androidx/*",
    "android/support/*",
    "kotlin/*",
    "kotlinx/*",
    "_COROUTINE/*",
    "org/jetbrains/annotations/*",
    "org/intellij/*",
    # jspecify arrives with androidx.core once the app's version is forced up
    # to match Chromium's, so the AAR's copy collides the same way.
    "org/jspecify/*",

    # Chrome's Java brings okio and okhttp, and the app already has both --
    # OkHttpPageLoader is the 0.1.0 document engine's fetcher.
    "okio/*",
    "okhttp3/*",
  ]

  # Resources are included, and they have to be.
  #
  # They were excluded at first, because dist_aar merges androidx's resource
  # zips in with Chromium's and AGP then rejects the duplicates:
  #
  #   [attr/elevation] values_11.xml [attr/elevation] values_19.xml:
  #   Error: Duplicate resources
  #
  # But Chromium's Java references its own R classes, so excluding them only
  # moves the failure to runtime, where it is worse:
  #
  #   NoClassDefFoundError: Failed resolution of: Lorg/chromium/ui/R$integer;
  #     at DeviceFormFactor.detectScreenWidthBucket
  #
  # The duplicates are resolved on the way out instead, in
  # tools/build/flatten-aar-res.py, which merges the values files and keeps the
  # first definition of each (type, name). They cannot be filtered here: by the
  # time dist_aar runs, every source has already been merged into
  # values_<n>.xml and androidx's are indistinguishable from Chromium's.
}
'''


def main() -> int:
    path = SRC / REL
    if not path.exists():
        print(f"missing {REL}", file=sys.stderr)
        return 1

    text = path.read_text(encoding="utf-8")

    # Rewritten rather than skipped when it is already there.
    #
    # The usual "marker present, nothing to do" would freeze whatever version of
    # this target the tree happened to receive first, and it has changed several
    # times -- the deps list most of all. A patch that cannot correct its own
    # previous output is a patch that quietly keeps building a stale artifact,
    # which is a failure this project has already paid for once.
    #
    # Safe because the block is self-delimiting: it is appended last, opens with
    # BANNER and runs to the end of the file, so replacing from BANNER onwards
    # cannot touch anything upstream owns.
    banner_at = text.find(BANNER)
    state = "patched"
    if banner_at != -1:
        if text[banner_at:] == TARGET.lstrip("\n"):
            print("  chrome/android/BUILD.gn (cobalt_content_dist_aar)  "
                  "already applied")
            return 0
        text = text[:banner_at]
        state = "rewritten"
    elif MARKER in text:
        print(f"  {REL}: {MARKER} is present but Cobalt's banner is not. "
              "Something else wrote this target; overwriting it would destroy "
              "that.", file=sys.stderr)
        return 1

    # Appended rather than inserted at an anchor: this target belongs to Cobalt
    # and has no natural neighbour, and the end of the file is the least likely
    # place for an upstream edit to land on top of it.
    path.write_bytes((text.rstrip("\n") + "\n" + TARGET)
                     .replace("\r\n", "\n").encode("utf-8"))
    print(f"  chrome/android/BUILD.gn (cobalt_content_dist_aar)  {state}")
    print("\nBuild it with:\n"
          "  autoninja -C out/Default -j 6 chrome/android:cobalt_content_dist_aar")
    return 0


if __name__ == "__main__":
    sys.exit(main())

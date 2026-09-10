#!/usr/bin/env python3
"""Put the embedder's compositor view into the build Cobalt actually ships.

`ContentViewRenderView` is the Android `View` a `WebContents` composites into --
the piece `docs/shell-integration.md` names as the thing Cobalt's Compose
`AndroidView` wraps. It is Chromium's own, in
`//components/embedder_support/android`, and it is the supported way for an
embedder to put a page on screen.

**Chrome does not use it.** Chrome composites through `CompositorViewHolder` in
`chrome/android`, which is the layer Cobalt is deliberately not taking, so
neither half of `ContentViewRenderView` is in `chrome_public_apk`'s graph:

    $ nm -D --defined-only out/Default/libchrome.so | grep -c ContentViewRenderView
    0
    $ strings out/Default/libchrome.so | grep -c ContentViewRenderView
    0

That absence is quiet and would stay quiet until runtime. Adding only the Java
gets a class whose every native method is a jni_zero stub, and calling one
aborts the process.

## Both halves, and why both edits are needed

**`//components/embedder_support/android:view`** is the C++
(`content_view_render_view.cc`). It goes into `libchrome`, the shared library
the AAR ships.

**`:view_java`** goes into `chrome_all_java`, and it has to be *there*
specifically rather than only in the AAR's own deps. jni_zero generates
`GEN_JNI` from `java_targets = [ "//chrome/android:chrome_public_apk" ]`
(chrome/android/BUILD.gn, `libchrome_impl`), so a Java class outside that graph
gets no entry in `GEN_JNI` no matter what the AAR carries -- and `GEN_JNI` is
the one class the app compiles from Chromium's generated source. Adding it to
the group puts it in both graphs at once, which is the only arrangement where
the Java, the registration and the native code agree.

**And `libmonochrome` needs the same C++**, which is not an afterthought but the
rule stated once more. `chrome_all_java` feeds monochrome's Java graph too, so
adding the class there put it in front of a second native library, and jni_zero
checks exactly this and refuses:

    Failed JNI assertion!
    We reference Java files which use JNI, but our native library does not
    depend on the corresponding generate_jni().
    Excess Java files:
    ../../components/embedder_support/android/java/src/org/chromium/
    components/embedder_support/view/ContentViewRenderView.java

Which is the check doing its job. The invariant is "every native library that
sees this Java links this C++", and there are two of them.

Cost to the normal build: `chrome_public_apk` gains one small Java class and
each library gains one small object file, none of which Chrome's own code
references. That is a real cost and it is the right side to pay it on -- the
alternative is a second shared library, which was already tried and produced a
byte-identical JNI registration for a doubled 205 MB link.

Idempotent; every edit asserts.
"""
import sys
from pathlib import Path

SRC = Path(sys.argv[1] if len(sys.argv) > 1 else "/opt/cobalt/chromium/m140/src")

REL = "chrome/android/BUILD.gn"

DONE = "Cobalt: the embedder's compositor view"

# In java_group("chrome_all_java").
#
# Anchored on tabwindow rather than the alphabetical neighbour this sorts after:
# several of these targets are listed in more than one group in this file, and
# an anchor that matches twice cannot be trusted to land in the right group.
# tabwindow appears exactly once.
JAVA_ANCHOR = '      "//chrome/browser/tabwindow/internal:java",\n'
JAVA_ADDED = (
    '      # Cobalt: the embedder\'s compositor view, the Android View a\n'
    '      # WebContents draws into. Chrome composites through\n'
    '      # CompositorViewHolder instead and never pulls this in, so without\n'
    '      # it ContentViewRenderView is absent from GEN_JNI -- which is\n'
    '      # generated from chrome_public_apk\'s Java graph, and this group is\n'
    '      # how a class gets into that graph.\n'
    '      "//components/embedder_support/android:view_java",\n'
)

# In libchrome_impl("libchrome").
#
# `module_descs = chrome_module_descs` alone appears three times in this file --
# monochrome and the test library set it too -- so the anchor carries the
# comment that follows it in this one block. It has to be an anchor *after* the
# conditional `deps = [ ":libchrome_inputs" ]` above it: inserting before that
# would have the conditional overwrite what this adds.
NATIVE_ANCHOR = """    module_descs = chrome_module_descs

    # Java and native targets form two independent compile graphs. Deps from java targets
"""
NATIVE_ADDED = (
    "    # Cobalt: the embedder's compositor view, native half\n"
    "    # (content_view_render_view.cc). Chrome does not link it -- it\n"
    "    # composites through chrome/android's CompositorViewHolder -- so\n"
    "    # libchrome.so contains no ContentViewRenderView symbol at all and the\n"
    "    # Java above would find only jni_zero stubs.\n"
    "    #\n"
    "    # `deps` rather than `deps +=`: the assignments in this block are both\n"
    "    # inside conditionals, so there may be no list to add to.\n"
    "    if (defined(deps)) {\n"
    "      deps += [ \"//components/embedder_support/android:view\" ]\n"
    "    } else {\n"
    "      deps = [ \"//components/embedder_support/android:view\" ]\n"
    "    }\n"
    "\n"
)


# In libmonochrome_tmpl. Unlike libchrome's, this block's `deps` is assigned
# unconditionally, so this appends to it.
MONO_ANCHOR = """    deps = [
      \"//android_webview\",
      \"//base:jni_onload\",
      \"//components/crash/android:crashpad_main\",
"""
MONO_ADDED = (
    "      # Cobalt: the same C++ as libchrome above. chrome_all_java feeds\n"
    "      # monochrome's Java graph too, so this library also sees\n"
    "      # ContentViewRenderView, and jni_zero refuses a library that has the\n"
    "      # Java without the generate_jni() behind it.\n"
    '      "//components/embedder_support/android:view",\n'
)


def main() -> int:
    path = SRC / REL
    if not path.exists():
        print(f"missing {REL}", file=sys.stderr)
        return 1

    text = path.read_text(encoding="utf-8")
    if DONE in text or "embedder_support/android:view_java" in text:
        print("  chrome/android/BUILD.gn (ContentViewRenderView)          "
              "already applied")
        return 0

    for name, anchor in (("chrome_all_java", JAVA_ANCHOR),
                         ("libchrome", NATIVE_ANCHOR),
                         ("libmonochrome_tmpl", MONO_ANCHOR)):
        if anchor not in text:
            print(f"  {REL}: the {name} anchor is gone; refusing to guess "
                  f"where to add this", file=sys.stderr)
            return 1
        if text.count(anchor) != 1:
            print(f"  {REL}: the {name} anchor appears {text.count(anchor)} "
                  "times and is ambiguous", file=sys.stderr)
            return 1

    text = text.replace(JAVA_ANCHOR, JAVA_ANCHOR + JAVA_ADDED, 1)
    text = text.replace(NATIVE_ANCHOR, NATIVE_ADDED + NATIVE_ANCHOR, 1)
    text = text.replace(MONO_ANCHOR, MONO_ANCHOR + MONO_ADDED, 1)

    path.write_bytes(text.replace("\r\n", "\n").encode("utf-8"))
    print("  chrome/android/BUILD.gn (ContentViewRenderView)          patched")
    print("\nContentViewRenderView is now in both graphs: Java in "
          "chrome_all_java, C++ in libchrome and libmonochrome.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

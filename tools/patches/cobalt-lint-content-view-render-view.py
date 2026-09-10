#!/usr/bin/env python3
"""Lint has never seen `ContentViewRenderView`, and now Chrome's build does.

`cobalt-content-view-render-view.py` puts `ContentViewRenderView` into
`chrome_all_java`, which is also where Chrome's lint gets its sources. The file
then fails `NewApi` five times:

    ContentViewRenderView.java:98: Error: Call requires API level 31
    (current min is 29): android.view.Window#getRootSurfaceControl [NewApi]
    ...:100: Call requires API level 35: AttachedSurfaceControl#getInputTransferToken
    ...:113: Call requires API level 35: InputTransferHandler
    ...:116: Call requires API level 35: getMap
    ...:151: Call requires API level 35: remove

and Chromium's lint step runs `--warnings-as-errors`, so the whole APK build
stops.

## Why upstream never hit this

`enable_lint` is opt-in (`build/config/android/rules.gni`), and the one target
that compiles this file -- `content_shell_apk` -- does not set it. So these
five have never been linted by anyone; they are not a regression Cobalt caused,
they are a check running on this file for the first time.

## Why suppressing is the right answer here, and not in general

The calls are guarded:

    if (InputUtils.isTransferInputToVizSupported() && window != null) {
        AttachedSurfaceControl rootSurfaceControl = window.getRootSurfaceControl();

`isTransferInputToVizSupported()` is answered by native
(`components/input/android`), which is where the platform capability is
actually determined. Lint reads Java and cannot follow a JNI call, so it sees an
unguarded API-35 call inside what is, at runtime, a version gate. That is a
false positive of exactly the kind the `NewApi` block in this file already
collects -- and Chromium's own rule there is "do not add new suppressions
without rationale", not "do not add suppressions".

**This is narrow on purpose.** The entry names this one file by its full path,
so an unguarded new-API call anywhere else still fails the build. A blanket
`NewApi` suppression would have been shorter and would have switched the check
off for all of Chrome.

It does suppress `NewApi` for the whole file rather than for the five calls, and
that is what the mechanism supports: an `<ignore regexp>` matches the finding's
path or its message, never the two joined. A first attempt joined them and
matched neither.

The risk that remains, stated plainly: if the native gate is ever wrong -- if
`isTransferInputToVizSupported()` returns true below API 35 -- this crashes at
runtime on old devices instead of failing here. That is upstream's invariant to
keep, and it is the same invariant `content_shell` already relies on.

Idempotent; the edit asserts.
"""
import sys
from pathlib import Path

SRC = Path(sys.argv[1] if len(sys.argv) > 1 else "/opt/cobalt/chromium/m140/src")

REL = "chrome/android/expectations/lint-suppressions.xml"

DONE = "Cobalt: ContentViewRenderView"

# The `NewApi` block, identified by the last entry already in it rather than by
# the endnote that closes it: two blocks in this file end with the same endnote
# line, so that one cannot say which block it means.
ANCHOR = (
    '    <!-- 1: TaskInfo is refactored at API 29. -->\n'
    '    <ignore regexp="Field requires API level .*`android.app.TaskInfo"/>\n'
)

# One path entry, not five per-call ones.
#
# An `<ignore regexp>` is matched against the finding's *path* or its *message*,
# never against the two joined -- both forms are already used in this file. A
# first attempt joined them (`ContentViewRenderView.java.*getRootSurfaceControl`)
# and so matched neither, and the build failed with the identical five errors.
ADDED = (
    "    <!-- 1: Cobalt: ContentViewRenderView's API 31/35 calls are guarded by\n"
    "         InputUtils.isTransferInputToVizSupported(), which is answered by\n"
    "         native and so is invisible to lint. Chrome does not compile this\n"
    "         file; Cobalt does, because it embeds content directly rather than\n"
    "         through chrome/android's CompositorViewHolder. -->\n"
    "    <ignore regexp=\"components/embedder_support/android/java/src/org/chromium"
    "/components/embedder_support/view/ContentViewRenderView.java\"/>\n"
)


def main() -> int:
    path = SRC / REL
    if not path.exists():
        print(f"missing {REL}", file=sys.stderr)
        return 1

    text = path.read_text(encoding="utf-8")
    if DONE in text:
        print("  lint-suppressions.xml (ContentViewRenderView)            "
              "already applied")
        return 0

    if ANCHOR not in text:
        print(f"  {REL}: the last entry of the NewApi block is gone; "
              "refusing to guess where these belong", file=sys.stderr)
        return 1
    if text.count(ANCHOR) != 1:
        print(f"  {REL}: the NewApi anchor appears {text.count(ANCHOR)} times "
              "and is ambiguous", file=sys.stderr)
        return 1

    text = text.replace(ANCHOR, ANCHOR + ADDED, 1)
    path.write_bytes(text.replace("\r\n", "\n").encode("utf-8"))
    print("  lint-suppressions.xml (ContentViewRenderView)            patched")
    return 0


if __name__ == "__main__":
    sys.exit(main())

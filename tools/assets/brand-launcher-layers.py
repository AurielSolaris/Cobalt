#!/usr/bin/env python3
"""Brand the launcher icon layers the first pass missed.

The Android launcher icon is not one image. AndroidManifest points at
@drawable/ic_launcher and @drawable/ic_launcher_round, and those compose:

  ic_launcher        background @android:color/white
                     foreground @mipmap/layered_app_icon        <- was branded
                     monochrome @drawable/themed_app_icon       <- WAS NOT
  ic_launcher_round  background @mipmap/layered_app_icon_background  <- WAS NOT
                     foreground @mipmap/layered_app_icon_foreground (transparent)
                     monochrome @drawable/themed_app_icon       <- WAS NOT

Only `layered_app_icon` was replaced, so the round icon still showed Chromium's
pinwheel outright, and any launcher with themed icons turned on (Android 13+,
on by default in several skins) drew Chromium's monochrome glyph. Verified by
extracting res/mipmap-xxhdpi-v4/layered_app_icon_background from the built APK
and looking at it.

Two things this fixes:

**layered_app_icon_background** is the adaptive icon's full 108dp canvas, not a
48dp launcher bitmap -- upstream ships it at 108/162/216/324/432. And because
the round icon's foreground is a transparent shape, this layer *is* the entire
round icon, so it has to be opaque edge to edge. cobalt-icon.svg is already
authored to that spec: a 432 canvas with the mark occupying the middle 288,
which is exactly 108dp with the mark inside the 72dp safe zone. It only needs an
opaque ground behind it so the corners are not transparent.

**themed_app_icon.xml** is a monochrome vector; the system tints it, so every
path is drawn solid black and colour is ignored. Built from the same kiwi path
as the SVG, clipped to the same circle the SVG masks with, and scaled into the
27..63 box of a 90 viewport that upstream uses.

Needs rsvg-convert, same as make-icons.py. Idempotent; --check reports without
writing.
"""
import argparse
import io
import os
import re
import subprocess
import sys
import tempfile
from xml.etree import ElementTree

# Adaptive icon canvas is 108dp; these are the pixel sizes per density bucket.
BACKGROUND_SIZES = {
    "mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432,
}

# The ground behind the mark. Matches the circle fill in cobalt-icon.svg, so the
# opaque corners read as an extension of the mark rather than a second colour.
GROUND = "#0B0E14"

RES = "chrome/android/java/res_chromium_base"

THEMED_XML = """<?xml version="1.0" encoding="utf-8"?>
<!--
Cobalt's monochrome launcher icon.

Derived from branding/cobalt-icon.svg, itself derived from Kiwi Browser's
kiwi_logo_circle.svg, Copyright (c) 2022 Geometry OU, BSD 3-Clause. Neither the
Kiwi Browser name nor Geometry OU endorses Cobalt.

Monochrome drawables are tinted by the system, so the fill colour here is only a
placeholder; what matters is the silhouette. The group maps the icon's native
432 canvas onto the 27..63 box of a 90 viewport, which is the inset upstream
uses, and the clip-path is the same circle cobalt-icon.svg masks the mark with.

(No double hyphens in this comment: XML forbids them inside comments, and the
resource parser rejects the whole file rather than warning.)
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="90dp"
    android:height="90dp"
    android:viewportWidth="90"
    android:viewportHeight="90">
    <group
        android:scaleX="0.0833333"
        android:scaleY="0.0833333"
        android:translateX="27"
        android:translateY="27">
        <clip-path
            android:pathData="M216,72a144,144 0 1,0 0.1,0z"/>
        <path
            android:fillType="evenOdd"
            android:pathData="%(kiwi)s"
            android:fillColor="#000000"/>
    </group>
</vector>
"""


def rasterise_background(svg: str, out: str, size: int) -> None:
    """Render the SVG onto an opaque ground at `size` square."""
    subprocess.run(
        ["rsvg-convert",
         "-w", str(size), "-h", str(size),
         "-b", GROUND,          # background-color: fills the transparent corners
         "-o", out, svg],
        check=True, capture_output=True)


def kiwi_path(svg_text: str) -> str:
    m = re.search(
        r'<path fill-rule="evenodd" clip-rule="evenodd" d="([^"]+)" fill="#78A9FF"',
        svg_text)
    if not m:
        raise SystemExit("could not find the kiwi path in cobalt-icon.svg")
    return m.group(1)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=os.environ.get(
        "SRC", "/opt/cobalt/chromium/m140/src"))
    ap.add_argument("--repo", default=os.path.dirname(os.path.dirname(
        os.path.dirname(os.path.abspath(__file__)))))
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()

    svg = os.path.join(a.repo, "branding", "cobalt-icon.svg")
    if not os.path.isfile(svg):
        print(f"missing {svg}", file=sys.stderr)
        return 1

    if not a.check and not shutil_which("rsvg-convert"):
        print("rsvg-convert not found (apt install librsvg2-bin)", file=sys.stderr)
        return 1

    rc = 0

    print("=== adaptive background (the whole round icon)")
    for density, size in BACKGROUND_SIZES.items():
        rel = f"{RES}/mipmap-{density}/layered_app_icon_background.png"
        dest = os.path.join(a.src, rel)
        if not os.path.isfile(dest):
            print(f"  MISSING FILE  {rel}")
            rc = 2
            continue
        if a.check:
            print(f"  would rebrand  mipmap-{density} ({size}px)")
            continue
        with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as tmp:
            tmp_path = tmp.name
        try:
            rasterise_background(svg, tmp_path, size)
            new = open(tmp_path, "rb").read()
        finally:
            os.unlink(tmp_path)
        if open(dest, "rb").read() == new:
            print(f"  already branded  mipmap-{density}")
            continue
        with open(dest, "wb") as fh:
            fh.write(new)
        print(f"  rebranded  mipmap-{density} ({size}px, {len(new)} bytes)")

    print("=== monochrome / themed icon")
    themed = os.path.join(a.src, RES, "drawable", "themed_app_icon.xml")
    if not os.path.isfile(themed):
        print(f"  MISSING FILE  {themed}")
        return 2
    body = THEMED_XML % {"kiwi": kiwi_path(
        io.open(svg, encoding="utf-8").read())}

    # Parse before writing. A malformed resource does not fail loudly at the
    # point of the mistake: prepare_resources.py dies hundreds of steps into the
    # build with an ElementTree traceback and a line number in a generated tree.
    # This caught a "--" inside an XML comment, which XML forbids.
    try:
        ElementTree.fromstring(body)
    except ElementTree.ParseError as exc:
        print(f"  REFUSED  themed_app_icon.xml is not well-formed: {exc}",
              file=sys.stderr)
        return 1
    current = io.open(themed, encoding="utf-8").read()
    if current == body:
        print("  already branded  themed_app_icon.xml")
    elif a.check:
        print("  would rebrand  themed_app_icon.xml")
    else:
        io.open(themed, "w", encoding="utf-8", newline="\n").write(body)
        print("  rebranded  themed_app_icon.xml")

    return rc


def shutil_which(name):
    from shutil import which
    return which(name)


if __name__ == "__main__":
    sys.exit(main())

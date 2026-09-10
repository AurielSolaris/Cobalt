#!/usr/bin/env python3
"""Give the adaptive icon a real foreground instead of a copy of the square one.

`brand-chromium.py` writes the same generated PNG to both `app_icon.png` and
`layered_app_icon.png`. That is wrong twice over.

**It fails the build.** Android lint refuses byte-identical icons, and the lint
step treats any output at all as failure:

    layered_app_icon.png: Warning: The following unrelated icon files have
    identical contents: app_icon.png, layered_app_icon.png [IconDuplicates]
    Command failed because it wrote to stdout.

**And it is not what an adaptive foreground is.** `app_icon.png` is the legacy
square launcher icon, drawn edge to edge. `layered_app_icon.png` is the
foreground *layer* of an adaptive icon: the launcher masks it to whatever shape
the device uses -- circle, squircle, rounded square -- and animates it against
its background. Android reserves the outer ring for that, guaranteeing only the
centre **66 of 108 dp** is always visible. Artwork drawn to the edges gets its
corners cut off by the mask.

So the foreground is the same artwork inset into the safe zone on a transparent
canvas. That makes the two files differ, which satisfies lint, but the reason to
do it is that it is the correct icon.

Runs after brand-chromium.py in the series, so it overwrites what that copied.
Idempotent: an already-inset foreground has transparent corners, and this checks
for that rather than re-scaling a scaled icon into oblivion.
"""

import argparse
import os
import sys

try:
    from PIL import Image
except ImportError:
    print("needs Pillow (pip install Pillow)", file=sys.stderr)
    raise SystemExit(1)

BUCKETS = ["mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"]

# Android's adaptive icon geometry: a 108dp canvas of which the centre 66dp is
# the guaranteed-visible safe zone.
SAFE_FRACTION = 66.0 / 108.0


def is_copy_of(layered: str, square: str) -> bool:
    """True while the foreground is still a byte copy of the square icon.

    This is the test, not "do the corners look transparent". The first version
    of this script checked corner alpha and reported every bucket as already
    inset, because Cobalt's source icon has transparent corners too -- so the
    heuristic was answering a different question from the one lint asks.

    Lint's complaint is exact and so is this: identical contents. It also makes
    the script idempotent for free, since an inset foreground is no longer a
    copy.
    """
    if not os.path.isfile(square):
        return False
    return open(square, "rb").read() == open(layered, "rb").read()


def inset(src_path: str, dest_path: str) -> str:
    with Image.open(src_path) as im:
        img = im.convert("RGBA")
        w, h = img.size
        inner = (max(1, int(round(w * SAFE_FRACTION))),
                 max(1, int(round(h * SAFE_FRACTION))))
        scaled = img.resize(inner, Image.LANCZOS)

        canvas = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        canvas.paste(scaled, ((w - inner[0]) // 2, (h - inner[1]) // 2), scaled)
        canvas.save(dest_path, "PNG")
    return "inset"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=os.environ.get(
        "SRC", "/opt/cobalt/chromium/m140/src"))
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()

    resdir = os.path.join(a.src, "chrome/android/java/res_chromium_base")
    if not os.path.isdir(resdir):
        print("no res_chromium_base at %s" % resdir, file=sys.stderr)
        return 1

    rc = 0
    for bucket in BUCKETS:
        square = os.path.join(resdir, "mipmap-%s" % bucket, "app_icon.png")
        layered = os.path.join(resdir, "mipmap-%s" % bucket,
                               "layered_app_icon.png")
        if not os.path.isfile(layered):
            print("  %-10s no layered_app_icon.png" % bucket)
            continue

        if a.check:
            state = ("IDENTICAL TO app_icon.png"
                     if is_copy_of(layered, square) else "inset")
            print("  %-10s %s" % (bucket, state))
            if state.startswith("IDENTICAL"):
                rc = 1
            continue

        if not is_copy_of(layered, square):
            print("  %-10s already inset" % bucket)
            continue

        print("  %-10s %s" % (bucket, inset(layered, layered)))

    if not a.check:
        print("\nAdaptive foreground inset to the 66/108 safe zone; it is no "
              "longer a copy of app_icon.png.")
    return rc


if __name__ == "__main__":
    sys.exit(main())

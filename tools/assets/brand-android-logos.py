#!/usr/bin/env python3
"""Replace the Chromium logos Android still shows after the launcher icon.

brand-chromium.py handles the launcher icon and the product name. It does not
touch two asset families that are shipped separately, so a build that looks
branded on the home screen still shows the Chromium pinwheel the moment it
starts:

  fre_product_logo.png   square mark on the splash and first-run screens
  product_logo_name.png  mark plus wordmark, used by the lightweight ToS screen

Both live under components/browser_ui/styles/android/java/res_chromium/ at five
densities. Sizes are read from the files being replaced rather than assumed, so
a density added upstream is picked up instead of silently skipped.

The wordmark needs a typeface. Lato is used, and its presence is asserted --
rsvg silently substitutes a default when a family is missing, which produces a
wordmark that renders fine and looks nothing like the brand.

Usage:
    brand-android-logos.py [--src CHROMIUM_SRC] [--check]
"""
import argparse
import base64
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
ICON = REPO / "branding" / "cobalt-icon.svg"

RES = "components/browser_ui/styles/android/java/res_chromium"
DENSITIES = ["mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"]

GROUND = "#0B0E14"
ACCENT = "#78A9FF"
TEXT = "#F4F6FA"
FONT = "Lato"


def png_size(path: Path):
    d = path.read_bytes()
    if d[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a png: " + str(path))
    return struct.unpack(">II", d[16:24])


def have_font(name: str) -> bool:
    out = subprocess.run(["fc-list", ":", "family"], capture_output=True, text=True)
    return name.lower() in out.stdout.lower()


def rasterise(svg: Path, out: Path, w: int, h: int):
    subprocess.run(["rsvg-convert", "-w", str(w), "-h", str(h), "-o", str(out), str(svg)],
                   check=True, capture_output=True)


def icon_b64() -> str:
    """Embed the icon rather than referencing it.

    rsvg refuses external file references from an SVG by default, so an
    xlink:href to the icon on disk renders as nothing at all -- the text
    appears, the mark silently does not, and the result looks like a
    deliberate text-only wordmark.
    """
    return base64.b64encode(ICON.read_bytes()).decode("ascii")


def wordmark_svg(w: int, h: int) -> str:
    """Icon on the left, product name to its right, sized off the target box."""
    pad = h * 0.08
    icon = h - 2 * pad
    gap = h * 0.18
    text_x = pad + icon + gap
    size = h * 0.62
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" '
        'width="{w}" height="{h}" viewBox="0 0 {w} {h}">'
        '<image x="{ix}" y="{iy}" width="{s}" height="{s}" xlink:href="data:image/svg+xml;base64,{icon}"/>'
        '<text x="{tx}" y="{ty}" font-family="{font}" font-weight="600" '
        'font-size="{fs}" fill="{fill}" letter-spacing="{ls}">Cobalt</text>'
        "</svg>"
    ).format(w=w, h=h, ix=pad, iy=pad, s=icon, icon=icon_b64(),
             tx=text_x, ty=h * 0.72, font=FONT, fs=size, fill=TEXT, ls=h * 0.01)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default="/opt/cobalt/chromium/m140/src")
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()

    src = Path(args.src)
    res = src / RES
    if not res.is_dir():
        print("no resource dir: " + str(res), file=sys.stderr)
        return 1
    if not ICON.is_file():
        print("no icon: " + str(ICON), file=sys.stderr)
        return 1
    if not have_font(FONT):
        print(FONT + " is not installed; rsvg would substitute a different face "
              "and the wordmark would be wrong", file=sys.stderr)
        return 1

    written = 0
    with tempfile.TemporaryDirectory() as td:
        tmp = Path(td)
        for density in DENSITIES:
            d = res / ("drawable-" + density)
            if not d.is_dir():
                continue

            square = d / "fre_product_logo.png"
            if square.is_file():
                w, h = png_size(square)
                if args.check:
                    print("  %-10s fre_product_logo   %dx%d" % (density, w, h))
                else:
                    rasterise(ICON, square, w, h)
                    written += 1

            name = d / "product_logo_name.png"
            if name.is_file():
                w, h = png_size(name)
                if args.check:
                    print("  %-10s product_logo_name  %dx%d" % (density, w, h))
                else:
                    svg = tmp / ("wordmark_" + density + ".svg")
                    svg.write_text(wordmark_svg(w, h), encoding="utf-8")
                    rasterise(svg, name, w, h)
                    written += 1

    if args.check:
        return 0
    if written == 0:
        print("nothing written -- expected assets were not found", file=sys.stderr)
        return 1
    print("rebranded " + str(written) + " logo assets")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

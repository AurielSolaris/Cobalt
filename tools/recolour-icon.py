#!/usr/bin/env python3
"""Recolour the icon SVG by swapping named hex values.

Kept as a tool rather than a one-off edit because the palette is still being
chosen, and doing it by hand across six colours invites one getting missed.
"""
import argparse, io, sys

# The source mark is a white bird on a coloured gradient disc.  Cobalt inverts
# that -- a blue bird on a plain ground -- so the icon reads as related to Kiwi
# rather than as Kiwi.  Recolouring alone does not make a mark distinct; the
# inversion is what says "inspired by, separate from".
#
# Every value must be 6-digit hex.  SVG 1.1 does not understand 8-digit
# (#RRGGBBAA) colours -- an earlier palette used one and it parsed wrong while
# still rendering plausibly, which is the worst way for this to fail.  Opacity
# belongs in the stop-opacity attribute the source already has.
BIRD = "#FCFCFC"           # the bird's fill in the source

PALETTES = {
    # Blue bird on a near-black ground.  Cobalt's default theme is dark.
    "inverted-dark": {
        BIRD:      "#78A9FF",   # Carbon Blue 40
        "#49F3CB": "#1C2029",   # disc gradient start
        "#456ED8": "#0B0E14",   # disc gradient end
        "#00485F": "#000000",   # inner shade
        "#FF9800": "#0B0E14",   # base disc
        "#002B6D": "#000000",   # deep shade
    },
    # Blue bird on a pale ground.  Holds up better at 48px on a busy launcher.
    "inverted-light": {
        BIRD:      "#0F62FE",   # Carbon Blue 60, electric
        "#49F3CB": "#F4F4F4",   # Carbon Gray 10
        "#456ED8": "#D0DDF0",
        "#00485F": "#8D949E",
        "#FF9800": "#F4F4F4",
        "#002B6D": "#A8B4C8",
    },
    # Kept for comparison: the original arrangement, recoloured only.
    "electric": {
        "#49F3CB": "#4589FF",
        "#456ED8": "#001D6C",
        "#00485F": "#000D95",
        "#FF9800": "#0B0E14",
        "#002B6D": "#000B2E",
    },
    "ice": {
        "#49F3CB": "#82CFFF",
        "#456ED8": "#4589FF",
        "#00485F": "#0A1A2F",
        "#FF9800": "#16181C",
        "#002B6D": "#0F1B33",
    },
}

HDR = ("<!-- Cobalt icon.\n"
       "     Derived from kiwi_logo_circle.svg, Copyright (c) 2022 Geometry OU\n"
       "     (Kiwi Browser), BSD 3-Clause. Recoloured to Cobalt's palette.\n"
       "     Neither the Kiwi Browser name nor Geometry OU endorses Cobalt. -->\n")

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("dest")
    ap.add_argument("--palette", default="electric", choices=sorted(PALETTES))
    a = ap.parse_args()

    s = io.open(a.src, encoding="utf-8").read()
    # Strip any header we added before, so re-running does not stack them.
    if s.startswith("<!-- Cobalt icon."):
        s = s[s.index("-->") + 4:].lstrip("\n")

    pal = PALETTES[a.palette]
    missing = [k for k in pal if k not in s and k.lower() not in s]
    if missing:
        print("source does not contain: %s" % ", ".join(missing), file=sys.stderr)
        print("(already recoloured? pass the original SVG)", file=sys.stderr)
        return 1
    for k, v in pal.items():
        s = s.replace(k, v).replace(k.lower(), v)

    io.open(a.dest, "w", encoding="utf-8", newline="\n").write(HDR + s)
    print("wrote %s (palette: %s)" % (a.dest, a.palette))
    return 0

if __name__ == "__main__":
    sys.exit(main())

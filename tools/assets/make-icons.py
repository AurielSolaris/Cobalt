#!/usr/bin/env python3
"""Rasterise Cobalt's icon from one SVG into every size the project needs.

One vector source, everything else generated.  Hand-maintained PNGs at six
densities drift -- someone updates the 192px one and forgets mdpi, and nobody
notices until it ships blurry on a cheap phone.

Outputs:
  Android launcher     mipmap-{mdpi..xxxhdpi}/ic_launcher.png       48..192
  Android adaptive     mipmap-*/ic_launcher_foreground.png          108dp @ each density
  Play Store           play-store-icon.png                          512
  Favicon / desktop    favicon.ico                                  16,32,48,64,128,256
  README / docs        icon-256.png, icon-512.png

Needs rsvg-convert (librsvg2-bin) for fidelity -- the source uses gradients,
masks and filters, which pure-Python SVG renderers get wrong.  Pillow assembles
the .ico.

Usage:
    tools/make-icons.py branding/cobalt-icon.svg
    tools/make-icons.py branding/cobalt-icon.svg --check
"""
import argparse, hashlib, os, shutil, subprocess, sys, tempfile

# Android launcher icon: dp size 48, scaled per density bucket.
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
LAUNCHER_DP = 48
# Adaptive icons are 108dp; the inner 72dp is the safe zone, the rest can be
# cropped to whatever mask the launcher applies.
ADAPTIVE_DP = 108
ICO_SIZES = [16, 32, 48, 64, 128, 256]


def rasterise(svg, png, size):
    subprocess.run(
        ["rsvg-convert", "-w", str(size), "-h", str(size), "-o", png, svg],
        check=True, capture_output=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("svg")
    ap.add_argument("--res", default="modules/app/src/main/res",
                    help="Android res/ directory")
    ap.add_argument("--out", default="branding/generated",
                    help="non-Android outputs (store icon, favicon, docs)")
    ap.add_argument("--check", action="store_true",
                    help="regenerate to a temp dir and report drift; write nothing")
    a = ap.parse_args()

    if not shutil.which("rsvg-convert"):
        sys.exit("rsvg-convert not found — install librsvg2-bin")
    if not os.path.isfile(a.svg):
        sys.exit("no such SVG: " + a.svg)

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    res = a.res if os.path.isabs(a.res) else os.path.join(root, a.res)
    out = a.out if os.path.isabs(a.out) else os.path.join(root, a.out)

    stage = tempfile.mkdtemp(prefix="cobalt-icons-")
    made = []

    # Android launcher + adaptive foreground
    for bucket, scale in DENSITIES.items():
        d = os.path.join(stage, "res", "mipmap-" + bucket)
        os.makedirs(d, exist_ok=True)
        for name, dp in (("ic_launcher.png", LAUNCHER_DP),
                         ("ic_launcher_foreground.png", ADAPTIVE_DP)):
            px = int(round(dp * scale))
            p = os.path.join(d, name)
            rasterise(a.svg, p, px)
            made.append((os.path.join("res", "mipmap-" + bucket, name), p, px))

    # Store icon and doc sizes
    od = os.path.join(stage, "out")
    os.makedirs(od, exist_ok=True)
    for name, px in (("play-store-icon.png", 512),
                     ("icon-512.png", 512), ("icon-256.png", 256)):
        p = os.path.join(od, name)
        rasterise(a.svg, p, px)
        made.append((os.path.join("out", name), p, px))

    # favicon.ico from several rasterised sizes, so each is sharp rather than
    # downscaled from one big PNG.
    from PIL import Image
    frames = []
    for px in ICO_SIZES:
        p = os.path.join(stage, "ico-%d.png" % px)
        rasterise(a.svg, p, px)
        frames.append(Image.open(p).convert("RGBA"))
    ico = os.path.join(od, "favicon.ico")
    frames[-1].save(ico, format="ICO",
                    sizes=[(s, s) for s in ICO_SIZES])
    made.append((os.path.join("out", "favicon.ico"), ico, max(ICO_SIZES)))

    def digest(p):
        return hashlib.sha256(open(p, "rb").read()).hexdigest()[:12]

    drift = []
    for rel, src, px in made:
        dest = os.path.join(res if rel.startswith("res") else out,
                            rel.split(os.sep, 1)[1] if rel.startswith(("res", "out"))
                            else rel)
        exists = os.path.isfile(dest)
        differs = not exists or digest(dest) != digest(src)
        if differs:
            drift.append(rel)
        if not a.check and differs:
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            shutil.copy2(src, dest)
        print("  %-46s %4dpx  %s" % (
            rel, px,
            "new" if not exists else ("differs" if differs else "unchanged")))

    shutil.rmtree(stage, ignore_errors=True)
    print()
    if a.check:
        if drift:
            print("STALE: %d icon(s) differ from the SVG" % len(drift))
            print("  regenerate with: tools/make-icons.py %s" % a.svg)
            return 1
        print("all icons match the SVG")
        return 0
    print("wrote %d icon(s)" % len(drift) if drift else "nothing to do")
    return 0


if __name__ == "__main__":
    sys.exit(main())

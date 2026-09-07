#!/usr/bin/env python3
"""Bake the Google Fonts catalogue into the app at build time.

Cobalt's theme editor lets the user pick a sans and a mono face from Google
Fonts.  Fetching a *font* needs no API key -- the css2 endpoint is public -- so
the only thing that has to come from Google is the list of what exists.  This
fetches that list once, at build time, and commits it to the app's assets.

Why not call the API from the app:

  * The Google Fonts Developer API requires a key.  Cobalt is open source, so
    shipping a key publishes it, and a published key gets abused and revoked --
    taking the font picker down with it.  This uses the keyless metadata
    endpoint that fonts.google.com itself uses.
  * The picker then works offline.  Browsing fonts contacts nobody; only
    downloading a chosen face touches the network, which is what decision 0007
    promises.

At runtime the app needs no API at all.  For a chosen family it requests:

    https://fonts.googleapis.com/css2?family=<Family+Name>:wght@<weight>

and then the font file the returned CSS points at on fonts.gstatic.com.

The User-Agent on that request decides what comes back, which is easy to get
wrong:

  * A generic UA gets **one .ttf** containing every glyph.
  * A Chromium UA gets **woff2, split by unicode-range** into per-script faces
    (latin, latin-ext, cyrillic, greek, vietnamese...).

woff2 is far smaller, so the Chromium UA is what we want -- and Cobalt sends one
anyway, since it declares itself as Chromium.  The consequence is that caching a
font means caching *several* files, not one, and choosing which subsets to pull.
That is what the "s" (subsets) field in this catalogue is for: fetch latin plus
whatever matches the user's locale, not all of them.

Output is deterministic -- sorted, fixed separators -- so an unchanged catalogue
produces a byte-identical file and does not show up as build noise.

Usage:
    tools/fetch-google-fonts.py                     # write app assets
    tools/fetch-google-fonts.py --check             # fail if stale, write nothing
    tools/fetch-google-fonts.py --out some/where.json
"""
import argparse, json, os, sys, urllib.request, urllib.error

# The keyless catalogue behind fonts.google.com.  Responses are prefixed with an
# XSSI guard, ")]}'", which must be stripped before parsing.
CATALOGUE = "https://fonts.google.com/metadata/fonts"
XSSI = ")]}'"

DEFAULT_OUT = "modules/app/src/main/assets/google-fonts.json"

# Categories as Google reports them, mapped to the two roles the theme editor
# exposes.  A user picking "font.mono" must not be shown 1,700 proportional
# faces to scroll past.
MONO = {"MONOSPACE"}
SANS_ISH = {"SANS_SERIF", "SERIF", "DISPLAY", "HANDWRITING"}


def fetch(url, timeout=30):
    req = urllib.request.Request(
        url, headers={"User-Agent": "Cobalt-build/1.0 (+https://github.com/AurielSolaris/Cobalt)"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode("utf-8")


def parse(raw):
    if raw.startswith(XSSI):
        raw = raw[len(XSSI):]
    return json.loads(raw)


# Variant keys in the metadata look like "400" and "400i" -- the italic marker
# is a bare trailing "i", not the word "italic".  Matching on "italic" silently
# reports every family as having no italics, which is how this was first written.
def variants_of(entry):
    """(weights, has_italic) from the family's axis and variant metadata."""
    ws, italic = set(), False
    for axis in entry.get("axes", []) or []:
        if axis.get("tag") == "wght":
            lo, hi = int(axis.get("min", 400)), int(axis.get("max", 400))
            # A variable weight axis: expose the usual stops inside its range.
            ws.update(w for w in (100, 200, 300, 400, 500, 600, 700, 800, 900)
                      if lo <= w <= hi)
    for key in (entry.get("fonts") or {}):
        k = str(key)
        if k.endswith("i"):
            italic = True
            k = k[:-1]
        if k.isdigit():
            ws.add(int(k))
    return sorted(ws) or [400], italic


def build(data):
    fams = data.get("familyMetadataList") or data.get("familyMetadata") or []
    if not fams:
        raise SystemExit("catalogue had no family list; endpoint shape changed")

    sans, mono = [], []
    for e in fams:
        name = e.get("family")
        if not name:
            continue
        cat = (e.get("category") or "").upper().replace("-", "_").replace(" ", "_")
        weights, italic = variants_of(e)
        # "menu" is an internal pseudo-subset used for the picker's own preview
        # strings, not a script a user would filter by.
        subsets = sorted(x for x in (e.get("subsets") or []) if x != "menu")
        rec = {
            "n": name,
            "c": cat,
            "w": weights,
            "i": italic,
            # Popularity rank, so the picker can lead with fonts people use.
            # 1,895 families in alphabetical order is a list nobody scrolls.
            "p": e.get("popularity") or 9999,
            "s": subsets,
        }
        (mono if cat in MONO else sans).append(rec)

    sans.sort(key=lambda r: r["n"])
    mono.sort(key=lambda r: r["n"])
    return {
        "source": CATALOGUE,
        "css": "https://fonts.googleapis.com/css2?family={family}:wght@{weight}",
        "sans": sans,
        "mono": mono,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--check", action="store_true",
                    help="compare against the committed file; write nothing")
    a = ap.parse_args()

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out = a.out if os.path.isabs(a.out) else os.path.join(root, a.out)

    try:
        cat = build(parse(fetch(CATALOGUE)))
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        # Offline is not a build failure.  The committed catalogue is the
        # shipping artifact; a maintainer refreshes it deliberately.
        if os.path.exists(out) and not a.check:
            print("could not reach Google Fonts (%s); keeping committed catalogue" % e)
            return 0
        print("could not reach Google Fonts: %s" % e, file=sys.stderr)
        return 1

    blob = json.dumps(cat, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False) + "\n"

    old = None
    if os.path.exists(out):
        old = open(out, encoding="utf-8").read()

    print("families: %d sans, %d mono" % (len(cat["sans"]), len(cat["mono"])))
    print("size    : %.1f KB" % (len(blob.encode("utf-8")) / 1024.0))

    if a.check:
        if old is None:
            print("MISSING: %s has never been generated" % out, file=sys.stderr)
            return 1
        if old != blob:
            print("STALE: committed catalogue differs from upstream", file=sys.stderr)
            print("  refresh with: tools/fetch-google-fonts.py", file=sys.stderr)
            return 1
        print("committed catalogue is current")
        return 0

    if old == blob:
        print("unchanged: %s" % out)
        return 0

    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write(blob)
    print("wrote %s" % out)
    return 0


if __name__ == "__main__":
    sys.exit(main())

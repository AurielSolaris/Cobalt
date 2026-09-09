#!/usr/bin/env python3
"""Apply Cobalt's identity to the Chromium build.

Three separate things, only one of which needs a source edit:

  package name  a GN argument (chrome_public_manifest_package) -- set in
                args.gn, no patch at all
  visible name  two strings: app_name in the Android resources, and
                IDS_PRODUCT_NAME in chromium_strings.grd
  launcher icon PNGs under chrome/android/java/res_chromium_base, replaced
                from the rasters make-icons.py generates from our SVG

The icon files are overwritten rather than added, because chrome/android/BUILD.gn
lists each one explicitly -- adding new names would mean patching the build file,
while replacing bytes at the same paths does not.

Idempotent. --check reports without writing.
"""
import argparse, io, os, shutil, sys

APP_NAME = "Cobalt"

STRINGS = [
    dict(path="chrome/android/java/res_chromium_base/values/channel_constants.xml",
         old='<string name="app_name" translatable="false">Chromium</string>',
         new='<string name="app_name" translatable="false">Cobalt</string>'),
    dict(path="chrome/app/chromium_strings.grd",
         old='<message name="IDS_PRODUCT_NAME" desc="The Chrome application name" translateable="false">\n            Chromium\n          </message>',
         new='<message name="IDS_PRODUCT_NAME" desc="The Chrome application name" translateable="false">\n            Cobalt\n          </message>'),
]

# density -> the launcher PNG we generated for that bucket
ICONS = {
    "mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192,
}
ICON_NAMES = ["app_icon.png", "layered_app_icon.png"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=os.environ.get("SRC", "/opt/cobalt/chromium/m140/src"))
    ap.add_argument("--repo", default=os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()

    rc = 0

    print("=== strings")
    for e in STRINGS:
        p = os.path.join(a.src, e["path"])
        if not os.path.isfile(p):
            print("  MISSING FILE %s" % e["path"]); rc = 2; continue
        s = io.open(p, encoding="utf-8").read()
        if e["new"] in s:
            print("  already branded  %s" % e["path"])
        elif e["old"] in s:
            print("  %s  %s" % ("would brand" if a.check else "branding    ", e["path"]))
            if not a.check:
                io.open(p, "w", encoding="utf-8", newline="\n").write(s.replace(e["old"], e["new"], 1))
        else:
            print("  TEXT NOT FOUND  %s" % e["path"]); rc = 2

    print()
    print("=== launcher icons")
    resdir = os.path.join(a.src, "chrome/android/java/res_chromium_base")
    for bucket, px in ICONS.items():
        srcpng = os.path.join(a.repo, "modules/app/src/main/res/mipmap-%s/ic_launcher.png" % bucket)
        if not os.path.isfile(srcpng):
            print("  MISSING generated icon for %s (run tools/assets/make-icons.py)" % bucket)
            rc = 2
            continue
        for name in ICON_NAMES:
            dest = os.path.join(resdir, "mipmap-%s" % bucket, name)
            if not os.path.isfile(dest):
                continue
            same = (os.path.getsize(dest) == os.path.getsize(srcpng)
                    and open(dest, "rb").read() == open(srcpng, "rb").read())
            if same:
                print("  already branded  mipmap-%s/%s" % (bucket, name))
            else:
                print("  %s  mipmap-%s/%s (%dpx)" % ("would replace" if a.check else "replacing    ", bucket, name, px))
                if not a.check:
                    shutil.copyfile(srcpng, dest)

    print()
    print("=== also set in args.gn (no patch needed)")
    print('  chrome_public_manifest_package = "app.auriel.cobalt"')
    return rc


if __name__ == "__main__":
    sys.exit(main())

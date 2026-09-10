#!/usr/bin/env python3
"""Repair a dist_aar's resource layout so AAPT will accept it.

`dist_aar` writes resources as `res/<n>_res/<type>/...`, one numbered directory
per input resource zip — `build/android/gyp/dist_aar.py`, `_AddResources`, which
enumerates them. An AAR's resources have to be `res/<type>/...`, and AAPT
rejects the numbered form outright:

    ERROR: AAPT: .../res/0_res/anim: error: resource file cannot be a directory.

So the prefix is stripped on the way into the Gradle project.

**This is only safe while there is exactly one index.** Two numbered directories
flattened into one would collide on any name they share, silently, and the
loser would be whichever was written second. So the count is checked and this
refuses rather than guesses.

Usage:  flatten-aar-res.py <input.aar> <output.aar>
"""

import re
import shutil
import sys
import zipfile

PREFIX = re.compile(r"^res/(\d+)_res/")


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__.strip().splitlines()[-1], file=sys.stderr)
        return 2

    src, dst = sys.argv[1], sys.argv[2]

    with zipfile.ZipFile(src) as z:
        names = z.namelist()
        indices = {m.group(1) for m in (PREFIX.match(n) for n in names) if m}

        if len(indices) > 1:
            print("REFUSED: %d resource index directories (%s). Flattening them "
                  "would collide on shared names. dist_aar's layout has changed "
                  "and this needs rethinking rather than forcing."
                  % (len(indices), ", ".join(sorted(indices))), file=sys.stderr)
            return 1

        if not indices:
            shutil.copyfile(src, dst)
            print("  no res/<n>_res/ prefix to strip")
            return 0

        moved = 0
        with zipfile.ZipFile(dst, "w") as out:
            for info in z.infolist():
                # Copy the entry's own header rather than letting zipfile pick,
                # so each keeps its original compression. dist_aar stores
                # libchrome.so uncompressed; deflating it here would spend a
                # couple of minutes per export shrinking 205 MB that AGP
                # re-expands anyway when it packages the APK.
                out_info = zipfile.ZipInfo(info.filename, info.date_time)
                out_info.compress_type = info.compress_type
                out_info.external_attr = info.external_attr
                if PREFIX.match(out_info.filename):
                    out_info.filename = PREFIX.sub("res/", out_info.filename)
                    moved += 1
                out.writestr(out_info, z.read(info))

    print("  flattened %d resource entries out of res/%s_res/"
          % (moved, indices.pop()))
    return 0


if __name__ == "__main__":
    sys.exit(main())

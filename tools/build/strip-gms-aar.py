#!/usr/bin/env python3
"""Write a copy of the Chromium AAR with Google Play Services removed.

    python tools/build/strip-gms-aar.py            # libs/cobalt-content.aar -> libs/cobalt-content-nogms.aar
    ./gradlew :modules:app:assembleDebug -Pcobalt.noGms

The experiment behind docs/gms-removal.md's "What Cobalt uses": Cobalt's
manifest declares no Google component at all (every service in it is
Chromium's sandboxed renderer), so Play Services code runs only when Cobalt's
own code calls it. Remove the classes, use the browser, and every
NoClassDefFoundError names something Cobalt actually needs; everything that
never fails is something it does not.

Removed: com.google.android.gms, com.google.firebase, and
com.google.android.datatransport (the uploader Firebase and Play Services
logging send through). Nothing else in the AAR is touched, and the original is
left as it is.
"""
import io
import sys
import zipfile
from pathlib import Path

STRIP = (
    "com/google/android/gms/",
    "com/google/firebase/",
    "com/google/android/datatransport/",
)


def strip_jar(data: bytes) -> tuple[bytes, int, int]:
    out = io.BytesIO()
    removed = removed_bytes = 0
    with zipfile.ZipFile(io.BytesIO(data)) as src, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as dst:
        for info in src.infolist():
            if info.filename.startswith(STRIP):
                removed += 1
                removed_bytes += info.file_size
                continue
            dst.writestr(info, src.read(info))
    return out.getvalue(), removed, removed_bytes


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    source = root / "modules/app/libs/cobalt-content.aar"
    target = root / "modules/app/libs/cobalt-content-nogms.aar"
    if not source.exists():
        print(f"no AAR at {source}; run tools/build/export-aar.sh first", file=sys.stderr)
        return 1
    with zipfile.ZipFile(source) as src, zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as dst:
        for info in src.infolist():
            data = src.read(info)
            if info.filename == "classes.jar":
                data, removed, removed_bytes = strip_jar(data)
                print(f"classes.jar: removed {removed} classes ({removed_bytes / 1e6:.1f} MB)")
            # Stored entries stay stored: the native library must remain
            # uncompressed to be mapped straight out of the APK.
            dst.writestr(info, data, compress_type=info.compress_type)
    print(f"wrote {target}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

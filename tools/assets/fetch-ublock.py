#!/usr/bin/env python3
"""Fetch uBlock Origin for bundling, and verify it is what we expect.

Cobalt preinstalls uBO (decision 0006). The build must not depend on whatever
happens to be latest at the time it runs, so the version is pinned here and the
download is checked against a recorded hash. An unpinned bundled extension is a
supply-chain hole: it would let a future release change what ships inside
Cobalt without any change to Cobalt.

The MV2 package is deliberate. uBO's MV2 build is the full-strength one, and
keeping MV2 working is the reason this project exists -- see decision 0005.
uBO Lite (MV3) is a different, weaker extension and is not a substitute.

uBO is GPLv3. It is bundled unmodified and its LICENSE ships with it; Cobalt's
own BSD-3 code is not affected by aggregating a separate GPL program.

Usage:
    fetch-ublock.py [--out DIR] [--version V] [--record]

--record prints the hash for pasting back into EXPECTED_SHA256, and is how the
pin is established the first time or deliberately moved.
"""
import argparse
import hashlib
import json
import shutil
import sys
import urllib.request
import zipfile
from pathlib import Path

VERSION = "1.74.0"

# Established with --record. A mismatch means the bytes behind this URL changed,
# which for a tagged release should never happen: treat it as a supply-chain
# event, not as a stale pin to refresh.
EXPECTED_SHA256 = "29a475e82688b304f2a9b2c0577c2655a8aaf75ce363fe02d720d389bbb9f451"

URL = ("https://github.com/gorhill/uBlock/releases/download/"
       "{v}/uBlock0_{v}.chromium.zip")

UA = "cobalt-build (+https://github.com/AurielSolaris/Cobalt)"


def fetch(version: str, dest: Path) -> Path:
    url = URL.format(v=version)
    print("fetching " + url)
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    zip_path = dest / ("uBlock0_" + version + ".chromium.zip")
    with urllib.request.urlopen(req, timeout=120) as r, zip_path.open("wb") as f:
        shutil.copyfileobj(r, f)
    return zip_path


def verify(zip_path: Path, version: str, record: bool) -> bool:
    digest = hashlib.sha256(zip_path.read_bytes()).hexdigest()
    print("sha256  " + digest)
    if record:
        print()
        print("paste into EXPECTED_SHA256:")
        print('EXPECTED_SHA256 = "' + digest + '"')
    elif EXPECTED_SHA256 and digest != EXPECTED_SHA256:
        print("HASH MISMATCH", file=sys.stderr)
        print("  expected " + EXPECTED_SHA256, file=sys.stderr)
        print("  got      " + digest, file=sys.stderr)
        return False
    elif not EXPECTED_SHA256:
        print("WARNING: no pin recorded; run with --record", file=sys.stderr)

    if not zipfile.is_zipfile(zip_path):
        print("not a zip", file=sys.stderr)
        return False

    with zipfile.ZipFile(zip_path) as z:
        bad = z.testzip()
        if bad is not None:
            print("corrupt member: " + bad, file=sys.stderr)
            return False
        names = z.namelist()
        root = "uBlock0.chromium/"
        manifest_name = root + "manifest.json" if root + "manifest.json" in names else "manifest.json"
        if manifest_name not in names:
            print("no manifest.json in archive", file=sys.stderr)
            return False
        manifest = json.loads(z.read(manifest_name))

    mv = manifest.get("manifest_version")
    print("entries " + str(len(names)))
    print("name    " + str(manifest.get("name")))
    print("version " + str(manifest.get("version")))
    print("mv      " + str(mv))

    if mv != 2:
        # The whole point of bundling this build rather than uBO Lite.
        print("expected an MV2 package, got manifest_version " + str(mv), file=sys.stderr)
        return False
    if manifest.get("version") != version:
        print("manifest version " + str(manifest.get("version")) +
              " does not match requested " + version, file=sys.stderr)
        return False

    perms = manifest.get("permissions", [])
    for needed in ("webRequest", "webRequestBlocking"):
        if needed not in perms:
            print("manifest is missing " + needed, file=sys.stderr)
            return False
    print("perms   webRequest + webRequestBlocking present")
    return True


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="/opt/cobalt/vendor/ublock")
    ap.add_argument("--version", default=VERSION)
    ap.add_argument("--record", action="store_true")
    args = ap.parse_args()

    dest = Path(args.out)
    dest.mkdir(parents=True, exist_ok=True)
    zip_path = fetch(args.version, dest)
    if not verify(zip_path, args.version, args.record):
        return 1

    unpacked = dest / "unpacked"
    if unpacked.exists():
        shutil.rmtree(unpacked)
    with zipfile.ZipFile(zip_path) as z:
        z.extractall(unpacked)
    inner = unpacked / "uBlock0.chromium"
    if inner.is_dir():
        for item in inner.iterdir():
            shutil.move(str(item), str(unpacked / item.name))
        inner.rmdir()
    print("unpacked to " + str(unpacked))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

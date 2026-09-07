#!/usr/bin/env python3
"""Make Manifest V2 a first-class, enabled-by-default manifest version.

Upstream removes MV2 by feature flag rather than by deletion. Flipping the
defaults puts MV2ExperimentStage back at kNone -- no deprecation warning, no
forced disable, no exception list to be on. MV2 simply works, which is the
point: this is not an experiment the user opts into, it is the supported state.

Rewrites BASE_FEATURE blocks *by name* rather than applying a context patch.
A context patch against extension_features.cc would break every time upstream
adds an unrelated flag nearby; matching by name survives that, and this runs
again at every rebase hop.

Idempotent.  --check verifies without writing.
A flag that has vanished is a hard error: it means upstream stopped gating MV2
and started deleting it, which is the gate in decision 0005.
"""
import argparse, os, re, sys

# flag name -> default it must carry for MV2 to be fully supported
WANT = {
    "kExtensionManifestV2DeprecationWarning": "DISABLED",
    "kExtensionManifestV2Unsupported":        "DISABLED",
    "kExtensionManifestV2Disabled":           "DISABLED",
    "kAllowLegacyMV2Extensions":              "ENABLED",
}

def block(flag):
    return re.compile(
        r"(BASE_FEATURE\(\s*" + re.escape(flag) + r"\s*,.*?FEATURE_)"
        r"(ENABLED|DISABLED)(_BY_DEFAULT)", re.S)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=os.environ.get("SRC", "/build/chromium/m140/src"))
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()

    path = os.path.join(a.src, "extensions/common/extension_features.cc")
    if not os.path.isfile(path):
        sys.exit("not found: " + path)

    s = open(path, encoding="utf-8").read()
    missing, changed, ok = [], [], []

    for flag, want in WANT.items():
        m = block(flag).search(s)
        if not m:
            missing.append(flag)
            continue
        cur = m.group(2)
        if cur == want:
            ok.append((flag, cur))
        else:
            changed.append((flag, cur, want))
            s = block(flag).sub(lambda mm: mm.group(1) + want + mm.group(3), s, count=1)

    for f, c in ok:
        print("  %-42s %s (already correct)" % (f, c))
    for f, c, w in changed:
        print("  %-42s %s -> %s" % (f, c, w))
    for f in missing:
        print("  %-42s NOT FOUND" % f)

    print()
    if missing:
        print("ERROR: %d MV2 flag(s) absent from this tree." % len(missing))
        print("Upstream may have moved from gating MV2 to deleting it. That is the")
        print("gate in docs/decisions/0005-support-mv2-and-mv3.md -- do not proceed")
        print("with this tree as a rebase target until it is understood.")
        return 2

    if a.check:
        print("MV2 defaults already correct" if not changed
              else "%d flag(s) need changing" % len(changed))
        return 1 if changed else 0

    if changed:
        open(path, "w", encoding="utf-8", newline="\n").write(s)
        print("%d flag(s) rewritten in %s" % (len(changed), path))
    else:
        print("nothing to do; MV2 defaults already correct")
    return 0

if __name__ == "__main__":
    sys.exit(main())

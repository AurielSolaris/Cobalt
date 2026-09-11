#!/usr/bin/env bash
#
# Build Mozilla's pdf.js Chromium extension ("PDF Viewer") from source, for
# Cobalt to bundle the way it bundles uBlock Origin.
#
#   tools/assets/build-pdfjs.sh            -> /opt/cobalt/vendor/pdfjs/extension/
#
# From source rather than repackaging the Chrome Web Store download: the
# store copy is signed by Google for a different id, and a build from a pinned
# tag with a lockfile is something anyone can reproduce and audit. pdf.js's own
# `gulp chromium` target is the build Mozilla publishes to the store.
#
# Needs Node; Cobalt vendors it at /opt/cobalt/vendor/node (an official
# nodejs.org build, checksum-verified) rather than installing it system-wide.
set -euo pipefail

TAG="${PDFJS_TAG:-v6.3.289}"
VENDOR="${VENDOR:-/opt/cobalt/vendor}"
WORK="$VENDOR/pdfjs/src-$TAG"
OUT="$VENDOR/pdfjs/extension"
export PATH="$VENDOR/node/bin:$PATH"

command -v node >/dev/null || { echo "no node at $VENDOR/node/bin" >&2; exit 1; }
echo "=== pdf.js $TAG with node $(node --version)"

if [ ! -d "$WORK/.git" ]; then
    rm -rf "$WORK"
    git clone --quiet --depth 1 --branch "$TAG" https://github.com/mozilla/pdf.js "$WORK"
fi
cd "$WORK"
echo "    commit $(git rev-parse HEAD)"

# The lockfile, exactly: npm ci refuses if package.json and the lock disagree.
npm ci --no-audit --no-fund --loglevel=error
npx gulp chromium

[ -f build/chromium/manifest.json ] || { echo "gulp chromium produced no manifest" >&2; exit 1; }
rm -rf "$OUT"
cp -r build/chromium "$OUT"
python3 - "$OUT/manifest.json" <<'EOF'
import json, sys
m = json.load(open(sys.argv[1]))
print(f"    built: {m['name']} {m['version']} (manifest v{m['manifest_version']})")
EOF
du -sh "$OUT" | awk '{print "    size:", $1}'

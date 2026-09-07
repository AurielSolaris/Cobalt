#!/usr/bin/env bash
#
# Verify the checkout survived, discard the build output, and rebuild.
#
# out/ is regenerable, and after an I/O fault it is the least trustworthy thing
# on the volume — half-written object files that look present but are corrupt
# produce link errors that read like source bugs. Throwing it away is cheaper
# than diagnosing that.
set -euo pipefail

SRC="${SRC:-/build/chromium/m140/src}"
OUT="${OUT:-out/Default}"

export PATH="/build/depot_tools:$PATH"
export DEPOT_TOOLS_UPDATE=0
export DEPOT_TOOLS_METRICS=0

cd "$SRC"

echo "=== checkout identity"
git --no-pager log -1 --format='  HEAD %H%n  %d' | head -2
printf '  VERSION '; tr '\n' ' ' < chrome/VERSION; echo

echo
echo "=== files that must exist"
ok=1
for f in chrome/VERSION \
         third_party/libaddressinput/src/cpp/src/address_input_helper.cc \
         third_party/llvm-build/Release+Asserts/bin/clang \
         v8/BUILD.gn \
         third_party/blink/renderer/core/dom/document.cc; do
    if [ -e "$f" ]; then printf '  OK      %s\n' "$f"
    else printf '  MISSING %s\n' "$f"; ok=0; fi
done
[ "$ok" = 1 ] || { echo "checkout is incomplete" >&2; exit 1; }

echo
echo "=== git object integrity (fast check)"
git rev-parse --verify HEAD >/dev/null && echo "  HEAD resolves"
git cat-file -e HEAD^{tree} && echo "  tree readable"

echo
echo "=== discarding build output"
rm -rf "$OUT"
echo "  removed $OUT"

echo
echo "=== gn gen"
bash /mnt/c/Users/Auriel/Documents/app.auriel/Cobalt/tools/build-chromium.sh gen 2>&1 | tail -3

echo
echo "=== building"
exec bash /mnt/c/Users/Auriel/Documents/app.auriel/Cobalt/tools/build-chromium.sh build

#!/usr/bin/env bash
#
# Resume the Chromium build after an interruption.
#
# ninja is incremental: every completed object on disk is kept and only
# in-flight compiles are redone. So this does NOT discard out/ -- an earlier
# version did, which is right after an I/O fault (half-written objects look
# present but are corrupt) and wrong after a clean stop, where it throws away
# hours of good work.
set -euo pipefail

SRC="${SRC:-/build/chromium/m140/src}"
OUT="${OUT:-out/Default}"
JOBS="${COBALT_JOBS:-6}"

export PATH="/build/depot_tools:$PATH"
export DEPOT_TOOLS_UPDATE=0
export DEPOT_TOOLS_METRICS=0
export COBALT_JOBS="$JOBS"

cd "$SRC"

echo "=== checkout"
printf '  VERSION '; tr '\n' ' ' < chrome/VERSION; echo
printf '  objects already built: %s\n' "$(find "$OUT/obj" -name '*.o' 2>/dev/null | wc -l)"

echo
echo "=== memory budget"
free -h | head -2 | sed 's/^/  /'
echo "  jobs: $JOBS"
echo
echo "  Heavy Blink and V8 translation units peak near 1 GB per clang. With a"
echo "  10 GB cap, sixteen of them exhausted RAM and all 16 GB of swap, and the"
echo "  build thrashed at 4 objects/min rather than failing outright."

echo
echo "=== building"
exec bash /mnt/c/Users/Auriel/Documents/app.auriel/Cobalt/tools/build-chromium.sh build

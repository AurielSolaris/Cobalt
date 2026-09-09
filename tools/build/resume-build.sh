#!/usr/bin/env bash
#
# Resume or restart the Chromium build.
#
#   tools/resume-build.sh            resume, keeping out/
#   tools/resume-build.sh --clean    discard out/ and rebuild it
#
# Which one is right depends entirely on HOW the previous build stopped:
#
#   * Clean stop (killed, cancelled, VM shut down): resume. ninja is
#     incremental and the objects on disk are good.
#   * I/O fault: --clean. The filesystem check passing says the metadata is
#     consistent; it says nothing about file CONTENTS written while the disk
#     was failing. Objects come back half-written, siso's deps log records
#     them as complete, and the failure surfaces much later as hundreds of
#     "undefined symbol" errors at link time -- which read like a source
#     problem and are not one.

# Resolve sibling tools relative to this script, not an absolute path.
TOOLS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

set -euo pipefail

SRC="${SRC:-/opt/cobalt/chromium/m140/src}"
OUT="${OUT:-out/Default}"
JOBS="${COBALT_JOBS:-6}"
CLEAN=0
[ "${1:-}" = "--clean" ] && CLEAN=1

export PATH="/opt/cobalt/depot_tools:$PATH"
export DEPOT_TOOLS_UPDATE=0
export DEPOT_TOOLS_METRICS=0
export COBALT_JOBS="$JOBS"

cd "$SRC"

echo "=== checkout"
printf '  VERSION '; tr '\n' ' ' < chrome/VERSION; echo

echo
echo "=== volume"
if touch "$SRC/.rwprobe" 2>/dev/null; then rm -f "$SRC/.rwprobe"; echo "  writable"
else echo "  build tree IS READ-ONLY -- disk failure, not a build problem" >&2; exit 1; fi

if [ "$CLEAN" = 1 ]; then
    echo
    echo "=== discarding out/ (corrupt after an I/O fault)"
    printf '  removing %s objects\n' "$(find "$OUT/obj" -name '*.o' 2>/dev/null | wc -l)"
    rm -rf "$OUT"
    bash $TOOLS/build-chromium.sh gen 2>&1 | tail -2
else
    printf '  objects kept: %s\n' "$(find "$OUT/obj" -name '*.o' 2>/dev/null | wc -l)"
fi

echo
echo "=== building with -j $JOBS"
exec bash $TOOLS/build-chromium.sh build

#!/usr/bin/env bash
# Reproduce a build failure and capture the full error, unwrapped.
set -uo pipefail

SRC="${SRC:-/opt/cobalt/chromium/m140/src}"
OUT="${OUT:-out/Default}"
TARGET="${1:-chrome_public_apk}"

export PATH="/opt/cobalt/depot_tools:$PATH"
export DEPOT_TOOLS_UPDATE=0
export DEPOT_TOOLS_METRICS=0

cd "$SRC"

echo "=== which build tool does autoninja pick?"
grep -E '^use_siso|^use_remoteexec' "$OUT/args.gn" 2>/dev/null || echo "  (use_siso unset)"
ls "$OUT/.siso_config" "$OUT/build.ninja" 2>/dev/null | head -3

echo
echo "=== siso/ninja failure, full text"
autoninja -C "$OUT" "$TARGET" 2>&1 | tail -40 | fold -w 160

echo
echo "=== exit was: ${PIPESTATUS[0]:-unknown}"

echo
echo "=== siso failure logs if any"
for f in "$OUT"/.siso_failure_summary "$OUT"/siso_failure_summary.json "$OUT"/.siso_port; do
    [ -f "$f" ] && { echo "--- $f"; head -30 "$f" | fold -w 160; }
done

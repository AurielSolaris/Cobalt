#!/usr/bin/env bash
# Estimate remaining build time from the observed compile rate.
#
# siso's progress output is not always parseable from a piped log, so this
# measures what is actually on disk: object files created over a sample window.
set -uo pipefail

OUT="${1:-/build/chromium/m140/src/out/Default}"
WINDOW="${2:-90}"

count_obj() { find "$OUT/obj" -name '*.o' 2>/dev/null | wc -l; }

a=$(count_obj)
ta=$(date +%s)
echo "sampling for ${WINDOW}s…"
sleep "$WINDOW"
b=$(count_obj)
tb=$(date +%s)

elapsed=$(( tb - ta ))
made=$(( b - a ))

echo
echo "  objects now      : $b"
echo "  made in ${elapsed}s     : $made"

if [ "$made" -le 0 ]; then
    echo
    echo "  no progress in the window — either linking, or stalled."
    echo "  compilers running: $(pgrep -fc 'clang|siso' 2>/dev/null || echo 0)"
    exit 0
fi

rate=$(( made * 60 / elapsed ))
echo "  rate             : ~${rate} objects/min"

# A Chromium Android build lands somewhere near 45k objects. This is an
# order-of-magnitude anchor, not a precise target — the real total depends on
# the target and the args.
for est in 40000 45000 50000; do
    left=$(( est - b ))
    if [ "$left" -gt 0 ] && [ "$rate" -gt 0 ]; then
        mins=$(( left / rate ))
        printf '  if total is %6d : ~%3d min left (%d h %02d m)\n' \
            "$est" "$mins" $(( mins / 60 )) $(( mins % 60 ))
    else
        printf '  if total is %6d : already past it\n' "$est"
    fi
done

echo
echo "  note: linking follows compilation and is not included above."
echo "        libchrome.so is the long pole and runs with concurrent_links=2."

#!/usr/bin/env bash
# Sample the compile rate from objects actually on disk.
OUT="${OUT:-/build/chromium/m140/src/out/Default}"
W="${1:-120}"
a=$(find "$OUT/obj" -name '*.o' 2>/dev/null | wc -l)
sleep "$W"
b=$(find "$OUT/obj" -name '*.o' 2>/dev/null | wc -l)
made=$(( b - a ))
echo "objects: $a -> $b  (+$made in ${W}s)"
[ "$made" -gt 0 ] && echo "rate   : ~$(( made * 60 / W )) obj/min"
echo "clang  : $(pgrep -fc clang 2>/dev/null || echo 0)"
echo "load   : $(cut -d' ' -f1-3 /proc/loadavg)"

#!/usr/bin/env bash
# Current edge rate, sampled — the average over the whole run hides that
# Blink and V8 translation units are far slower than the early ones.
LOG=${LOG:-/opt/cobalt/build.log}
W=${1:-120}
get() { tr '\r' '\n' < "$LOG" 2>/dev/null | grep -oE '^\[[0-9]+/[0-9]+\]' | tail -1 | tr -d '[]'; }
a=$(get); an=${a%%/*}; total=${a##*/}
sleep "$W"
b=$(get); bn=${b%%/*}
made=$(( bn - an ))
echo "edges   : $bn / $total  ($(( bn * 100 / total ))%)"
echo "made    : $made in ${W}s"
if [ "$made" -gt 0 ]; then
  rate=$(( made * 60 / W ))
  left=$(( total - bn ))
  mins=$(( left / rate ))
  echo "rate now: ${rate}/min"
  printf 'remaining at this rate: %dh %02dm (%d edges)\n' $(( mins / 60 )) $(( mins % 60 )) "$left"
else
  echo "no edges completed in the window — long-running translation units, or stalled"
fi
echo
echo "what it is chewing on:"
tr '\r' '\n' < "$LOG" | tail -4 | sed 's/^/  /' | cut -c1-120

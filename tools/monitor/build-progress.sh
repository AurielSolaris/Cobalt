#!/usr/bin/env bash
# Progress from siso's own edge counter in the log, which is exact, rather than
# counting .o files on disk and guessing at the total.
LOG=${LOG:-/opt/cobalt/build.log}
OUT=${OUT:-/opt/cobalt/chromium/m140/src/out/Default}

last=$(tr '\r' '\n' < "$LOG" 2>/dev/null | grep -oE '^\[[0-9]+/[0-9]+\]' | tail -1 | tr -d '[]')
done_n=${last%%/*}
total=${last##*/}

echo "edges   : ${done_n:-?} / ${total:-?}"
if [ -n "${done_n:-}" ] && [ -n "${total:-}" ] && [ "$total" -gt 0 ] 2>/dev/null; then
    echo "percent : $(( done_n * 100 / total ))%"
fi

pid=$(pgrep -f 'siso ninja' | head -1)
echo "elapsed : $(ps -o etime= -p "${pid:-0}" 2>/dev/null | tr -d ' ')"
echo
echo "clang   : $(pgrep -c clang 2>/dev/null || true)"
echo "load    : $(cut -d' ' -f1-3 /proc/loadavg)"
echo "mem free: $(awk '/MemAvailable/{printf "%.1f GB", $2/1048576}' /proc/meminfo)"
echo "disk    : $(df -h / | awk 'NR==2{print $4" free"}')"
echo "out size: $(du -sh "$OUT" 2>/dev/null | cut -f1)"
echo "apk     : $([ -f "$OUT/apks/ChromePublic.apk" ] && ls -lh "$OUT/apks/ChromePublic.apk" | awk '{print $5}' || echo 'not yet')"
echo
# Anchored patterns only. A bare "Read-only" also matched V8 sources named
# read-only-spaces.cc and reported them as disk faults -- exactly the alarm
# that must not cry wolf, given three real ones preceded it.
echo "real errors:"
tr '\r' '\n' < "$LOG" \
  | grep -E 'error: |FAILED: |ld\.lld: error|Read-only file system|Input/output error' \
  | tail -3
if [ "${PIPESTATUS[1]:-1}" -ne 0 ]; then echo "  none"; fi

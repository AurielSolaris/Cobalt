#!/usr/bin/env bash
# One-shot build status: elapsed, objects, rate, ETA band, health.
OUT="${OUT:-/opt/cobalt/chromium/m140/src/out/Default}"
START="${START:-2026-09-07T15:02:06Z}"

now=$(date -u +%s)
st=$(date -u -d "$START" +%s 2>/dev/null || echo "$now")
el=$(( now - st ))

a=$(find "$OUT/obj" -name '*.o' 2>/dev/null | wc -l)
sleep 60
b=$(find "$OUT/obj" -name '*.o' 2>/dev/null | wc -l)
rate=$(( (b - a) ))          # per 60s == per min

printf 'elapsed   : %dh %02dm\n' $(( el/3600 )) $(( (el%3600)/60 ))
printf 'objects   : %d\n' "$b"
printf 'rate now  : %d/min   (avg %d/min)\n' "$rate" $(( el > 0 ? b*60/el : 0 ))
avg=$(( el > 0 ? b*60/el : 1 )); [ "$avg" -lt 1 ] && avg=1

echo
echo 'remaining, using the average rate:'
for est in 40000 45000 50000; do
  left=$(( est - b ))
  if [ "$left" -gt 0 ]; then
    m=$(( left / avg ))
    printf '  if total %6d : ~%dh %02dm  (%d min)\n' "$est" $(( m / 60 )) $(( m % 60 )) "$m"
  else
    printf '  if total %6d : past it\n' "$est"
  fi
done

echo
echo "health:"
printf '  clang procs : %s\n' "$(pgrep -fc clang 2>/dev/null || echo 0)"
printf '  load        : %s\n' "$(cut -d' ' -f1-3 /proc/loadavg)"
printf '  mem avail   : %s\n' "$(awk '/MemAvailable/{printf "%.1f GB", $2/1048576}' /proc/meminfo)"
printf '  disk        : %s\n' "$(df -h /build | awk 'NR==2{print $3" used, "$4" free"}')"
printf '  out size    : %s\n' "$(du -sh "$OUT" 2>/dev/null | cut -f1)"
printf '  apk         : %s\n' "$([ -f "$OUT/apks/ChromePublic.apk" ] && ls -lh "$OUT/apks/ChromePublic.apk" | awk '{print $5}' || echo 'not yet')"

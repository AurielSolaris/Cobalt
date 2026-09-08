#!/usr/bin/env bash
# Emit only events worth acting on. Silence must not be able to mean "dying" --
# the 16-job build thrashed at 4 objects/min for hours and looked merely slow.
OUT=/opt/cobalt/chromium/m140/src/out/Default
APK=$OUT/apks/ChromePublic.apk
INTERVAL="${INTERVAL:-300}"
prev=-1
warned_mem=0

while true; do
  avail=$(awk '/MemAvailable/{printf "%d", $2/1024}' /proc/meminfo 2>/dev/null)
  avail=${avail:-9999}
  swapfree=$(awk '/SwapFree/{printf "%d", $2/1024}' /proc/meminfo 2>/dev/null)
  swapfree=${swapfree:-9999}
  n=$(find "$OUT/obj" -name '*.o' 2>/dev/null | wc -l)

  # pgrep -fc prints a zero AND exits non-zero when nothing matches, so an
  # "|| echo 0" fallback appends a second zero, the value becomes two lines,
  # and every numeric test rejects it. Same trap as "grep -c ... || echo 0"
  # corrupting the patch-classification columns earlier in this project.
  clang=$(pgrep -fc clang 2>/dev/null)
  clang=${clang:-0}

  # The volume remounts read-only when the USB SSD throws write errors -- twice
  # now -- and every compile then fails with "Read-only file system", which
  # reads as 107 compiler errors rather than as one hardware fault.
  if ! touch "$(dirname "$OUT")/.rwprobe" 2>/dev/null; then
    echo "VOLUME READ-ONLY: build tree is not writable -- disk I/O failure, not a build error"
    exit 1
  fi
  rm -f "$(dirname "$OUT")/.rwprobe"

  if [ -f "$APK" ]; then
    echo "BUILD COMPLETE: ChromePublic.apk $(du -h "$APK" | cut -f1), $n objects"
    exit 0
  fi

  # The memory warning latches, so a sustained squeeze reports once rather than
  # every five minutes until it clears.
  if [ "$avail" -lt 400 ] || [ "$swapfree" -lt 1000 ]; then
    if [ "$warned_mem" -eq 0 ]; then
      echo "MEMORY PRESSURE: ${avail}MB free, ${swapfree}MB swap, ${clang} clangs, ${n} objects"
      warned_mem=1
    fi
  else
    warned_mem=0
  fi

  if [ "$clang" -eq 0 ] && [ "$n" -eq "$prev" ] && [ "$n" -gt 0 ]; then
    echo "BUILD STOPPED: no compilers, ${n} objects, no APK -- died or finished badly"
    exit 1
  fi

  if dmesg 2>/dev/null | tail -60 | grep -qiE 'Out of memory|oom-kill|Killed process'; then
    echo "OOM KILL in dmesg at ${n} objects"
  fi

  prev=$n
  sleep "$INTERVAL"
done

#!/usr/bin/env bash
# Stop the build cleanly. ninja/siso is incremental, so completed objects
# survive -- only the in-flight compiles are lost.
echo "before:"
printf '  clang %s   siso %s\n' "$(pgrep -fc clang 2>/dev/null || echo 0)" "$(pgrep -fc siso 2>/dev/null || echo 0)"
pkill -f 'siso' 2>/dev/null
pkill -f 'autoninja' 2>/dev/null
pkill -f 'ninja' 2>/dev/null
sleep 3
pkill -9 -f clang 2>/dev/null
sleep 2
echo "after:"
printf '  clang %s   siso %s\n' "$(pgrep -fc clang 2>/dev/null || echo 0)" "$(pgrep -fc siso 2>/dev/null || echo 0)"
echo
echo "objects preserved: $(find /build/chromium/m140/src/out/Default/obj -name '*.o' 2>/dev/null | wc -l)"
free -h | head -3

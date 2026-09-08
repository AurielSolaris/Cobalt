#!/usr/bin/env bash
SRC=${SRC:-/opt/cobalt/chromium/m140/src}
cd "$SRC" || exit 1
echo "=== is_high_end_android definition"
grep -rn -B2 -A6 "is_high_end_android *=" build/config/android/config.gni build/config/chrome_build.gni 2>/dev/null | head -25
echo
echo "=== pointer compression resolution"
sed -n '270,285p' v8/gni/v8.gni
echo
echo "=== how PGO profiles arrive"
grep -rn "update_pgo_profiles\|pgo_profiles" DEPS | head -5

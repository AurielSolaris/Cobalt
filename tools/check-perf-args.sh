#!/usr/bin/env bash
SRC=${SRC:-/opt/cobalt/chromium/m140/src}
cd "$SRC" || exit 1
show() {
  printf '=== %s\n' "$1"
  grep -rn --include='*.gni' --include='*.gn' -A3 "^ *$1 *=" "$2" 2>/dev/null | head -6
  echo
}
show chrome_pgo_phase build/config/compiler/pgo/pgo.gni
show is_high_end_android build/config/android/config.gni
show v8_enable_pointer_compression v8/gni/v8.gni
show use_thin_lto build/config/compiler/BUILD.gn
echo "=== is_high_end_android referenced anywhere?"
grep -rl "is_high_end_android" --include='*.gn' --include='*.gni' . 2>/dev/null | head -5
echo
echo "=== PGO profile present for android-arm64?"
ls chrome/build/*.profdata 2>/dev/null | head -5
ls chrome/build/pgo_profiles/ 2>/dev/null | head -5

#!/usr/bin/env bash
# What does is_desktop_android actually change?
set -uo pipefail
S=/opt/cobalt/chromium/m140/src
export PATH=/opt/cobalt/depot_tools:$PATH
export DEPOT_TOOLS_UPDATE=0 DEPOT_TOOLS_METRICS=0
cd "$S" || exit 1

echo "=== declaration and comment"
grep -rn -B6 -A4 "is_desktop_android = " build/config/chrome_build.gni | head -20

echo
echo "=== how many build files branch on it"
grep -rl "is_desktop_android" --include='*.gn' --include='*.gni' . 2>/dev/null | wc -l

echo
echo "=== does chrome_public_apk still exist in that config?"
gn ls out/RouteB 2>/dev/null | grep -E "chrome_public_apk|_apk$" | head -8

echo
echo "=== what APK targets does it offer"
gn ls out/RouteB 2>/dev/null | grep -cE "_apk$"

echo
echo "=== is the extensions system actually reachable from the apk?"
for t in "//chrome/android:chrome_public_apk"; do
  echo "  checking $t"
  gn desc out/RouteB "$t" deps 2>/dev/null | head -5
done

#!/usr/bin/env bash
# Can gn root the graph at chrome_public_apk instead of gn_all?
#
# If it can, gn never evaluates the desktop test targets that assert !is_android,
# and the whole assertion cascade disappears rather than being patched around
# one target at a time.
set -uo pipefail
S=/opt/cobalt/chromium/m140/src
export PATH=/opt/cobalt/depot_tools:$PATH
export DEPOT_TOOLS_UPDATE=0 DEPOT_TOOLS_METRICS=0
cd "$S" || exit 1

echo "=== gn help dotfile: is there a root setting?"
gn help dotfile 2>&1 | grep -n -i "root_target\|^  root" | head -5

echo
echo "=== try it: .gn with root_target"
cp .gn /tmp/gn.bak
if grep -q "^root = " .gn; then
  echo "  already set"
else
  printf '\n# Cobalt: root the graph at the APK we build, so gn does not evaluate\n# desktop-only test targets that assert !is_android.\nroot = "//chrome/android:chrome_public_apk"\n' >> .gn
fi
gn gen out/ExtTest 2>&1 | tail -8
rc=${PIPESTATUS[0]}
echo "gn exit: $rc"

if [ "$rc" != 0 ]; then
  echo "  reverting .gn"
  cp /tmp/gn.bak .gn
fi

#!/usr/bin/env bash
# Route F plus rooting the graph at the APK we actually build.
#
# gn evaluates every target reachable from gn_all by default, including desktop
# test binaries that assert !is_android. Rooting at chrome_public_apk means gn
# only evaluates what we ship -- which is both correct and much faster.
set -uo pipefail
S=/opt/cobalt/chromium/m140/src
export PATH=/opt/cobalt/depot_tools:$PATH
export DEPOT_TOOLS_UPDATE=0 DEPOT_TOOLS_METRICS=0
cd "$S" || exit 1

cp .gn /tmp/gn.bak
grep -q '^root = ' .gn || printf '\n# Cobalt: evaluate only the APK we build. gn otherwise walks every target\n# reachable from gn_all, including desktop test binaries that assert !is_android.\nroot = "//chrome/android:chrome_public_apk"\n' >> .gn

mkdir -p out/RouteG
cat > out/RouteG/args.gn <<'ARGS'
target_os = "android"
target_cpu = "arm64"
is_debug = false
is_official_build = false
symbol_level = 0
blink_symbol_level = 0
v8_symbol_level = 0
use_remoteexec = false
enable_resource_allowlist_generation = false
is_desktop_android = true
enable_extensions = true
enable_platform_apps = false
enable_guest_view = true
ARGS

echo "=== G — full extensions on Android, graph rooted at the APK"
err=$(gn gen out/RouteG 2>&1); rc=$?
if [ $rc -eq 0 ]; then
    echo "  gn gen: OK"
    echo "  extension targets : $(gn ls out/RouteG 2>/dev/null | grep -c '^//extensions')"
    for t in //extensions/browser //extensions/common //chrome/browser/extensions //chrome/renderer/extensions; do
      printf '  %-34s ' "$t"
      gn path out/RouteG //chrome/android:chrome_public_apk "$t" >/tmp/p 2>&1 \
        && { grep -q "No non-data paths" /tmp/p && echo "not reachable" || echo "REACHABLE"; } \
        || head -1 /tmp/p
    done
else
    echo "  gn gen: FAILED"
    printf '%s\n' "$err" | grep -E "^ERROR|assert\(|\"" | head -4 | sed 's/^/    /'
    cp /tmp/gn.bak .gn
    echo "  (.gn reverted)"
fi

#!/usr/bin/env bash
# Can we have the FULL extensions platform on Android?
#
# is_desktop_android clears the Android assertions in the extension graph;
# enable_extensions turns on the full desktop platform rather than the core
# subset. If they combine, that is desktop-class extensions on a phone --
# which is Kiwi's entire pitch, reached through upstream flags.
set -uo pipefail
S=/opt/cobalt/chromium/m140/src
export PATH=/opt/cobalt/depot_tools:$PATH
export DEPOT_TOOLS_UPDATE=0 DEPOT_TOOLS_METRICS=0
cd "$S" || exit 1

base='target_os = "android"
target_cpu = "arm64"
is_debug = false
is_official_build = false
symbol_level = 0
blink_symbol_level = 0
v8_symbol_level = 0
use_remoteexec = false
enable_resource_allowlist_generation = false'

run() {
    local name="$1" out="$2" extra="$3"
    mkdir -p "$out"
    printf '%s\n%s\n' "$base" "$extra" > "$out/args.gn"
    echo "=== $name"
    printf '%s\n' "$extra" | sed 's/^/    /'
    local err rc
    err=$(gn gen "$out" 2>&1); rc=$?
    if [ $rc -eq 0 ]; then
        echo "    gn gen: OK"
        echo "    extension targets : $(gn ls "$out" 2>/dev/null | grep -c '^//extensions')"
        for t in //extensions/browser //chrome/browser/extensions; do
          printf '    %-32s ' "$t"
          gn path "$out" //chrome/android:chrome_public_apk "$t" >/tmp/p 2>&1 \
            && { grep -q "No non-data paths" /tmp/p && echo "not reachable" || echo "REACHABLE"; } \
            || head -1 /tmp/p
        done
    else
        echo "    gn gen: FAILED"
        printf '%s\n' "$err" | grep -E "^ERROR|assert\(|\"" | head -4 | sed 's/^/      /'
    fi
    echo
}

run "C — desktop-android + FULL extensions" out/RouteC 'is_desktop_android = true
enable_extensions = true'

run "D — C plus guest view" out/RouteD 'is_desktop_android = true
enable_extensions = true
enable_guest_view = true'

run "E — full extensions, no Chrome Apps" out/RouteE 'is_desktop_android = true
enable_extensions = true
enable_platform_apps = false'

run "F — E plus guest view" out/RouteF 'is_desktop_android = true
enable_extensions = true
enable_platform_apps = false
enable_guest_view = true'

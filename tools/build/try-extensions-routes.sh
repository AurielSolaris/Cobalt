#!/usr/bin/env bash
# Compare the two routes to extensions on Android.
#
#   A: enable_extensions = true          -- the full desktop platform, Kiwi's route
#   B: enable_desktop_android_extensions -- upstream's own Android work
#
# Route B matters because it is maintained by upstream and expanding over time
# (crbug.com/356905053), where route A is a fork-local fight that gets harder
# every milestone. Worth knowing which one gn will even accept.
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
    local err
    err=$(gn gen "$out" 2>&1)
    if [ $? -eq 0 ]; then
        echo "    gn gen: OK"
        echo "    extension targets in graph: $(gn ls "$out" 2>/dev/null | grep -c '^//extensions')"
    else
        echo "    gn gen: FAILED"
        printf '%s\n' "$err" | grep -E "^ERROR|assert|should not be" | head -3 | sed 's/^/      /'
    fi
    echo
}

run "A — full extensions platform" out/RouteA 'enable_extensions = true
enable_guest_view = true'

run "B — upstream desktop-android extensions" out/RouteB 'is_desktop_android = true'

run "B2 — core extensions flag only" out/RouteB2 'enable_desktop_android_extensions = true'

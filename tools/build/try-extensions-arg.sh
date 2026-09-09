#!/usr/bin/env bash
# Can extensions be turned on for Android at all?
#
# enable_extensions is inside declare_args(), so it is overridable from args.gn
# without patching -- but upstream defaults it off for Android, and gn may
# assert against the combination. This generates into a scratch directory so
# out/Default is untouched.
set -uo pipefail
SRC=${SRC:-/opt/cobalt/chromium/m140/src}
OUT=${OUT:-out/ExtTest}
export PATH="/opt/cobalt/depot_tools:$PATH"
export DEPOT_TOOLS_UPDATE=0 DEPOT_TOOLS_METRICS=0
cd "$SRC" || exit 1

mkdir -p "$OUT"
cat > "$OUT/args.gn" <<'ARGS'
target_os = "android"
target_cpu = "arm64"
is_debug = false
is_official_build = false
symbol_level = 0
blink_symbol_level = 0
v8_symbol_level = 0
use_remoteexec = false
enable_resource_allowlist_generation = false
enable_extensions = true
# extensions/BUILD.gn asserts !enable_extensions || enable_guest_view;
# guest view is the <webview> plumbing extensions depend on.
enable_guest_view = true
ARGS

echo "=== gn gen with enable_extensions = true"
gn gen "$OUT" 2>&1 | tail -15
rc=${PIPESTATUS[0]}
echo "gn exit: $rc"

if [ "$rc" = 0 ]; then
  echo
  echo "=== does the graph now contain extension targets?"
  gn ls "$OUT" 2>/dev/null | grep -c "^//extensions" | sed 's/^/  extensions targets: /'
  gn args "$OUT" --list=enable_extensions --short 2>/dev/null | sed 's/^/  /'
fi

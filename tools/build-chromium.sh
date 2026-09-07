#!/usr/bin/env bash
#
# Configure and build the Chromium Android APK.
#
# Usage:
#   tools/build-chromium.sh deps     install Chromium's build dependencies (root)
#   tools/build-chromium.sh hooks    run gclient hooks (toolchains, NDK, SDK)
#   tools/build-chromium.sh gen      write out/Default/args.gn and run gn gen
#   tools/build-chromium.sh build    autoninja the APK
#   tools/build-chromium.sh all      hooks + gen + build
#
# Run `deps` as root once; everything else as a normal user.

set -euo pipefail

SRC="${SRC:-/build/chromium/m140/src}"
OUT="${OUT:-out/Default}"

export PATH="/build/depot_tools:$PATH"
export DEPOT_TOOLS_UPDATE=0
export DEPOT_TOOLS_METRICS=0

[ -d "$SRC" ] || { echo "no checkout at $SRC" >&2; exit 1; }
cd "$SRC"

# --- Build arguments -------------------------------------------------------
#
# Tuned for this machine (16 cores, 8 GB RAM) and for the fact that Stage 3 only
# needs to prove the tree builds — not to ship anything.
#
# symbol_level=0 is the single most important line here. Chromium's default
# debug info makes link steps need several GB *each*; with 8 GB and parallel
# links that is an OOM, not a slowdown. It also saves tens of GB of output.
# We are not debugging Chromium's internals at this stage, so the symbols buy
# nothing.
#
# concurrent_links=2 is the other memory guard: it caps how many linkers run at
# once independently of ninja's job count, so compiling stays parallel while
# linking stays inside the memory budget. This is the knob to lower first if
# the build gets OOM-killed — before touching processors in .wslconfig.
write_args() {
    mkdir -p "$OUT"
    cat > "$OUT/args.gn" <<'EOF'
# Cobalt — Stage 3 bring-up build. See tools/build-chromium.sh.

target_os = "android"
target_cpu = "arm64"

# A release build: faster to produce and to run than a debug one, and Stage 3
# is about proving the toolchain rather than stepping through Chromium.
is_debug = false
is_official_build = false
dcheck_always_on = false

# Memory guards for an 8 GB build host. See the comment in build-chromium.sh.
symbol_level = 0
blink_symbol_level = 0
v8_symbol_level = 0
concurrent_links = 2

# Nothing here uses NaCl, and it is a large chunk of build time.
enable_nacl = false

# No remote execution available; be explicit rather than let it probe.
use_remoteexec = false

# Keep the resource-heavy extras off for the first build.
enable_resource_allowlist_generation = false
EOF
    echo "wrote $SRC/$OUT/args.gn:"
    sed 's/^/    /' "$OUT/args.gn"
}

case "${1:-all}" in
  deps)
    echo "=== installing Chromium build dependencies"
    # Chromium's own script knows what this revision needs; do not hand-roll it.
    ./build/install-build-deps.sh --android --no-prompt
    ;;

  hooks)
    echo "=== gclient runhooks (toolchains, NDK, SDK — this pulls GBs)"
    gclient runhooks
    ;;

  gen)
    write_args
    echo "=== gn gen $OUT"
    gn gen "$OUT"
    ;;

  build)
    echo "=== building chrome_public_apk"
    echo "    started $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    # autoninja picks its own -j; it respects the load and the pools above.
    autoninja -C "$OUT" chrome_public_apk
    echo "=== finished $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    ls -la "$OUT/apks/" 2>/dev/null || true
    ;;

  all)
    gclient runhooks
    write_args
    gn gen "$OUT"
    autoninja -C "$OUT" chrome_public_apk
    ls -la "$OUT/apks/" 2>/dev/null || true
    ;;

  *)
    echo "unknown step: $1" >&2
    sed -n '3,12p' "$0" >&2
    exit 1
    ;;
esac

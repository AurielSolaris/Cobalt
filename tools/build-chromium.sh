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

SRC="${SRC:-/opt/cobalt/chromium/m140/src}"
OUT="${OUT:-out/Default}"

export PATH="/opt/cobalt/depot_tools:$PATH"
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

# Memory guards for a 10 GB build host. See the comment in build-chromium.sh.
symbol_level = 0
blink_symbol_level = 0
v8_symbol_level = 0
concurrent_links = 2

# NOTE: enable_nacl is deliberately absent.
#
# It was an ordinary argument on Kiwi's M105, but NaCl has since been removed
# from Chromium entirely - on M140 there is no components/nacl and no
# enable_nacl declaration anywhere in the tree, so setting it fails gn gen with
# an unknown-argument error. Verified by tools/check-gn-args.sh.
#
# This is the small, boring shape of the four-year gap: an argument that was
# correct on the old base is fatal on the new one.

# Cache compiled objects between builds. This is the local stand-in for the
# shared object cache Google gives Chromium engineers through RBE: without it,
# every one of the ~160 patch batches in Stage 5 re-pays for files it never
# touched, and switching branches throws away the whole build.
#
# The first build after enabling this gets no hits -- the cache is cold, and
# the win starts from the second. Enabling it also requires a fresh gn gen,
# which invalidates out/, so it goes in after the current build finishes
# rather than during it.
cc_wrapper = "ccache"

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
    # autoninja sizes -j from CPU count and ignores the memory ceiling, which
    # is wrong for this host: 16 cores but a 10 GB cap. Heavy Blink and V8
    # translation units peak near 1 GB per clang, so 16 of them exhausted RAM
    # and all 16 GB of swap four hours in. The build did not fail -- it thrashed
    # at 4 objects/min with 60% I/O wait, which looks like slowness rather than
    # a misconfiguration and is far harder to notice.
    #
    # Memory is the binding constraint here, not CPU. Six jobs running at full
    # speed beat sixteen fighting over swap.
    autoninja -C "$OUT" -j "${COBALT_JOBS:-6}" chrome_public_apk
    # Verify an APK actually exists rather than trusting the exit code.
    #
    # autoninja returned 0 on a build that failed in siso's scheduling phase,
    # and an unconditional "finished" line then made a monitor report success.
    # The artifact is the only honest signal.
    if [ -f "$OUT/apks/ChromePublic.apk" ]; then
        echo "=== finished $(date -u +%Y-%m-%dT%H:%M:%SZ)"
        ls -la "$OUT/apks/"
    else
        echo "=== BUILD FAILED - no APK at $OUT/apks/ChromePublic.apk" >&2
        exit 1
    fi
    ;;

  all)
    gclient runhooks
    write_args
    gn gen "$OUT"
    autoninja -C "$OUT" -j "${COBALT_JOBS:-6}" chrome_public_apk
    ls -la "$OUT/apks/" 2>/dev/null || true
    ;;

  *)
    echo "unknown step: $1" >&2
    sed -n '3,12p' "$0" >&2
    exit 1
    ;;
esac

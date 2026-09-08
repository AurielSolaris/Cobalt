#!/usr/bin/env bash
# Confirm every GN arg we set actually exists in this Chromium revision.
#
# gn gen fails outright on an unknown argument, and it fails after the hooks
# step — which pulls gigabytes. Checking first is cheap.
set -uo pipefail

SRC="${1:-/opt/cobalt/chromium/m140/src}"
cd "$SRC" || { echo "no checkout at $SRC" >&2; exit 1; }

ARGS="concurrent_links symbol_level blink_symbol_level v8_symbol_level
enable_nacl use_remoteexec enable_resource_allowlist_generation
dcheck_always_on is_official_build is_debug is_component_build
target_os target_cpu"

missing=0
for a in $ARGS; do
    hit=$(grep -rlE "^[[:space:]]*declare_args|^[[:space:]]*${a}[[:space:]]*=" \
            --include='*.gni' --include='*.gn' \
            build/ build_overrides/ 2>/dev/null | head -1)
    # A real check: the arg must appear inside a declare_args() block somewhere.
    decl=$(grep -rn --include='*.gni' --include='*.gn' -A 150 'declare_args()' \
            build/ v8/ third_party/blink/ 2>/dev/null \
            | grep -cE "[[:space:]]${a}[[:space:]]*=" || true)
    if [ "${decl:-0}" -gt 0 ]; then
        printf '  %-40s OK (declared %s times)\n' "$a" "$decl"
    else
        printf '  %-40s NOT DECLARED\n' "$a"
        missing=$((missing + 1))
    fi
done

echo
if [ "$missing" -gt 0 ]; then
    echo "$missing argument(s) not found — remove them from args.gn before gn gen."
    exit 1
fi
echo "all arguments present"

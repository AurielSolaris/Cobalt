#!/usr/bin/env bash
# Cross the patch trial against the keep/drop classification.
#
# The headline conflict rate counts patches we already decided to drop — the
# ad-blocking hacks especially, which live in the most-refactored Blink files
# and would inflate the number with work nobody intends to do. This reports the
# rate for the series we actually intend to carry.
set -uo pipefail

TRIAL="${1:-/build/patch-trial}"
PATCHES="${2:-patches/kiwi-105}"

DROP='third_party_blink_renderer_core_loader_base_fetch_context.cc
third_party_blink_renderer_core_layout_layout_object.cc
net_http_http_network_transaction.cc
README.md
LICENSE
.gitignore'

is_dropped() {
    printf '%s\n' "$DROP" | grep -qxF "$1"
}

for cls in clean fuzzy conflict gone; do
    total=0; kept=0; dropped=0
    while IFS=$'\t' read -r name _; do
        [ -n "$name" ] || continue
        total=$((total + 1))
        if is_dropped "$name"; then dropped=$((dropped + 1)); else kept=$((kept + 1)); fi
    done < "$TRIAL/$cls.txt"
    printf '%-9s total %3d   carried %3d   dropped %2d\n' "$cls" "$total" "$kept" "$dropped"
done

echo
echo "=== extension patches specifically (the must-survive set)"
for cls in clean fuzzy conflict gone; do
    n=0
    while IFS=$'\t' read -r name _; do
        [ -n "$name" ] || continue
        f="$PATCHES/$name.patch"
        [ -f "$f" ] || continue
        if grep -qiE 'extension|webstore|crx' "$f" 2>/dev/null; then n=$((n + 1)); fi
    done < "$TRIAL/$cls.txt"
    printf '  %-9s %3d\n' "$cls" "$n"
done

echo
echo "=== where the dropped ad-block patches landed"
for name in third_party_blink_renderer_core_loader_base_fetch_context.cc \
            third_party_blink_renderer_core_layout_layout_object.cc \
            net_http_http_network_transaction.cc; do
    for cls in clean fuzzy conflict gone; do
        if grep -qF "$name" "$TRIAL/$cls.txt" 2>/dev/null; then
            printf '  %-9s %s\n' "$cls" "$name"
        fi
    done
done

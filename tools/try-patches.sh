#!/usr/bin/env bash
#
# Dry-run Kiwi's patch series against a Chromium checkout.
#
# `git apply --check` reports whether a patch would apply without touching
# anything, so this can run against a tree that is still syncing. It answers the
# question Stage 5a exists to answer — how much of Kiwi survives 35 milestones —
# before a single byte is compiled.
#
# Usage: tools/try-patches.sh [patch-dir] [chromium-src] [out-dir]
#
# Output classes:
#   clean    applies as-is. Free.
#   fuzzy    applies with -C1 context relaxation. Cheap, needs a look.
#   conflict the file exists but the patch no longer fits. Real work.
#   gone     the target file no longer exists upstream. Either the feature moved
#            or upstream absorbed it — each needs a decision, not a port.

set -uo pipefail

PATCHES="${1:-patches/kiwi-105}"
SRC="${2:-/opt/cobalt/chromium/m140/src}"
OUT="${3:-/build/patch-trial}"

[ -d "$SRC/.git" ] || { echo "no Chromium checkout at $SRC" >&2; exit 1; }

mkdir -p "$OUT"
for f in clean fuzzy conflict gone; do : > "$OUT/$f.txt"; done

n_clean=0; n_fuzzy=0; n_conflict=0; n_gone=0; n_total=0

# Resolve to an absolute path before cd'ing into the checkout.
PATCHES="$(cd "$PATCHES" && pwd)"

cd "$SRC"

shopt -s nullglob dotglob
for p in "$PATCHES"/*.patch; do
    n_total=$((n_total + 1))
    name="$(basename "$p" .patch)"

    # The real target path lives in the patch header, not the flattened filename.
    target="$(sed -n 's#^+++ b/##p' "$p" | head -1)"

    if [ -n "$target" ] && [ ! -e "$target" ]; then
        printf '%s\t%s\n' "$name" "$target" >> "$OUT/gone.txt"
        n_gone=$((n_gone + 1))
        continue
    fi

    if git apply --check "$p" 2>/dev/null; then
        printf '%s\n' "$name" >> "$OUT/clean.txt"
        n_clean=$((n_clean + 1))
    elif git apply --check -C1 "$p" 2>/dev/null; then
        printf '%s\n' "$name" >> "$OUT/fuzzy.txt"
        n_fuzzy=$((n_fuzzy + 1))
    else
        reason="$(git apply --check "$p" 2>&1 | head -1 | cut -c1-120)"
        printf '%s\t%s\n' "$name" "$reason" >> "$OUT/conflict.txt"
        n_conflict=$((n_conflict + 1))
    fi
done

pct() { [ "$n_total" -gt 0 ] && echo $(( $1 * 100 / n_total )) || echo 0; }

{
    echo "# Kiwi's M105 patch series against $(git -C "$SRC" describe --tags 2>/dev/null || echo unknown)"
    echo
    echo "date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo
    printf '%-10s %4d\n'          "total"    "$n_total"
    printf '%-10s %4d  (%s%%)\n'  "clean"    "$n_clean"    "$(pct $n_clean)"
    printf '%-10s %4d  (%s%%)\n'  "fuzzy"    "$n_fuzzy"    "$(pct $n_fuzzy)"
    printf '%-10s %4d  (%s%%)\n'  "conflict" "$n_conflict" "$(pct $n_conflict)"
    printf '%-10s %4d  (%s%%)\n'  "gone"     "$n_gone"     "$(pct $n_gone)"
} | tee "$OUT/summary.txt"

echo
echo "conflicts by area:"
cut -f1 "$OUT/conflict.txt" | sed -E 's#^([a-z0-9]+_[a-z0-9]+).*#\1#' \
    | sort | uniq -c | sort -rn | head -10

echo
echo "files upstream no longer has:"
cut -f2 "$OUT/gone.txt" | sed -E 's#^([^/]+/[^/]+)/.*#\1#' \
    | sort | uniq -c | sort -rn | head -10

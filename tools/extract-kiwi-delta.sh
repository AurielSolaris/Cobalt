#!/usr/bin/env bash
#
# Classify every file in Kiwi's overlay against pristine Chromium.
#
# Kiwi ships `src.next` as a whole-file overlay: 8,313 complete source files
# copied over a Chromium tree at build time, with no patches and no record of
# what was changed inside any of them. An unknown number are byte-identical to
# upstream and were carried along for build convenience.
#
# This separates the three cases:
#
#   identical  - byte-for-byte upstream. Noise. Discard.
#   modified   - Kiwi changed it. A real diff is emitted per file.
#   kiwi-only  - no upstream counterpart. Kiwi's own code. Keep whole.
#
# The `modified` diffs are the patch series that Stage 5 carries forward, and
# the whole point of Stage 2. See docs/kiwi-delta.md.
#
# Usage:
#   tools/extract-kiwi-delta.sh <overlay-dir> <pristine-chromium-src> [out-dir]
#
# Example:
#   tools/extract-kiwi-delta.sh .ref/kiwi /build/chromium/src-105 /build/delta

set -euo pipefail

OVERLAY="${1:?usage: $0 <overlay-dir> <pristine-chromium-src> [out-dir]}"
UPSTREAM="${2:?usage: $0 <overlay-dir> <pristine-chromium-src> [out-dir]}"
OUT="${3:-./kiwi-delta-out}"

[ -d "$OVERLAY" ]  || { echo "overlay not found: $OVERLAY" >&2; exit 1; }
[ -d "$UPSTREAM" ] || { echo "upstream not found: $UPSTREAM" >&2; exit 1; }

# Guard: a CRLF overlay against an LF upstream makes every file "differ" and
# the whole result is noise. This cost a full run once; it is checked now.
crlf=0
while IFS= read -r f; do
    head -c 8000 "$f" 2>/dev/null | grep -qU $'' && crlf=$((crlf + 1))
done < <(find "$OVERLAY" -name '*.cc' -o -name '*.h' 2>/dev/null | head -20)
if [ "$crlf" -gt 5 ]; then
    echo "REFUSING: the overlay has CRLF line endings ($crlf of 20 sampled)." >&2
    echo "Upstream Chromium is LF, so every file would falsely read as modified." >&2
    echo "Re-clone the overlay on Linux, or with -c core.autocrlf=false." >&2
    exit 1
fi

mkdir -p "$OUT/patches"
: > "$OUT/identical.txt"
: > "$OUT/modified.txt"
: > "$OUT/kiwi-only.txt"
: > "$OUT/binary-differs.txt"

echo "overlay:  $OVERLAY"
echo "upstream: $UPSTREAM"
echo "output:   $OUT"
echo

n_identical=0
n_modified=0
n_only=0
n_binary=0
n_total=0

# -print0 / read -d '' so paths with spaces survive. Kiwi has a few.
while IFS= read -r -d '' file; do
    rel="${file#"$OVERLAY"/}"

    # Kiwi's own repository metadata is not part of the overlay.
    case "$rel" in
        .git/*|.github/*) continue ;;
    esac

    n_total=$((n_total + 1))
    up="$UPSTREAM/$rel"

    if [ ! -e "$up" ]; then
        printf '%s\n' "$rel" >> "$OUT/kiwi-only.txt"
        n_only=$((n_only + 1))
        continue
    fi

    if cmp -s "$file" "$up"; then
        printf '%s\n' "$rel" >> "$OUT/identical.txt"
        n_identical=$((n_identical + 1))
        continue
    fi

    # Binary files can differ but cannot be diffed usefully — icons, mostly.
    if ! diff -q --binary "$file" "$up" >/dev/null 2>&1 && \
       { file --mime-encoding -b "$file" 2>/dev/null | grep -q binary; }; then
        printf '%s\n' "$rel" >> "$OUT/binary-differs.txt"
        n_binary=$((n_binary + 1))
        continue
    fi

    printf '%s\n' "$rel" >> "$OUT/modified.txt"
    n_modified=$((n_modified + 1))

    # One patch per file, named after its path so the series is navigable.
    patch_name="$(printf '%s' "$rel" | tr '/' '_')"
    diff -u --label "a/$rel" "$up" --label "b/$rel" "$file" \
        > "$OUT/patches/${patch_name}.patch" || true
done < <(find "$OVERLAY" -type f -print0)

{
    echo "# Kiwi overlay vs pristine Chromium"
    echo
    echo "overlay:  $OVERLAY"
    echo "upstream: $UPSTREAM"
    echo "date:     $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo
    printf '%-16s %6d\n' "total files"    "$n_total"
    printf '%-16s %6d  (%s%%)\n' "identical" "$n_identical" \
        "$(( n_total ? n_identical * 100 / n_total : 0 ))"
    printf '%-16s %6d  (%s%%)\n' "modified"  "$n_modified" \
        "$(( n_total ? n_modified * 100 / n_total : 0 ))"
    printf '%-16s %6d  (%s%%)\n' "kiwi-only" "$n_only" \
        "$(( n_total ? n_only * 100 / n_total : 0 ))"
    printf '%-16s %6d  (%s%%)\n' "binary differs" "$n_binary" \
        "$(( n_total ? n_binary * 100 / n_total : 0 ))"
    echo
    echo "The real delta is modified + kiwi-only + binary."
    echo "Everything in identical.txt is upstream code Kiwi merely copied."
} | tee "$OUT/summary.txt"

echo
echo "Top areas by modified file count:"
sed -E 's#^([^/]+/[^/]+)/.*#\1#' "$OUT/modified.txt" 2>/dev/null \
    | sort | uniq -c | sort -rn | head -15 | tee "$OUT/modified-by-area.txt"

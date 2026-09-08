#!/usr/bin/env bash
# Summarise an extracted delta: patch weight, hot files, and what Kiwi added.
set -uo pipefail

D="${1:-/build/delta}"

echo "=== total patch weight"
du -sh "$D/patches" 2>/dev/null
echo "lines of diff: $(cat "$D"/patches/*.patch 2>/dev/null | wc -l)"
echo "lines added:   $(cat "$D"/patches/*.patch 2>/dev/null | grep -c '^+[^+]')"
echo "lines removed: $(cat "$D"/patches/*.patch 2>/dev/null | grep -c '^-[^-]')"

echo
echo "=== 15 largest patches (lines of diff)"
for f in "$D"/patches/*.patch; do
    printf '%8d  %s\n' "$(wc -l < "$f")" "$(basename "$f" .patch)"
done | sort -rn | head -15

echo
echo "=== kiwi-only files by area"
sed -E 's#^([^/]+/[^/]+)/.*#\1#' "$D/kiwi-only.txt" 2>/dev/null \
    | sort | uniq -c | sort -rn | head -15

echo
echo "=== kiwi-only, top-level files"
grep -v '/' "$D/kiwi-only.txt" 2>/dev/null | head -15

echo
echo "=== binary differences by area (icons, resources)"
sed -E 's#^([^/]+/[^/]+)/.*#\1#' "$D/binary-differs.txt" 2>/dev/null \
    | sort | uniq -c | sort -rn | head -10

echo
echo "=== modified files mentioning extensions"
grep -ci extension "$D/modified.txt" 2>/dev/null

echo
echo "=== patches that touch V8 (for Stage 12's map only)"
grep -rl -i "v8::" "$D/patches" 2>/dev/null | wc -l

#!/usr/bin/env bash
# Every modified file should have exactly one patch. Report any that do not.
set -uo pipefail
D="${1:-/build/delta}"

want=$(mktemp); have=$(mktemp)
while IFS= read -r rel; do
    printf '%s.patch\n' "$(printf '%s' "$rel" | tr '/' '_')"
done < "$D/modified.txt" | sort > "$want"
ls "$D/patches" | sort > "$have"

echo "modified entries: $(wc -l < "$D/modified.txt")"
echo "patch files:      $(wc -l < "$have")"
echo "unique names:     $(sort -u "$want" | wc -l)"
echo
echo "--- modified but no patch:"
comm -23 "$want" "$have" | head
echo "--- patch but not in modified list:"
comm -13 "$want" "$have" | head
echo "--- duplicate flattened names (collisions):"
sort "$want" | uniq -d | head
rm -f "$want" "$have"

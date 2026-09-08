#!/usr/bin/env bash
#
# Find DEPS entries that exist but are empty.
#
# A dependency interrupted mid-fetch leaves an empty directory behind. gclient
# treats that as present and reports a successful sync; the failure surfaces
# hours later as "missing and no known rule to make it" during the build. This
# catches them before the build does.
#
# Usage: tools/verify-deps.sh [chromium-src]

set -uo pipefail

SRC="${1:-/opt/cobalt/chromium/m140/src}"
cd "$SRC" || { echo "no checkout at $SRC" >&2; exit 1; }

empty=0
checked=0

while IFS= read -r dep; do
    rel="${dep#src/}"
    [ -n "$rel" ] || continue

    # Skip entries for other platforms — they are absent by design.
    case "$rel" in
        *fuchsia*|*ios*|*chromeos*|ash/*|*/mac/*|*win*) continue ;;
    esac

    checked=$((checked + 1))

    if [ -d "$rel" ] && [ -z "$(ls -A "$rel" 2>/dev/null)" ]; then
        echo "EMPTY: $rel"
        empty=$((empty + 1))
    fi
done < <(grep -oE "^\s*'src/[^']+'" DEPS 2>/dev/null | tr -d " '" | sort -u)

echo
echo "checked $checked entries, $empty empty"

if [ "$empty" -gt 0 ]; then
    echo
    echo "Re-fetch each with its pinned revision from DEPS, e.g."
    echo "  grep -A2 \"'src/<path>'\" DEPS"
    echo "  git clone <url> <path> && git -C <path> checkout <rev>"
    exit 1
fi

echo "no empty dependencies"

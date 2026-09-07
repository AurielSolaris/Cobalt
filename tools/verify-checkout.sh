#!/usr/bin/env bash
# Sanity-check a pristine Chromium checkout before diffing against it.
set -uo pipefail

D="${1:-/build/chromium/src-105}"

echo "=== checkout: $D"
if [ ! -d "$D" ]; then echo "MISSING"; exit 1; fi

echo "=== HEAD"
git -C "$D" --no-pager log -1 --format='%H%n%d%n%ad' --date=short 2>&1 | head -4

echo "=== version file"
cat "$D/chrome/VERSION" 2>/dev/null | tr '\n' ' '; echo

echo "=== size"
du -sh "$D"

echo "=== file count (excluding .git)"
find "$D" -path "$D/.git" -prune -o -type f -print | wc -l

echo "=== areas Kiwi overlays"
for d in base chrome components content extensions net services ui remoting third_party/blink; do
    if [ -d "$D/$d" ]; then
        printf '  %-22s %8d files\n' "$d" "$(find "$D/$d" -type f | wc -l)"
    else
        printf '  %-22s %s\n' "$d" "MISSING"
    fi
done

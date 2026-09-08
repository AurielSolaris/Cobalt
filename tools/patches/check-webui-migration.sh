#!/usr/bin/env bash
# Size up the extensions WebUI migration — the top risk found by the patch trial.
#
# Kiwi patched five HTML files in chrome/browser/resources/extensions/ that no
# longer exist upstream. This reports what replaced them and how much of Kiwi's
# intent can still be located, so the reimplementation can be estimated rather
# than guessed at.
set -uo pipefail

SRC="${1:-/opt/cobalt/chromium/m140/src}"
PATCHES="${2:-patches/kiwi-105}"
UI="$SRC/chrome/browser/resources/extensions"

echo "=== M140 extensions WebUI: file types"
ls "$UI" 2>/dev/null | sed 's/.*\.//' | sort | uniq -c | sort -rn

echo
echo "=== the five files Kiwi patched, and their likely successors"
for base in manager detail_view item_list toggle_row toolbar; do
    printf '  %-14s ' "$base"
    found=$(ls "$UI" 2>/dev/null | grep -E "^${base}\.(ts|html\.ts|css)$" | tr '\n' ' ')
    if [ -n "$found" ]; then echo "-> $found"; else echo "-> (none by that name)"; fi
done

echo
echo "=== what Kiwi changed in those files (added lines only)"
for base in manager detail_view item_list toggle_row toolbar; do
    p="$PATCHES/chrome_browser_resources_extensions_${base}.html.patch"
    [ -f "$p" ] || continue
    n=$(grep -c '^+[^+]' "$p" 2>/dev/null || true)
    printf '  %-14s %3s added lines\n' "$base.html" "$n"
done

echo
echo "=== total Kiwi WebUI change"
cat "$PATCHES"/chrome_browser_resources_extensions_*.patch 2>/dev/null \
    | grep -c '^+[^+]' || true

echo
echo "=== is it Lit or Polymer now?"
grep -l "CrLitElement" "$UI"/*.ts 2>/dev/null | wc -l | sed 's/^/  Lit files:     /'
grep -l "PolymerElement" "$UI"/*.ts 2>/dev/null | wc -l | sed 's/^/  Polymer files: /'

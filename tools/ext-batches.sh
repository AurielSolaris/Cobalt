#!/usr/bin/env bash
# Group the extension-touching patches by subsystem, so Stage 5a can land them
# in compile-verifiable batches rather than one 70-patch avalanche.
T="${1:-/build/patch-trial}"
for cls in clean fuzzy conflict gone; do
  echo "===== $cls"
  grep -iE 'extension|_crx|webstore' "$T/$cls.txt" 2>/dev/null \
    | sed 's/\.patch$//' \
    | awk -F'/' '{
        if ($0 ~ /chrome_browser_extensions|chrome\/browser\/extensions/) g="chrome/browser/extensions";
        else if ($0 ~ /extensions_browser/) g="extensions/browser";
        else if ($0 ~ /extensions_common/) g="extensions/common";
        else if ($0 ~ /chrome_renderer|renderer/) g="renderer";
        else if ($0 ~ /webui|\.html|\.ts|\.css/) g="WebUI";
        else if ($0 ~ /android|\.java|\.xml/) g="android (spec only)";
        else g="other";
        c[g]++
      } END {for (k in c) printf "  %-28s %3d\n", k, c[k]}' | sort -k2 -rn
  printf "  %-28s %3d\n" "TOTAL" "$(grep -icE 'extension|_crx|webstore' "$T/$cls.txt" 2>/dev/null || echo 0)"
  echo
done

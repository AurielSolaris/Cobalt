#!/usr/bin/env bash
# Name the actual patches for batch 1: the extension core plumbing.
T="${1:-/build/patch-trial}"
echo "===== extensions/common + extensions/browser, by trial result"
for cls in clean fuzzy conflict gone; do
  n=$(grep -E 'extensions_(common|browser)' "$T/$cls.txt" 2>/dev/null | wc -l)
  echo "--- $cls ($n)"
  grep -E 'extensions_(common|browser)' "$T/$cls.txt" 2>/dev/null | sed 's/^/    /'
done

#!/usr/bin/env bash
# Is the overlay's line-ending style the same as upstream's?
#
# .ref/kiwi was cloned on Windows. If git converted it to CRLF on checkout,
# every text file differs from Chromium's LF and the whole diff is noise.
set -uo pipefail

OVERLAY="${1:-${REPO:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}/.ref/kiwi}"
UPSTREAM="${2:-/build/chromium/src-105}"

echo "=== git config in the overlay clone"
git -C "$OVERLAY" config --get core.autocrlf || echo "core.autocrlf: (unset)"
git -C "$OVERLAY" config --get core.eol || echo "core.eol: (unset)"

echo
echo "=== CRLF count in first 20 overlay text files"
crlf=0; lf=0
while IFS= read -r f; do
    if head -c 8000 "$f" 2>/dev/null | grep -qU $'\r'; then
        crlf=$((crlf + 1))
    else
        lf=$((lf + 1))
    fi
done < <(find "$OVERLAY" -name '*.cc' -o -name '*.h' -o -name '*.java' 2>/dev/null | head -20)
echo "overlay:  CRLF=$crlf  LF=$lf"

echo
echo "=== same check on upstream"
ucrlf=0; ulf=0
while IFS= read -r f; do
    if head -c 8000 "$f" 2>/dev/null | grep -qU $'\r'; then
        ucrlf=$((ucrlf + 1))
    else
        ulf=$((ulf + 1))
    fi
done < <(find "$UPSTREAM/base" -name '*.cc' 2>/dev/null | head -20)
echo "upstream: CRLF=$ucrlf  LF=$ulf"

echo
echo "=== a file present in both: does it differ only by line endings?"
for cand in $(cd "$OVERLAY" && find base chrome -type f -name '*.cc' 2>/dev/null | head -40); do
    if [ -f "$UPSTREAM/$cand" ]; then
        raw=$(cmp -s "$OVERLAY/$cand" "$UPSTREAM/$cand" && echo same || echo differ)
        norm=$(diff -q --strip-trailing-cr "$OVERLAY/$cand" "$UPSTREAM/$cand" >/dev/null 2>&1 && echo same || echo differ)
        printf '  %-60s raw=%-6s ignoring-CR=%s\n' "$cand" "$raw" "$norm"
    fi
done | head -12

#!/usr/bin/env bash
#
# First-pass classification of Kiwi's patch series.
#
# Emits a TSV of signals per patch so the series can be triaged at scale rather
# than read one file at a time. The signals are evidence, not a verdict — the
# committed classification in docs/kiwi-delta.md is reviewed by hand.
#
# Signals:
#   adblock   hardcoded ad/tracker domain or element-hiding strings
#   ext       extension system
#   brand     Kiwi naming, or its update/telemetry endpoints
#   ifzero    upstream code disabled with `#if 0` / `|| true` rather than config
#   v8        touches the JavaScript engine
#
# Usage: tools/classify-patches.sh [patch-dir] > classification.tsv

set -uo pipefail
DIR="${1:-patches/kiwi-105}"

printf 'lines\tadblock\text\tbrand\tifzero\tv8\tpatch\n'

for f in "$DIR"/*.patch "$DIR"/.*.patch; do
    [ -f "$f" ] || continue
    name="$(basename "$f" .patch)"
    added="$(grep -c '^+' "$f" 2>/dev/null || true)"

    adblock=$(grep -ciE 'adnet|popads|adblock|ads\.js|doubleclick|adtrackers|anti-adblock|PROMOTED|COMPANION|mealbar|adservice|googlesyndication|taboola|outbrain' "$f" 2>/dev/null || true)
    ext=$(grep -ciE 'extension|chrome\.runtime|manifest_version|crx|webstore' "$f" 2>/dev/null || true)
    brand=$(grep -ciE 'kiwi|geometry\.ee|kiwibrowser' "$f" 2>/dev/null || true)
    ifzero=$(grep -cE '^\+#if 0|\|\| true|&& 0' "$f" 2>/dev/null || true)
    v8=$(grep -c 'v8::' "$f" 2>/dev/null || true)

    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$added" "$adblock" "$ext" "$brand" "$ifzero" "$v8" "$name"
done | sort -rn

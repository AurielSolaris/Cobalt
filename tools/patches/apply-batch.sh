#!/usr/bin/env bash
#
# Apply a named set of Kiwi patches to the Chromium tree.
#
# Reports each patch's outcome rather than stopping at the first failure -- the
# point of a batch is to learn which of them still apply, not to get one in.
set -uo pipefail

SRC="${SRC:-/opt/cobalt/chromium/m140/src}"
PATCHDIR="${PATCHDIR:?usage: PATCHDIR=... apply-batch.sh <patch names...>}"

cd "$SRC" || { echo "no checkout at $SRC" >&2; exit 1; }

ok=0; fuzzy=0; failed=0
declare -a FAILED=()

for name in "$@"; do
    f="$PATCHDIR/${name}.patch"
    if [ ! -f "$f" ]; then
        printf '  %-58s MISSING\n' "$name"; failed=$((failed+1)); FAILED+=("$name"); continue
    fi
    if git apply --check "$f" 2>/dev/null; then
        git apply "$f" && { printf '  %-58s applied\n' "$name"; ok=$((ok+1)); }
    elif git apply --check -C1 "$f" 2>/dev/null; then
        git apply -C1 "$f" && { printf '  %-58s applied (fuzzy)\n' "$name"; fuzzy=$((fuzzy+1)); }
    else
        printf '  %-58s FAILED\n' "$name"
        git apply --check "$f" 2>&1 | sed 's/^/      /' | head -3
        failed=$((failed+1)); FAILED+=("$name")
    fi
done

echo
echo "applied $ok, fuzzy $fuzzy, failed $failed"
[ "$failed" -gt 0 ] && { echo "failed:"; printf '  %s\n' "${FAILED[@]}"; }
exit 0

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

# Kiwi switches upstream behaviour off by editing preprocessor conditions --
# 76 of its patches do this, per docs/patch-classification.md, which concluded:
# "Each needs the intent recovered and re-expressed as a real build flag or
# feature toggle. Mechanically re-applying them would be porting a bug."
#
# That is exactly what happened. extension_protocols.cc was applied verbatim
# and it disabled AllowExtensionResourceLoad -- the check enforcing which
# processes may load a chrome-extension:// resource -- and restricted
# component-extension bundle resources to paths containing "cryptotoken", an
# extension deleted from Chromium years ago. Neither was noticed for two builds
# because extensions were compiled out of Android until Route B turned them on.
#
# The rule is enforced now instead of documented: a patch adding a disabling
# construct is refused and must be ported by hand with its intent recovered.
# COBALT_ALLOW_DISABLERS=1 overrides, for when that intent is established.
DISABLERS='#if 0|#if !?defined[(]NEVER|&& 0|[|][|] true'

for name in "$@"; do
    f="$PATCHDIR/${name}.patch"
    if [ ! -f "$f" ]; then
        printf '  %-58s MISSING\n' "$name"; failed=$((failed+1)); FAILED+=("$name"); continue
    fi
    if [ "${COBALT_ALLOW_DISABLERS:-0}" != 1 ] && grep -E "^[+]" "$f" | grep -qE "$DISABLERS"; then
        printf '  %-58s REFUSED (disables upstream code)\n' "$name"
        grep -E "^[+]" "$f" | grep -E "$DISABLERS" | sed 's/^/      /' | head -3
        failed=$((failed+1)); FAILED+=("$name"); continue
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

#!/usr/bin/env bash
#
# Apply Cobalt's entire patch series to a Chromium checkout.
#
# Usage:
#   tools/patches/apply-all.sh              apply the series
#   tools/patches/apply-all.sh --check      report what would run, change nothing
#
# Reads tools/patches/series.txt, which is the single declaration of what Cobalt
# changes about Chromium. Before this existed the series lived in commit
# messages and in whoever had most recently run the scripts, which meant nobody
# -- including us, on a fresh machine -- could reproduce a build.
#
# Safe to re-run. Every entry is idempotent, and the series MUST be re-run after
# every `gclient sync`: one of the changes lands inside a git submodule
# (third_party/search_engines_data/resources) that sync silently resets, and
# which src's own `git status` will never report as dirty.
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SRC="${SRC:-/opt/cobalt/chromium/m140/src}"
SERIES="${SERIES:-$REPO/tools/patches/series.txt}"
CHECK=0
[ "${1:-}" = "--check" ] && CHECK=1

[ -d "$SRC" ]     || { echo "no checkout at $SRC" >&2; exit 1; }
[ -f "$SERIES" ]  || { echo "no series at $SERIES" >&2; exit 1; }

PY="$(command -v python3 || command -v python)" \
    || { echo "python3 not found" >&2; exit 1; }

echo "=== Cobalt patch series"
echo "    repo:   $REPO"
echo "    src:    $SRC"
echo "    series: $SERIES"
[ "$CHECK" = 1 ] && echo "    MODE:   --check (nothing will be modified)"
echo

ok=0; failed=0
declare -a FAILED=()

while IFS= read -r line || [ -n "$line" ]; do
    # Strip comments and blank lines. A trailing comment on an entry line would
    # be ambiguous with a script argument, so only whole-line comments count.
    case "$line" in ''|'#'*) continue ;; esac

    kind="${line%% *}"
    rest="${line#* }"

    case "$kind" in
    note)
        echo
        echo "$rest"
        ;;
    kiwi)
        # "kiwi <name> :: <marker>" -- if <marker> is already present in every
        # file the patch touches, the patch counts as applied. Needed because
        # `git apply --reverse --check` cannot recognise a hunk that a later
        # entry in the series has since edited: cobalt-chromesearch-scheme.py
        # rewrites the very line Kiwi's url_pattern.cc patch adds.
        marker=""
        case "$rest" in
        *" :: "*)
            marker="${rest#* :: }"
            rest="${rest%% :: *}"
            ;;
        esac
        if [ "$CHECK" = 1 ]; then
            printf '  %-58s would apply\n' "$rest"
            continue
        fi
        if [ -n "$marker" ]; then
            files=$(sed -n 's|^+++ b/||p' "$REPO/patches/kiwi-105/${rest}.patch")
            found=1
            [ -n "$files" ] || found=0
            for rel in $files; do
                grep -qF -- "$marker" "$SRC/$rel" 2>/dev/null || found=0
            done
            if [ "$found" = 1 ]; then
                printf '  %-58s already applied (marker)\n' "$rest"
                ok=$((ok+1)); continue
            fi
        fi
        # apply-batch.sh carries the disabler refusal gate; go through it rather
        # than calling git apply directly, so the gate cannot be bypassed by
        # adding an entry here.
        if SRC="$SRC" PATCHDIR="$REPO/patches/kiwi-105" \
             "$REPO/tools/patches/apply-batch.sh" "$rest" </dev/null 2>&1 | sed 's/^/  /'; then
            ok=$((ok+1))
        else
            failed=$((failed+1)); FAILED+=("kiwi $rest")
        fi
        ;;
    tool)
        # $SRC in the series expands here, not in the shell that wrote the file.
        cmd="${rest//\$SRC/$SRC}"
        script="${cmd%% *}"
        args="${cmd#* }"
        [ "$args" = "$cmd" ] && args=""
        path="$REPO/tools/$script"
        if [ ! -f "$path" ]; then
            printf '  %-58s MISSING\n' "$script"
            failed=$((failed+1)); FAILED+=("tool $script"); continue
        fi
        if [ "$CHECK" = 1 ]; then
            printf '  %-58s would run\n' "$script"
            continue
        fi
        printf '  %s\n' "$script"
        # shellcheck disable=SC2086
        if "$PY" "$path" $args </dev/null 2>&1 | sed 's/^/      /'; then
            ok=$((ok+1))
        else
            failed=$((failed+1)); FAILED+=("tool $script")
        fi
        ;;
    backport)
        # "backport <repo> <CVE>" -- an upstream security fix adapted to M140,
        # from patches/security/<CVE>.diff, applied inside <repo> (a path under
        # $SRC, "." for src itself; V8, ANGLE, Skia and Dawn are separate git
        # repositories). Decision 0016: a backport that silently fails to apply
        # reports as fixed, which is worse than a missing one, so this is
        # all-or-nothing: already applied, applied whole, or a failure.
        # read, not ${rest#* }: the series aligns these in columns.
        read -r sub cve <<< "$rest"
        patch="$REPO/patches/security/${cve}.diff"
        dir="$SRC/$sub"
        if [ ! -f "$patch" ]; then
            printf '  %-58s MISSING\n' "$cve"
            failed=$((failed+1)); FAILED+=("backport $cve"); continue
        fi
        if [ "$CHECK" = 1 ]; then
            printf '  %-58s would apply in %s\n' "$cve" "$sub"
            continue
        fi
        if git -C "$dir" apply --reverse --check "$patch" 2>/dev/null; then
            printf '  %-58s already applied\n' "$cve"
            ok=$((ok+1))
        elif git -C "$dir" apply --check "$patch" 2>/dev/null \
             && git -C "$dir" apply "$patch"; then
            printf '  %-58s applied (%s)\n' "$cve" "$sub"
            ok=$((ok+1))
        else
            printf '  %-58s DOES NOT APPLY in %s\n' "$cve" "$sub"
            git -C "$dir" apply --check "$patch" 2>&1 | sed 's/^/      /'
            failed=$((failed+1)); FAILED+=("backport $cve")
        fi
        ;;
    *)
        echo "  unknown entry kind: $kind" >&2
        failed=$((failed+1)); FAILED+=("$line")
        ;;
    esac
done < "$SERIES"

echo
if [ "$CHECK" = 1 ]; then
    echo "=== check only; nothing applied"
    exit 0
fi

echo "=== $ok applied, $failed failed"
if [ "$failed" -gt 0 ]; then
    printf '    %s\n' "${FAILED[@]}"
    echo
    echo "The tree is PARTIALLY patched. Do not build from it -- fix the"
    echo "failures and re-run, or reset the checkout." >&2
    exit 1
fi

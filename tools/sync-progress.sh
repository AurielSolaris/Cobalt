#!/usr/bin/env bash
# Estimate how far through the DEPS sync we are.
#
# gclient reports no percentage, so this counts declared dependencies against
# those on disk. Two corrections matter, and without them the number is
# meaningless:
#
#   1. DEPS declares dependencies for every platform Chromium targets. With
#      target_os=["android"] the ChromeOS (ash/), Fuchsia, iOS and desktop-only
#      entries are correctly never fetched, so counting them as "missing" makes
#      an Android build look permanently half-done.
#   2. Some entries are single files, not directories. Testing for a non-empty
#      directory reports every one of those as missing.
set -uo pipefail

DIR="${1:-/build/chromium/m140}"
SRC="$DIR/src"
LOG="${2:-/build/logs/fetch-m140-resume.log}"

[ -d "$SRC" ] || { echo "no checkout at $SRC" >&2; exit 1; }

mapfile -t all_deps < <(grep -oE "^\s*'src/[^']+'" "$SRC/DEPS" 2>/dev/null \
    | tr -d " '" | sort -u)

# Entries that do not apply to an Android build.
skip_re='^src/(ash|fuchsia|ios|chromeos)/|/fuchsia|fuchsia/|_fuchsia|/ios/|mac_|/win/|win_|cros_|chromeos'

declared=0; present=0; skipped=0
missing=()

for d in "${all_deps[@]}"; do
    rel="${d#src/}"
    path="$SRC/$rel"

    if printf '%s' "$d" | grep -qE "$skip_re"; then
        skipped=$((skipped + 1))
        continue
    fi

    declared=$((declared + 1))

    # A dependency is present if it is a non-empty directory OR an existing file.
    if { [ -d "$path" ] && [ -n "$(ls -A "$path" 2>/dev/null)" ]; } || [ -f "$path" ]; then
        present=$((present + 1))
    else
        missing+=("$rel")
    fi
done

pct=0
[ "$declared" -gt 0 ] && pct=$(( present * 100 / declared ))

bar=""
filled=$(( pct * 40 / 100 ))
for ((i = 0; i < 40; i++)); do
    if [ "$i" -lt "$filled" ]; then bar+="#"; else bar+="."; fi
done

echo "  applicable to Android : $declared"
echo "  populated             : $present"
echo "  still missing         : $(( declared - present ))"
echo "  skipped (other OSes)  : $skipped"
echo
printf '  [%s] %d%%\n' "$bar" "$pct"
echo
echo "  size on disk : $(du -sh "$DIR" 2>/dev/null | cut -f1)"
echo "  disk free    : $(df -h "$DIR" | tail -1 | awk '{print $4}')"
echo "  gclient      : $(pgrep -fc 'depot_tools/gclient' 2>/dev/null || echo 0)"
echo "  fetchers     : $(pgrep -fc 'git-remote-https|cipd' 2>/dev/null || echo 0)"

if [ "${#missing[@]}" -gt 0 ]; then
    echo
    echo "  still to fetch (first 10):"
    printf '    %s\n' "${missing[@]:0:10}"
fi

#!/usr/bin/env bash
# What Google Play Services is still in the shipped APK, and who pulls it in.
#
# Decision 0013 removes GMS entirely and calls it a release gate, so "how much
# is left" has to be a number someone can produce on demand rather than a
# feeling. This reads the real dependency graph of the target we actually ship,
# not a grep over the tree: most of the tree's GMS references belong to targets
# Cobalt never builds, and counting those makes the job look bigger than it is
# while hiding which parts are load-bearing.
#
# Done is: MODULES = 0.
#
# Usage:  tools/build/gms-inventory.sh [--verbose]
#         SRC=/path/to/src tools/build/gms-inventory.sh
set -euo pipefail

SRC="${SRC:-/opt/cobalt/chromium/m140/src}"
OUT="${OUT:-out/Default}"
GN="$SRC/buildtools/linux64/gn"
VERBOSE=0
[ "${1:-}" = "--verbose" ] && VERBOSE=1

[ -d "$SRC" ] || { echo "no checkout at $SRC" >&2; exit 1; }
[ -x "$GN" ] || { echo "no gn at $GN" >&2; exit 1; }
[ -f "$SRC/$OUT/args.gn" ] || { echo "no args.gn in $SRC/$OUT; run gen first" >&2; exit 1; }
cd "$SRC"

modules=$("$GN" desc "$OUT" "//chrome/android:chrome_public_apk" deps --all 2>/dev/null \
    | grep -oE 'google_play_services_[a-z_]+_java$' \
    | sed 's/_java$//' | sort -u)

if [ -z "$modules" ]; then
    echo "MODULES 0"
    echo
    echo "No Google Play Services in chrome_public_apk. Decision 0013's gate is met."
    exit 0
fi

count=$(printf '%s\n' "$modules" | wc -l | tr -d ' ')
echo "MODULES $count"
echo

# For each module, who depends on it. Targets whose names say they are tests are
# reported separately: they are not in the shipped APK's graph and removing them
# is not what closes the gate, but leaving them referencing deleted classes is
# how a build breaks later.
first_party_total=0
for m in $modules; do
    refs=$("$GN" refs "$OUT" "//third_party/android_deps:${m}_java" 2>/dev/null \
        | grep -v '^//third_party/android_deps' | grep -v '__' || true)
    prod=$(printf '%s\n' "$refs" | grep -viE 'test|javatest|junit' | grep . || true)
    test=$(printf '%s\n' "$refs" | grep -iE 'test|javatest|junit' | grep . || true)

    short=${m#google_play_services_}
    if [ -z "$prod" ]; then
        # Nothing first-party asks for it; it arrives underneath another AAR and
        # falls out for free when that one goes.
        printf '  %-22s transitive only\n' "$short"
        [ "$VERBOSE" = 1 ] && [ -n "$test" ] && printf '%s\n' "$test" | sed 's/^/        test  /'
        continue
    fi
    n=$(printf '%s\n' "$prod" | wc -l | tr -d ' ')
    first_party_total=$((first_party_total + n))
    printf '  %-22s %s first-party\n' "$short" "$n"
    printf '%s\n' "$prod" | sed 's/^/        /'
    [ "$VERBOSE" = 1 ] && [ -n "$test" ] && printf '%s\n' "$test" | sed 's/^/        test  /'
done

echo
echo "first-party dependency edges: $first_party_total"
echo
echo "Java files importing com.google.android.gms (excluding tests and out/):"
grep -rl 'com\.google\.android\.gms' --include='*.java' . 2>/dev/null \
    | grep -v '^./out/' | grep -viE 'test|junit' \
    | cut -d/ -f2-4 | sort | uniq -c | sort -rn | sed 's/^/  /'

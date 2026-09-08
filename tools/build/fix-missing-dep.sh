#!/usr/bin/env bash
#
# Fetch a single DEPS entry that gclient left empty.
#
# A dependency interrupted mid-fetch (by rate limiting, say) can be left as an
# existing but empty directory. gclient then treats it as present and reports a
# successful sync, and the failure only surfaces much later as a missing source
# file during the build. This re-clones one entry at its pinned revision.
#
# Usage: tools/fix-missing-dep.sh third_party/libaddressinput/src

set -uo pipefail

SRC="${SRC:-/opt/cobalt/chromium/m140/src}"
DEP="${1:?usage: $0 <path-relative-to-src>}"

cd "$SRC" || exit 1

# DEPS records entries as  'src/<path>': '<url>@<revision>'
line=$(grep -A3 "'src/${DEP}'" DEPS | tr -d " \n'" | head -1)
url_rev=$(grep -oE "https?://[^']+@[0-9a-f]{6,40}" <(grep -A4 "'src/${DEP}'" DEPS) | head -1)

if [ -z "$url_rev" ]; then
    echo "could not find '$DEP' in DEPS" >&2
    grep -n -A4 "$(basename "$DEP")" DEPS | head -12 >&2
    exit 1
fi

url="${url_rev%@*}"
rev="${url_rev##*@}"

echo "dep : $DEP"
echo "url : $url"
echo "rev : $rev"
echo

if [ -d "$DEP" ] && [ -n "$(ls -A "$DEP" 2>/dev/null)" ]; then
    echo "already populated; nothing to do"
    exit 0
fi

rm -rf "$DEP"
mkdir -p "$(dirname "$DEP")"

echo "cloning…"
git clone --filter=blob:none "$url" "$DEP" || exit 1
git -C "$DEP" checkout -q "$rev" || exit 1

echo
echo "now at: $(git -C "$DEP" rev-parse HEAD)"
echo "files : $(find "$DEP" -type f | wc -l)"

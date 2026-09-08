#!/usr/bin/env bash
#
# After migrating the checkout: verify it, then build.
#
# The copy came off a drive that had already failed to read a file it wrote
# days earlier, so "the copy finished" is not evidence the checkout is sound.
# git fsck is, for everything git tracks.
set -uo pipefail

SRC="${SRC:-/opt/cobalt/chromium/m140/src}"
cd "$SRC" || { echo "no checkout at $SRC" >&2; exit 1; }

echo "=== checkout identity"
printf '  VERSION '; tr '\n' ' ' < chrome/VERSION; echo
printf '  HEAD    %s\n' "$(git rev-parse --short HEAD 2>/dev/null)"

echo
echo "=== files the build has previously died without"
ok=1
for f in chrome/VERSION \
         third_party/libaddressinput/src/cpp/src/address_input_helper.cc \
         third_party/llvm-build/Release+Asserts/bin/clang \
         third_party/angle/src/libANGLE/Stream.cpp \
         v8/BUILD.gn \
         third_party/blink/renderer/core/dom/document.cc; do
    if [ -r "$f" ]; then printf '  OK      %s\n' "$f"
    else printf '  MISSING %s\n' "$f"; ok=0; fi
done
[ "$ok" = 1 ] || { echo "checkout incomplete" >&2; exit 1; }

echo
echo "=== git object integrity"
if git fsck --no-dangling --no-progress 2>&1 | head -20; then
    echo "  fsck completed"
else
    echo "  fsck reported problems -- see above" >&2
fi

echo
echo "=== empty DEPS entries"
bash /mnt/c/Users/Auriel/Documents/app.auriel/Cobalt/tools/verify-deps.sh "$SRC" 2>&1 | tail -3

echo
echo "=== building"
exec bash /mnt/c/Users/Auriel/Documents/app.auriel/Cobalt/tools/resume-build.sh --clean

#!/usr/bin/env bash
SRC=/opt/cobalt/chromium/m140/src
OLD=/build/chromium/m140/src
echo "=== sizes"
printf '  source (E:) : %s\n' "$(du -sh --exclude=out "$OLD" 2>/dev/null | cut -f1)"
printf '  dest   (C:) : %s\n' "$(du -sh "$SRC" 2>/dev/null | cut -f1)"
echo
echo "=== file counts"
printf '  source : %s\n' "$(find "$OLD" -path "$OLD/out" -prune -o -type f -print 2>/dev/null | wc -l)"
printf '  dest   : %s\n' "$(find "$SRC" -type f 2>/dev/null | wc -l)"
echo
echo "=== free space on the distro filesystem"
df -h / | tail -1
echo
echo "=== files past builds have died without"
for f in chrome/VERSION \
         third_party/libaddressinput/src/cpp/src/address_input_helper.cc \
         third_party/llvm-build/Release+Asserts/bin/clang \
         third_party/angle/src/libANGLE/Stream.cpp \
         v8/BUILD.gn \
         third_party/blink/renderer/core/dom/document.cc; do
  if [ -r "$SRC/$f" ]; then printf '  OK      %s\n' "$f"; else printf '  MISSING %s\n' "$f"; fi
done
echo
echo "=== git integrity (connectivity only, fast)"
cd "$SRC" && timeout 600 git fsck --connectivity-only --no-dangling --no-progress 2>&1 | head -10
echo "  fsck rc=$?"

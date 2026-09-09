#!/usr/bin/env bash
# Are extensions actually compiled into this build?
#
# Batch 1 patches extensions/ -- which does nothing at all if the extension
# subsystem is not built for Android in this configuration. Worth knowing before
# spending a rebuild on it.
S=${SRC:-/opt/cobalt/chromium/m140/src}

echo "=== enable_extensions declaration"
grep -rn -B3 -A6 "enable_extensions *=" "$S/extensions/buildflags/buildflags.gni" 2>/dev/null | head -20

echo
echo "=== resolved value in our build"
if [ -f "$S/out/Default/args.gn" ]; then
  grep -n "extensions" "$S/out/Default/args.gn" || echo "  not set explicitly in args.gn (using default)"
fi

echo
echo "=== did the build actually compile extension objects?"
find "$S/out/Default/obj/extensions" -name '*.o' 2>/dev/null | wc -l | sed 's/^/  extensions objects: /'
find "$S/out/Default/obj/chrome/browser/extensions" -name '*.o' 2>/dev/null | wc -l | sed 's/^/  chrome extension objects: /'

echo
echo "=== was command.cc compiled?"
find "$S/out/Default" -name 'command.o' -path '*extensions*' 2>/dev/null | head -3 | sed 's/^/  /'

echo
echo "=== IS_DESKTOP_ANDROID in this build?"
grep -rn "is_desktop_android" "$S/out/Default/args.gn" 2>/dev/null || echo "  not set (defaults false)"

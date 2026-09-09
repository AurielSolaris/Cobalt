#!/usr/bin/env bash
# Root the gn graph at the APK we build.
#
# gn otherwise evaluates every target reachable from gn_all, including desktop
# test binaries that assert !is_android -- which is a whole class of gn gen
# failures that has nothing to do with what we ship. Rooting here also makes
# gn gen noticeably faster.
S=${SRC:-/opt/cobalt/chromium/m140/src}
cd "$S" || exit 1
if grep -q '^root = ' .gn; then
    echo "already set: $(grep '^root = ' .gn)"
else
    printf '\n# Cobalt: evaluate only the APK we build, not every target in gn_all.\nroot = "//chrome/android:chrome_public_apk"\n' >> .gn
    echo "set: $(grep '^root = ' .gn)"
fi

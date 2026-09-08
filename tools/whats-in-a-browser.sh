#!/usr/bin/env bash
SRC=${SRC:-/opt/cobalt/chromium/m140/src}
cd "$SRC" || exit 1
count() { printf '  %-34s %8s files\n' "$1" "$(find "$2" -type f \( -name '*.cc' -o -name '*.h' -o -name '*.cpp' \) 2>/dev/null | wc -l)"; }
echo "=== C/C++ source files by subsystem"
count "Blink (the web platform)"      third_party/blink/renderer
count "  ...of which: CSS"            third_party/blink/renderer/core/css
count "  ...of which: layout"         third_party/blink/renderer/core/layout
count "  ...of which: JS bindings"    third_party/blink/renderer/bindings
count "V8 (JavaScript engine)"        v8/src
count "net (the network stack)"       net
count "BoringSSL (its own TLS)"       third_party/boringssl/src
count "Skia (graphics)"               third_party/skia/src
count "ANGLE (GL translation)"        third_party/angle/src
count "content (process model)"       content
count "media"                         media
echo
echo "=== web platform interfaces Blink implements (.idl)"
printf '  %-34s %8s files\n' "IDL definitions" "$(find third_party/blink/renderer -name '*.idl' 2>/dev/null | wc -l)"
echo
echo "=== third_party dependencies vendored in"
printf '  %-34s %8s dirs\n' "third_party/" "$(find third_party -maxdepth 1 -type d 2>/dev/null | wc -l)"

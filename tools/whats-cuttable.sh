#!/usr/bin/env bash
SRC=${SRC:-/opt/cobalt/chromium/m140/src}
cd "$SRC" || exit 1
sz() { printf '  %-30s %8s files  %6s\n' "$1" \
  "$(find "$2" -type f \( -name '*.cc' -o -name '*.h' -o -name '*.cpp' \) 2>/dev/null | wc -l)" \
  "$(du -sh "$2" 2>/dev/null | cut -f1)"; }

echo "=== MODERN features (candidates for cutting)"
sz "WebRTC"                third_party/webrtc
sz "Dawn (WebGPU)"         third_party/dawn
sz "PDFium (PDF viewer)"   third_party/pdfium
sz "DevTools frontend"     third_party/devtools-frontend
sz "WebXR / VR"            device/vr
sz "Web Bluetooth"         device/bluetooth
sz "Web USB"               device/usb
sz "Speech"                components/speech
echo
echo "=== LEGACY support (what dropping pre-2010 would target)"
sz "XSLT (libxslt)"        third_party/libxslt
sz "ICU (encodings etc)"   third_party/icu
printf '  %-30s %8s\n' "quirks-mode references" "$(grep -rl 'InQuirksMode\|kQuirksMode' third_party/blink/renderer 2>/dev/null | wc -l)"
printf '  %-30s %8s\n' "legacy encoding tables" "$(find third_party/icu -name '*.cnv' 2>/dev/null | wc -l)"
echo
echo "=== GN args that actually turn features off"
grep -rhoE '^ *(enable_[a-z_]+|use_dawn|toolkit_views) = (true|false)' \
  build/config/features.gni chrome/common/features.gni extensions/buildflags/buildflags.gni \
  printing/buildflags/buildflags.gni pdf/features.gni device/vr/buildflags/buildflags.gni 2>/dev/null \
  | sed 's/^ *//' | sort -u | head -30

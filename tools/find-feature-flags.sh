#!/usr/bin/env bash
SRC=${SRC:-/opt/cobalt/chromium/m140/src}
cd "$SRC" || exit 1
echo "=== VR / XR buildflags"
find . -path ./out -prune -o -name 'buildflags.gni' -path '*vr*' -print 2>/dev/null | head
grep -rn "enable_vr\b\|enable_openxr\|enable_cardboard\|enable_arcore" device/vr/buildflags/buildflags.gni chrome/browser/BUILD.gn 2>/dev/null | head -10
echo
echo "=== declared args mentioning vr/xr"
grep -rhn "^ *enable_[a-z_]*\(vr\|xr\|arcore\|cardboard\)[a-z_]* *=" --include='*.gni' --include='*.gn' . 2>/dev/null | sed 's/^ *//' | sort -u | head -10
echo
echo "=== HID"
grep -rhn "^ *enable_[a-z_]*hid[a-z_]* *=" --include='*.gni' --include='*.gn' . 2>/dev/null | sed 's/^ *//' | sort -u | head
find . -path ./out -prune -o -type d -name 'hid' -print 2>/dev/null | head -5
echo
echo "=== Bluetooth / USB / NFC dirs (keeping these)"
for d in device/bluetooth services/device/usb device/nfc services/device/public/mojom; do
  [ -d "$d" ] && printf '  %-34s %s\n' "$d" "$(du -sh "$d" 2>/dev/null | cut -f1)"
done

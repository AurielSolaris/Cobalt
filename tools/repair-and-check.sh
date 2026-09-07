#!/usr/bin/env bash
# Repair the build volume after an I/O-induced read-only remount, then report
# what survived. Resolves by LABEL, never by letter.
set -uo pipefail
LABEL="${LABEL:-cobalt-build}"
MOUNT="${MOUNT:-/build}"

DEV="$(blkid -L "$LABEL" 2>/dev/null || true)"
[ -n "$DEV" ] || { echo "no device labelled $LABEL" >&2; exit 1; }
echo "device: $DEV"

if grep -E "^$DEV +(/|/mnt/wslg[^ ]*) " /proc/mounts >/dev/null 2>&1; then
    echo "REFUSING: $DEV backs a root or system mount" >&2; exit 1
fi

mountpoint -q "$MOUNT" && { echo "unmounting"; umount "$MOUNT" || umount -l "$MOUNT"; }
sleep 2

echo
echo "=== e2fsck"
e2fsck -fy "$DEV"; rc=$?
case "$rc" in
  0) echo "clean" ;;
  1) echo "errors CORRECTED" ;;
  2) echo "errors corrected, reboot advised" ;;
  4) echo "errors REMAIN UNCORRECTED" ;;
  *) echo "e2fsck exit $rc" ;;
esac

echo
echo "=== remount"
mount -L "$LABEL" "$MOUNT" || { echo "mount failed" >&2; exit 1; }
grep ' /build ' /proc/mounts

echo
echo "=== writable?"
if touch "$MOUNT/.rwtest" 2>/dev/null; then echo "WRITABLE"; rm -f "$MOUNT/.rwtest"; else echo "STILL READ-ONLY"; exit 1; fi

echo
echo "=== what survived"
S="$MOUNT/chromium/m140/src"
printf '  objects : %s\n' "$(find "$S/out/Default/obj" -name '*.o' 2>/dev/null | wc -l)"
printf '  version : %s\n' "$(tr '\n' ' ' < "$S/chrome/VERSION" 2>/dev/null)"
df -h "$MOUNT" | tail -1

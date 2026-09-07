#!/usr/bin/env bash
# Unmount, check, and remount the build volume.
#
# Two traps, both hit for real on this project:
#
#   * Never lazy-unmount before fsck. "umount -l" detaches the mount point but
#     leaves the filesystem live, so e2fsck refuses with "Cannot continue,
#     aborting" (exit 8) while the caller sees a writable volume and assumes it
#     was checked.
#   * systemd's fstab-generator re-mounts /build the moment it is unmounted, so
#     e2fsck reports "in use" against a device nothing appears to hold. The
#     fstab entry has to be taken out of play first.
set -uo pipefail
LABEL="${LABEL:-cobalt-build}"
MOUNT="${MOUNT:-/build}"
FSTAB=/etc/fstab

DEV="$(blkid -L "$LABEL" 2>/dev/null || true)"
[ -n "$DEV" ] || { echo "no device labelled $LABEL" >&2; exit 1; }
echo "device: $DEV"

if grep -E "^$DEV +(/|/mnt/wslg[^ ]*) " /proc/mounts >/dev/null 2>&1; then
    echo "REFUSING: backs a root or system mount" >&2; exit 1
fi

restore() {
    if [ -f "$FSTAB.cobalt-bak" ]; then
        cp "$FSTAB.cobalt-bak" "$FSTAB" && rm -f "$FSTAB.cobalt-bak"
        systemctl daemon-reload 2>/dev/null || true
        echo "fstab restored"
    fi
}
trap restore EXIT

cp "$FSTAB" "$FSTAB.cobalt-bak"
sed -i "s|^\([^#].*[[:space:]]$MOUNT[[:space:]]\)|#COBALT-TEMP \1|" "$FSTAB"
systemctl daemon-reload 2>/dev/null || true
echo "fstab entry disabled for the check"

mountpoint -q "$MOUNT" && { umount "$MOUNT" || { echo "umount failed; NOT lazy-unmounting" >&2; exit 1; }; }
sleep 2
if grep -q "^$DEV " /proc/mounts; then
    echo "still mounted:" >&2; grep "^$DEV " /proc/mounts >&2; exit 1
fi
echo "unmounted cleanly"

echo
echo "=== e2fsck -fy $DEV"
e2fsck -fy "$DEV"; rc=$?
echo
case "$rc" in
  0) echo "RESULT: clean, no errors" ;;
  1) echo "RESULT: errors were CORRECTED" ;;
  2) echo "RESULT: errors corrected, remount advised" ;;
  4) echo "RESULT: errors REMAIN UNCORRECTED -- data loss likely" ;;
  8) echo "RESULT: operational error, check did NOT run" ;;
  *) echo "RESULT: e2fsck exit $rc" ;;
esac

restore
trap - EXIT
mount -L "$LABEL" "$MOUNT" && grep ' /build ' /proc/mounts
printf 'objects: %s\n' "$(find "$MOUNT/chromium/m140/src/out/Default/obj" -name '*.o' 2>/dev/null | wc -l)"
exit "$rc"

#!/usr/bin/env bash
#
# Unmount and repair the cobalt-build volume.
#
# ALWAYS resolves the device by LABEL, never by letter.
#
# WSL reassigns /dev/sdX across restarts. After one restart /dev/sdd — which had
# been the build volume — was the Ubuntu distro's root filesystem, and an
# e2fsck -fy aimed at "sdd" would have been aimed at the running OS. It refused
# only because it was mounted. Device letters are not identity; labels are.
set -euo pipefail

LABEL="${LABEL:-cobalt-build}"
MOUNT="${MOUNT:-/build}"

DEV="$(blkid -L "$LABEL" 2>/dev/null || true)"

if [ -z "$DEV" ]; then
    echo "no device labelled '$LABEL' — is the VHDX attached?" >&2
    echo "  wsl --mount \"E:\\cobalt-build.vhdx\" --vhd --bare" >&2
    exit 1
fi

echo "label  : $LABEL"
echo "device : $DEV"

# Refuse if this device backs any root or system mount.
# Match the mountpoint exactly: "/" or something under /mnt/wslg. An earlier
# version used (/|/mnt/wslg) without anchoring, which matched /build too — the
# very volume it was meant to let through.
if grep -E "^$DEV +(/|/mnt/wslg[^ ]*) " /proc/mounts >/dev/null 2>&1; then
    echo "REFUSING: $DEV backs a root or system mount." >&2
    grep "^$DEV " /proc/mounts >&2
    exit 1
fi

if mountpoint -q "$MOUNT"; then
    echo "unmounting $MOUNT"
    umount "$MOUNT" || { echo "could not unmount; something is using it" >&2; exit 1; }
fi

# Any remaining mount of this device blocks a repair.
if grep -q "^$DEV " /proc/mounts; then
    echo "still mounted elsewhere:" >&2
    grep "^$DEV " /proc/mounts >&2
    exit 1
fi

echo
echo "=== e2fsck -fy $DEV"
set +e
e2fsck -fy "$DEV"
rc=$?
set -e

echo
case "$rc" in
    0) echo "clean, no errors" ;;
    1) echo "errors were found and CORRECTED" ;;
    2) echo "errors corrected; a reboot of the volume is advised" ;;
    4) echo "errors remain UNCORRECTED — data loss likely" ;;
    *) echo "e2fsck exited $rc" ;;
esac

echo
echo "=== remounting"
mount -L "$LABEL" "$MOUNT"
df -h "$MOUNT" | tail -1
ls "$MOUNT"

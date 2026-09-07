#!/usr/bin/env bash
DEV="$(blkid -L cobalt-build)"
echo "device: $DEV"
echo
echo "--- fstab entries for /build"
grep -n build /etc/fstab || echo "  (none)"
echo
echo "--- unmount"
umount /build && echo "  umount ok" || echo "  umount FAILED"
echo
echo "--- /proc/mounts right now"
grep -c "$DEV" /proc/mounts | sed 's/^/  refs: /'
echo
echo "--- e2fsck immediately, no sleep"
e2fsck -fy "$DEV"
echo "exit=$?"

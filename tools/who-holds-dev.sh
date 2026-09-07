#!/usr/bin/env bash
DEV="$(blkid -L cobalt-build 2>/dev/null)"
echo "device: $DEV"
echo
echo "=== every mount referencing it"
grep -n "$DEV" /proc/mounts || echo "  (none in /proc/mounts)"
echo
echo "=== mountinfo (catches bind mounts and other namespaces)"
grep -n "$(basename "$DEV")" /proc/self/mountinfo || echo "  (none)"
echo
echo "=== other mount namespaces holding it"
for p in /proc/[0-9]*; do
  [ -r "$p/mountinfo" ] || continue
  if grep -q "$(basename "$DEV")" "$p/mountinfo" 2>/dev/null; then
    echo "  pid $(basename "$p"): $(tr -d '\0' < "$p/comm" 2>/dev/null)"
  fi
done | sort -u | head -20
echo
echo "=== fuser on the device node"
fuser -v "$DEV" 2>&1 | head -10
echo
echo "=== holders in sysfs"
cat "/sys/class/block/$(basename "$DEV")/holders/"* 2>/dev/null || echo "  (no holders)"
ls "/sys/class/block/$(basename "$DEV")/holders/" 2>/dev/null
echo
echo "=== dm / swap / md using it?"
swapon --show 2>/dev/null | head
lsblk -o NAME,MOUNTPOINTS,FSTYPE 2>/dev/null | head -20

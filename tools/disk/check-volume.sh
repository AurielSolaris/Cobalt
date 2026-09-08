#!/usr/bin/env bash
# Is the build volume mounted, healthy, and does the checkout look intact?
LABEL="${LABEL:-cobalt-build}"
MOUNT="${MOUNT:-/build}"
DEV="$(blkid -L "$LABEL" 2>/dev/null || true)"
echo "device : ${DEV:-NOT FOUND}"
if ! mountpoint -q "$MOUNT"; then
    echo "not mounted at $MOUNT; mounting"
    mount -L "$LABEL" "$MOUNT" 2>/dev/null || mount "$DEV" "$MOUNT" 2>/dev/null || echo "  mount failed"
fi
mountpoint -q "$MOUNT" && echo "mounted: yes" || { echo "mounted: NO"; exit 1; }
df -h "$MOUNT" | tail -1
echo
echo "objects on disk : $(find "$MOUNT/chromium/m140/src/out/Default/obj" -name '*.o' 2>/dev/null | wc -l)"
echo "out size        : $(du -sh "$MOUNT/chromium/m140/src/out/Default" 2>/dev/null | cut -f1)"
echo "checkout        : $(cd "$MOUNT/chromium/m140/src" 2>/dev/null && tr '\n' ' ' < chrome/VERSION)"
echo
echo "=== dmesg: any filesystem errors?"
dmesg 2>/dev/null | grep -iE 'ext4.*error|I/O error|EXT4-fs error' | tail -5 || echo "  (none)"

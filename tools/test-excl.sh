#!/usr/bin/env bash
# e2fsck's "in use" can come from an O_EXCL open failing, not from /proc/mounts.
DEV="$(blkid -L cobalt-build)"
mountpoint -q /build && umount /build
python3 - "$DEV" <<'PY'
import os, sys
dev = sys.argv[1]
for flags, name in ((os.O_RDONLY, "O_RDONLY"),
                    (os.O_RDONLY | os.O_EXCL, "O_RDONLY|O_EXCL")):
    try:
        fd = os.open(dev, flags); os.close(fd)
        print("  %-16s OK" % name)
    except OSError as e:
        print("  %-16s FAILED: %s" % (name, e))
PY
echo "mounted? $(grep -c "$DEV" /proc/mounts)"
mount -L cobalt-build /build 2>/dev/null && echo "remounted"

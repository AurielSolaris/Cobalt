#!/usr/bin/env bash
DEV="$(blkid -L cobalt-build)"
echo "device : $DEV"
echo "mounted: $(grep -c "$DEV" /proc/mounts)"
python3 -c "
import os,sys
try:
    fd=os.open('$DEV', os.O_RDONLY|os.O_EXCL); os.close(fd); print('exclusive open: OK')
except OSError as e: print('exclusive open: FAILED —', e)
"
echo
echo "=== e2fsck -fy $DEV"
e2fsck -fy "$DEV"
rc=$?
echo
case "$rc" in
  0) echo "RESULT: clean, no errors" ;;
  1) echo "RESULT: errors were CORRECTED" ;;
  2) echo "RESULT: errors corrected, remount advised" ;;
  4) echo "RESULT: errors REMAIN UNCORRECTED" ;;
  8) echo "RESULT: operational error, check did NOT run" ;;
  *) echo "RESULT: exit $rc" ;;
esac
exit "$rc"

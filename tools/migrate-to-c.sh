#!/usr/bin/env bash
#
# Copy the Chromium checkout off the failing external SSD onto the WSL distro's
# own filesystem, which is native ext4 backed by C:.
#
# No VHDX and no diskpart: creating one needs Windows elevation, and the distro
# root is already ext4 on the internal NVMe. Same performance, no admin.
#
# Read errors are the point of the logging here. The source drive has already
# failed to read one source file it wrote days earlier, so any file that cannot
# be read now is one we must know about rather than discover at link time.
set -uo pipefail

SRC="${SRC:-/build/chromium/m140}"
DEST="${DEST:-/opt/cobalt/chromium/m140}"
LOG="${LOG:-/opt/cobalt/migrate.log}"

mkdir -p "$DEST" "$(dirname "$LOG")"

echo "source : $SRC  ($(du -sh "$SRC" 2>/dev/null | cut -f1))"
echo "dest   : $DEST"
echo "free   : $(df -h / | awk 'NR==2{print $4}')"
echo

# out/ is deliberately excluded: it was corrupted by the I/O fault and is being
# rebuilt from scratch anyway. Copying 6 GB of known-bad objects would only
# reintroduce the link failure.
echo "=== copying (excluding out/, which is corrupt)"
# --partial keeps partial files so a retry resumes rather than restarting.
# --no-inc-recursive was removed: it scans every file before transferring
# anything, and on 1.1 million files against a failing disk that is time
# spent not copying.
rsync -a --partial --info=progress2 \
      --exclude 'src/out/' \
      "$SRC/" "$DEST/" 2> "$LOG"
rc=$?

echo
echo "=== rsync exit $rc"
if [ -s "$LOG" ]; then
    echo "=== READ ERRORS ($(wc -l < "$LOG") lines):"
    head -40 "$LOG"
else
    echo "no read errors"
fi

echo
echo "=== what landed"
du -sh "$DEST" 2>/dev/null | cut -f1
ls "$DEST/src/chrome/VERSION" >/dev/null 2>&1 && tr '\n' ' ' < "$DEST/src/chrome/VERSION" && echo
exit "$rc"

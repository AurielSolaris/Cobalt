#!/usr/bin/env bash
#
# Move depot_tools off the failing external drive.
#
# The Chromium checkout was migrated to the internal disk but depot_tools was
# not, so every ninja invocation still ran python and autoninja from the dying
# USB SSD. It failed with a bus error mid-build at 99.4% -- the toolchain has to
# live on the same healthy disk as the tree it builds.
set -uo pipefail

SRC=${SRC:-/opt/cobalt/depot_tools}
DEST=${DEST:-/opt/cobalt/depot_tools}

if [ -d "$DEST" ] && [ -x "$DEST/autoninja" ]; then
    echo "already present at $DEST"
else
    echo "copying $SRC -> $DEST"
    rsync -a --partial "$SRC/" "$DEST/" 2>/tmp/depot-copy.err
    rc=$?
    echo "rsync exit $rc"
    [ -s /tmp/depot-copy.err ] && { echo "read errors:"; head -10 /tmp/depot-copy.err; }
fi

echo
echo "=== verify"
for f in autoninja gn ninja python-bin/python3 gclient; do
    [ -e "$DEST/$f" ] && printf '  OK      %s\n' "$f" || printf '  MISSING %s\n' "$f"
done
printf '  size    %s\n' "$(du -sh "$DEST" 2>/dev/null | cut -f1)"

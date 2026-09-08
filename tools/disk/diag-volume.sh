#!/usr/bin/env bash
echo "=== mount state of /build"
grep ' /build ' /proc/mounts
echo
echo "=== is it read-only?"
touch /build/.rwtest 2>&1 && { echo "WRITABLE"; rm -f /build/.rwtest; } || echo "READ-ONLY"
echo
echo "=== kernel errors"
dmesg 2>/dev/null | grep -iE 'EXT4-fs|I/O error|buffer error|remount|sd[a-z].*error|critical target' | tail -25

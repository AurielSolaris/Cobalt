#!/usr/bin/env bash
echo "=== distro root filesystem (this is native ext4 on C:)"
df -h / | tail -1
echo
echo "=== who am I / home"
id -un; echo "HOME=$HOME"
echo
echo "=== is /build still readable?"
if mountpoint -q /build; then
  grep ' /build ' /proc/mounts
  ls /build/chromium/m140/src/chrome/VERSION >/dev/null 2>&1 && echo "  checkout reachable" || echo "  checkout NOT reachable"
else
  echo "  /build not mounted"
fi
echo
echo "=== size of the checkout we would copy"
du -sh /build/chromium/m140/src 2>/dev/null | cut -f1

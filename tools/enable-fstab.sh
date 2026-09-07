#!/usr/bin/env bash
if [ -f /etc/fstab.cobalt-bak ]; then
  cp /etc/fstab.cobalt-bak /etc/fstab && rm -f /etc/fstab.cobalt-bak
  echo "fstab restored:"; grep -n build /etc/fstab
else
  sed -i 's|^#COBALT-DISABLED ||' /etc/fstab
  echo "uncommented:"; grep -n build /etc/fstab
fi

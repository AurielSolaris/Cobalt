#!/usr/bin/env bash
# Take /build out of fstab so the next boot does not mount it, leaving the
# device free of the kernel's exclusive claim so e2fsck can open it.
cp /etc/fstab /etc/fstab.cobalt-bak
sed -i 's|^\([^#].*[[:space:]]/build[[:space:]]\)|#COBALT-DISABLED \1|' /etc/fstab
echo "fstab now:"; grep -n build /etc/fstab

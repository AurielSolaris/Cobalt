#!/usr/bin/env bash
#
# Prepare a Debian/Ubuntu WSL distro to fetch and build Chromium.
#
# Run as root:
#   wsl -d Ubuntu-24.04 -u root -- bash /mnt/c/.../tools/setup-build-host.sh
#
# Mounts the cobalt-build volume, installs the tools needed to fetch, and
# lays down depot_tools. It does NOT install Chromium's full build deps —
# that is done from inside the checkout by build/install-build-deps.sh, which
# knows what the pinned revision actually needs.

set -euo pipefail

echo "=== mounting the build volume"
mkdir -p /build
if ! mountpoint -q /build; then
    if blkid -L cobalt-build >/dev/null 2>&1; then
        mount -L cobalt-build /build
        echo "mounted $(blkid -L cobalt-build) at /build"
    else
        echo "ERROR: no volume labelled cobalt-build." >&2
        echo "Attach it from Windows first:" >&2
        echo '  wsl --mount "E:\\cobalt-build.vhdx" --vhd --bare' >&2
        exit 1
    fi
else
    echo "/build already mounted"
fi

grep -q cobalt-build /etc/fstab 2>/dev/null || \
    echo "LABEL=cobalt-build /build ext4 defaults,nofail 0 2" >> /etc/fstab

df -h /build

echo
echo "=== installing fetch prerequisites"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq \
    git python3 python3-pip curl file lsb-release sudo \
    ca-certificates xz-utils zip unzip >/dev/null
echo "git      $(git --version)"
echo "python3  $(python3 --version)"

echo
echo "=== depot_tools"
if [ ! -d /opt/cobalt/depot_tools ]; then
    git clone -q https://chromium.googlesource.com/chromium/tools/depot_tools.git \
        /opt/cobalt/depot_tools
    echo "cloned"
else
    git -C /opt/cobalt/depot_tools pull -q --ff-only || true
    echo "already present, updated"
fi

# Chromium's tooling is noisy about metrics; opt out once, explicitly.
mkdir -p /build/.config
cat > /opt/cobalt/depot_tools/.disable_auto_update <<'EOF'
Auto-update disabled so a long fetch cannot be changed underneath itself.
EOF

# Bootstrap depot_tools' vendored Python once, explicitly.
#
# DEPOT_TOOLS_UPDATE=0 keeps a multi-hour fetch from being changed underneath
# itself, but it also suppresses the one-time bootstrap that writes
# python3_bin_reldir.txt - without which `gn gen` fails with "need to
# initialize depot_tools". Doing it here separates the two concerns.
#
# ensure_bootstrap may fail partway on luci-auth, which is a CIPD tool we do
# not use; the file we need is written before that point.
if [ ! -f /opt/cobalt/depot_tools/python3_bin_reldir.txt ]; then
    echo "bootstrapping depot_tools"
    (cd /opt/cobalt/depot_tools && ./ensure_bootstrap >/dev/null 2>&1) || true
    [ -f /opt/cobalt/depot_tools/python3_bin_reldir.txt ]         && echo "  python3_bin_reldir.txt written"         || echo "  WARNING: bootstrap did not produce python3_bin_reldir.txt"
fi

echo
echo "=== ready"
echo "PATH needs: export PATH=/opt/cobalt/depot_tools:\$PATH"

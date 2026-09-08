#!/usr/bin/env bash
# Single source of truth for where the Chromium tree lives.
#
# It moved from an external USB SSD (/build, an ext4 VHDX) to the WSL distro's
# own filesystem after that drive failed three times under build load. Scripts
# source this rather than each carrying its own default, so the next move is one
# edit instead of twenty.
#
# Every script still honours an explicit SRC/OUT in the environment.

# Prefer the internal location; fall back to the old one if only that exists.
if [ -d /opt/cobalt/chromium/m140/src ]; then
    COBALT_ROOT="${COBALT_ROOT:-/opt/cobalt/chromium/m140}"
elif [ -d /build/chromium/m140/src ]; then
    COBALT_ROOT="${COBALT_ROOT:-/build/chromium/m140}"
else
    COBALT_ROOT="${COBALT_ROOT:-/opt/cobalt/chromium/m140}"
fi

export COBALT_ROOT
export COBALT_SRC="${SRC:-$COBALT_ROOT/src}"
export COBALT_OUT="${OUT:-$COBALT_SRC/out/Default}"
export COBALT_JOBS="${COBALT_JOBS:-6}"

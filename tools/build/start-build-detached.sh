#!/usr/bin/env bash
#
# Start the build fully detached from the invoking WSL client.
#
# Killing the `wsl` client process kills its whole process tree, so a build
# launched in the foreground of a background task dies whenever that task is
# reaped -- which the harness does under host memory pressure. setsid puts the
# build in its own session so nothing upstream can reach it.
#
# This was tried before and blamed for silent deaths, but the cause then was the
# WSL VM itself cycling and taking everything with it. vmIdleTimeout=-1 fixes
# that, so detaching is now the right answer rather than the broken one.

# Resolve sibling tools relative to this script, not an absolute path.
TOOLS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

set -uo pipefail

SRC="${SRC:-/opt/cobalt/chromium/m140/src}"
LOG="${LOG:-/opt/cobalt/build.log}"
JOBS="${COBALT_JOBS:-6}"

# Refuse to start a second one.
# Match the siso binary itself, not any command line mentioning it -- a monitor
# waiting on "siso ninja" otherwise looks like a running build and blocks this.
if pgrep -x siso >/dev/null 2>&1; then
    echo "a build is already running:"
    pgrep -ax siso | head -2 | cut -c1-120
    exit 1
fi

: > "$LOG"
setsid nohup env COBALT_JOBS="$JOBS" SRC="$SRC" \
    bash "$TOOLS/resume-build.sh" >> "$LOG" 2>&1 < /dev/null &

sleep 5
echo "log  : $LOG"
printf 'siso : %s\n' "$(pgrep -c -f 'siso ninja' 2>/dev/null || true)"
echo "started; the launching shell can exit safely"

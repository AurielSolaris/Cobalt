#!/usr/bin/env bash
#
# Fetch a pinned Chromium for Android into /build.
#
# Usage:  tools/fetch-chromium.sh [tag] [dir]
# Default: 140.0.7339.264 — the last patch of M140, so it carries every security
# fix on that line. See docs/decisions/0003-staged-chromium-rebase.md for why
# M140 rather than current stable.
#
# --no-history keeps this to a workable size: we need the tree, not four years
# of Chromium's commit log. Hooks are deliberately deferred; they pull toolchains
# and are run after build deps are installed.

set -euo pipefail

TAG="${1:-140.0.7339.264}"
DIR="${2:-/build/chromium/m140}"

export PATH="/build/depot_tools:$PATH"
export DEPOT_TOOLS_UPDATE=0
export DEPOT_TOOLS_METRICS=0

echo "=== fetching Chromium $TAG"
echo "    into $DIR"
echo "    started $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo

mkdir -p "$DIR"
cd "$DIR"

if [ ! -f .gclient ]; then
    cat > .gclient <<EOF
solutions = [
  {
    "name": "src",
    "url": "https://chromium.googlesource.com/chromium/src.git",
    "managed": False,
    "custom_deps": {},
    "custom_vars": {},
  },
]
target_os = ["android"]
EOF
    echo "wrote .gclient (target_os=android)"
fi

# gclient with managed:False does NOT clone src itself — it expects the checkout
# to exist and only resolves DEPS against it. Cloning it here keeps the pin
# explicit rather than depending on `fetch` to guess a revision.
if [ ! -d src/.git ]; then
    echo "=== cloning src at $TAG"
    rm -rf src
    git clone --depth 1 --branch "$TAG" --single-branch \
        https://chromium.googlesource.com/chromium/src.git src
else
    echo "=== src already present"
    git -C src --no-pager log -1 --format='    %H %d' 2>/dev/null | head -2
fi

# No --revision below, deliberately.
#
# src is already checked out at the exact tag. Passing --revision src@TAG makes
# gclient re-resolve it, and against a shallow single-branch clone that becomes
# `git fetch origin` with no depth limit — Chromium's entire history, tens of GB
# and hours, to arrive at the commit we already have. With managed:False gclient
# leaves src alone and resolves only the DEPS it declares, which is the job.
#
# --reset is also omitted: it would discard the shallow clone's state.
#
# -j 4, not 12.
#
# chromium.googlesource.com rate-limits: twelve parallel fetches earned HTTP 429
# on libaddressinput and libaom, and gclient quarantined the half-fetched trees
# into _bad_scm/. The limit is on requests, not bandwidth, so fewer concurrent
# clones is both kinder and faster in wall-clock terms than retrying failures.
#
# gclient sync is resumable: re-running picks up whatever is missing.
echo "=== syncing DEPS"
gclient sync \
    --no-history \
    --nohooks \
    --shallow \
    --delete_unversioned_trees \
    -j 4

echo
echo "=== sync finished $(date -u +%Y-%m-%dT%H:%M:%SZ)"
du -sh "$DIR"
git -C "$DIR/src" --no-pager log -1 --format='%H %d' 2>/dev/null | head -2
tr '\n' ' ' < "$DIR/src/chrome/VERSION" 2>/dev/null; echo
echo
echo "next: tools/build-chromium.sh deps   (as root)"
echo "      tools/build-chromium.sh hooks"
echo "      tools/build-chromium.sh gen"
echo "      tools/build-chromium.sh build"

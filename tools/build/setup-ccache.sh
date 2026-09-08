#!/usr/bin/env bash
#
# Configure ccache for Chromium builds.
#
# This is the local version of what Google's RBE gives Chromium engineers: a
# shared object cache so a rebuild recompiles only what actually changed. It
# matters most for exactly the work ahead of us -- Stage 5 lands ~160 patches
# one batch at a time, and each batch would otherwise re-pay for files it never
# touched. It also survives switching branches, which a plain ninja rebuild
# does not.
#
# Object files are not portable: a .o is tied to the clang revision, the full
# flag set, the sysroot and the build paths. ccache hashes all of that, so a
# mismatch is a miss rather than a silently broken binary. That is why the cache
# has to be local and why nobody can just ship prebuilt objects.
set -uo pipefail

SIZE="${CCACHE_SIZE:-40G}"

ccache --set-config=max_size="$SIZE"
# Chromium passes -ffile-compilation-dir=. and absolute -I paths; hashing the
# preprocessed source rather than the command line's paths keeps hit rates up
# across checkouts.
ccache --set-config=hash_dir=false
ccache --set-config=compiler_check=content
# Chromium's build sets -Werror and uses depfiles; both are fine with ccache,
# but sloppiness has to be explicit for the time macros it already neutralises
# with -D__DATE__= and friends.
ccache --set-config=sloppiness=time_macros,include_file_mtime,include_file_ctime

echo "=== config"
ccache --show-config 2>/dev/null | grep -E 'max_size|hash_dir|compiler_check|sloppiness|cache_dir'
echo
echo "=== current stats"
ccache --show-stats 2>/dev/null | head -8
echo
echo "Add to args.gn for the next gen:  cc_wrapper = \"ccache\""

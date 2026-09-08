#!/usr/bin/env bash
# depot_tools ships as scripts; the python and ninja binaries it needs are
# fetched on first use. DEPOT_TOOLS_UPDATE=0 suppresses that bootstrap, which
# once produced "python3_bin_reldir.txt not found" from gn gen. So bootstrap
# explicitly, with updates left on for this one step.
DT=${DT:-/opt/cobalt/depot_tools}
export PATH="$DT:$PATH"
unset DEPOT_TOOLS_UPDATE
export DEPOT_TOOLS_METRICS=0

echo "=== ensure_bootstrap"
# It fails on luci-auth in a headless environment; that part is not needed here.
"$DT/ensure_bootstrap" 2>&1 | tail -5

echo
echo "=== resulting binaries"
for f in python-bin/python3 ninja gn autoninja; do
  if [ -e "$DT/$f" ]; then printf '  OK      %s\n' "$f"; else printf '  MISSING %s\n' "$f"; fi
done
echo
echo "=== versions"
"$DT/python-bin/python3" --version 2>&1 | head -1
"$DT/ninja" --version 2>&1 | head -1

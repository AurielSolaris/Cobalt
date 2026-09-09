#!/usr/bin/env bash
# Confirm the generated config is what we intended, before spending a build on it.
S=${SRC:-/opt/cobalt/chromium/m140/src}
export PATH=/opt/cobalt/depot_tools:$PATH
cd "$S" || exit 1
echo "=== extensions reachable from the APK?"
for t in //extensions/browser //extensions/common //chrome/browser/extensions //chrome/renderer/extensions; do
  printf '  %-34s ' "$t"
  gn path out/Default //chrome/android:chrome_public_apk "$t" >/tmp/p 2>&1 \
    && { grep -q "No non-data paths" /tmp/p && echo "not reachable" || echo "REACHABLE"; } \
    || head -1 /tmp/p
done
echo
echo "=== resolved args that matter"
for a in is_desktop_android enable_extensions enable_extensions_core enable_vr enable_arcore chrome_public_manifest_package; do
  printf '  %-34s %s\n' "$a" "$(gn args out/Default --list="$a" --short 2>/dev/null | head -1)"
done
echo
echo "=== branding landed?"
grep -o 'name="app_name"[^<]*<[^<]*' chrome/android/java/res_chromium_base/values/channel_constants.xml 2>/dev/null | head -1 | sed 's/^/  /'
echo
echo "=== ccache wired in?"
grep -c cc_wrapper out/Default/args.gn | sed 's/^/  cc_wrapper lines: /'

#!/usr/bin/env bash
# What does this Chromium tree still contain for Manifest V2?
#
# Kiwi is M105, where MV2 was the default and fully supported. Upstream has been
# removing MV2 progressively since. Whatever is already gone here is code we must
# carry ourselves, forever, and the burden grows with every rebase.
SRC="${SRC:-/build/chromium/m140/src}"
cd "$SRC" || exit 1

echo "=== tree: $(tr '\n' ' ' < chrome/VERSION)"
echo

echo "=== MV2 deprecation machinery (presence = upstream is phasing it out)"
for f in \
  chrome/browser/extensions/manifest_v2_experiment_manager.h \
  chrome/browser/extensions/mv2_experiment_stage.h \
  extensions/common/mojom/manifest.mojom ; do
  [ -e "$f" ] && echo "  PRESENT $f" || echo "  absent  $f"
done
echo

echo "=== features that gate MV2"
grep -rn "ExtensionManifestV2\|kExtensionManifestV2" \
  extensions/common/extension_features.cc 2>/dev/null | head -12
echo

echo "=== does the manifest parser still accept version 2?"
grep -rn "manifest_version" extensions/common/manifest.cc 2>/dev/null | head -8
echo

echo "=== background pages (MV2) vs service workers (MV3)"
printf '  background_page refs   : '
grep -rl "background_page\|BackgroundPage" extensions/common extensions/browser 2>/dev/null | wc -l
printf '  service_worker refs    : '
grep -rl "background.service_worker\|ServiceWorkerBased" extensions/common extensions/browser 2>/dev/null | wc -l
echo

echo "=== webRequestBlocking (the MV2 API that matters for content blocking)"
grep -rn "webRequestBlocking" extensions/common/api/_permission_features.json 2>/dev/null | head -5
grep -rn "webRequestBlocking" chrome/common/extensions/api/_permission_features.json 2>/dev/null | head -5

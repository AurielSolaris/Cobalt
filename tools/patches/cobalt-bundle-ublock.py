#!/usr/bin/env python3
"""Bundle uBlock Origin into the APK as a preinstalled, disableable extension.

Three separate mechanisms have to line up, and none of them is a Kiwi patch --
this is all upstream machinery configured for Android:

1. **Delivery.** The signed CRX rides in the APK as an uncompressed asset, and
   is staged out to a real filesystem path on first run. It has to be a real
   path: `ExternalPrefLoader` uses `base::FileEnumerator` and
   `JSONFileValueDeserializer`, neither of which can see inside an APK.

2. **Installation.** Chromium already registers an `ExternalPrefLoader` over
   `chrome::DIR_EXTERNAL_EXTENSIONS` on every non-Windows platform, Android
   included -- see the `#else` branch in
   `ExternalProviderImpl::CreateExternalProviders`. What it does *not* have is a
   usable directory: on Android `base::DIR_MODULE` resolves to the native
   library directory, which is read-only, so the path's `create_dir` would fail
   and the loader would find nothing. Redirecting that one path to app data is
   the whole of the C++ install change.

3. **Uninstall protection.** `ExtensionSettings` with
   `installation_mode: normal_installed` maps to
   `ManagedInstallationMode::kRecommended`, and
   `StandardManagementPolicyProvider::MustRemainInstalled` returns true for it
   while `MustRemainEnabled` does not -- exactly the "cannot remove, can
   disable" semantic decision 0006 asked for. That pref is loaded with
   `force_managed=true`, so it cannot be set as a user pref; it has to arrive
   through a policy provider. Cobalt adds one, appended last so a real
   administrator still outranks it.

Note the interaction between 2 and 3: `normal_installed` requires a valid
`update_url`, and the recommended-mode policy loader turns that into a second
external provider offering the same extension over the network. It loses --
`kExternalPref` (rank 2) outranks `kExternalPrefDownload` (rank 1), so once the
local CRX is in, `OnExternalExtensionUpdateUrlFound` reports ALREADY_INSTALLED
and returns false. The update URL is a Cobalt-owned placeholder that does not
resolve today; it exists to satisfy the parser and to be the real self-hosted
endpoint later, when the staleness problem decision 0006 flags comes due.

Idempotent. Every edit asserts, because an unasserted str.replace reports
success while changing nothing.
"""
import shutil
import sys
from pathlib import Path

SRC = Path(sys.argv[1] if len(sys.argv) > 1 else "/opt/cobalt/chromium/m140/src")
CRX = Path(sys.argv[2] if len(sys.argv) > 2 else "/opt/cobalt/vendor/ublock/ublock.crx")

# Derived from the signing key at /opt/cobalt/vendor/keys/ublock.pem. Losing
# that key changes the id, which would orphan every installed copy.
UBLOCK_ID = "fimbmjialkbnbbedhcpdodbhicmjfgli"
UBLOCK_VERSION = "1.74.0"
UPDATE_URL = "https://updates.cobalt.auriel/crx/update.xml"

ASSET_DIR = SRC / "chrome/browser/cobalt/extensions"
COBALT_DIR = SRC / "chrome/browser/cobalt"


# ---------------------------------------------------------------- helpers


class Failed(Exception):
    pass


def edit(path: Path, old: str, new: str, *, done_marker: str) -> str:
    """Replace `old` with `new`, or report the edit already applied."""
    text = path.read_text(encoding="utf-8")
    if done_marker in text:
        return "already applied"
    if old not in text:
        raise Failed(f"anchor not found in {path}")
    if text.count(old) != 1:
        raise Failed(f"anchor is ambiguous in {path} ({text.count(old)} matches)")
    path.write_text(text.replace(old, new), encoding="utf-8")
    return "patched"


def write(path: Path, body: str) -> str:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() and path.read_text(encoding="utf-8") == body:
        return "unchanged"
    path.write_text(body, encoding="utf-8")
    return "written"


# ---------------------------------------------------------------- 1. asset


def stage_crx() -> str:
    if not CRX.exists():
        raise Failed(f"missing CRX: {CRX} -- run tools/assets/pack-crx.py first")
    magic = CRX.read_bytes()[:4]
    if magic != b"Cr24":
        raise Failed(f"{CRX} is not a CRX (magic {magic!r})")

    ASSET_DIR.mkdir(parents=True, exist_ok=True)
    dest = ASSET_DIR / "ublock.crx"
    if dest.exists() and dest.read_bytes() == CRX.read_bytes():
        return "unchanged"
    shutil.copy2(CRX, dest)
    return f"copied ({dest.stat().st_size} bytes)"


ASSET_BUILD_GN = """\
# Copyright 2026 The Cobalt Authors
# Use of this source code is governed by a GPL-3.0 license that can be found in
# the LICENSE file.

import("//build/config/android/rules.gni")

# The bundled extension CRXs, carried in the APK and staged out to
# chrome::DIR_EXTERNAL_EXTENSIONS on first run by
# //chrome/browser/cobalt:bundled_extensions.
#
# disable_compression is not an optimisation here, it is a requirement:
# base::android::OpenApkAsset can only hand back a file descriptor for an asset
# stored uncompressed. A CRX is a signed zip, so storing it uncompressed costs
# essentially nothing anyway.
android_assets("bundled_extension_assets") {
  sources = [ "ublock.crx" ]
  disable_compression = true
}
"""


# ------------------------------------------------- 2. staging (C++, Android)

STAGER_H = """\
// Copyright 2026 The Cobalt Authors
// Use of this source code is governed by a GPL-3.0 license that can be found in
// the LICENSE file.

#ifndef CHROME_BROWSER_COBALT_BUNDLED_EXTENSIONS_H_
#define CHROME_BROWSER_COBALT_BUNDLED_EXTENSIONS_H_

namespace cobalt {

// Copies the extension CRXs bundled in the APK into
// chrome::DIR_EXTERNAL_EXTENSIONS and writes the external_extensions.json that
// describes them, so Chromium's ordinary external-extension provider installs
// them on first run.
//
// Blocking, and deliberately synchronous: it must complete before the profile
// exists, because ExtensionService creates the external providers during
// profile construction and a provider that runs before the files are staged
// finds an empty directory and installs nothing until the next launch. Call it
// from PreCreateThreads.
//
// Cheap after the first run: it stats a version marker and returns.
void StageBundledExtensions();

}  // namespace cobalt

#endif  // CHROME_BROWSER_COBALT_BUNDLED_EXTENSIONS_H_
"""

STAGER_CC = """\
// Copyright 2026 The Cobalt Authors
// Use of this source code is governed by a GPL-3.0 license that can be found in
// the LICENSE file.

#include "chrome/browser/cobalt/bundled_extensions.h"

#include <array>
#include <string>
#include <string_view>

#include "base/android/apk_assets.h"
#include "base/files/file.h"
#include "base/files/file_path.h"
#include "base/files/file_util.h"
#include "base/files/memory_mapped_file.h"
#include "base/json/json_writer.h"
#include "base/logging.h"
#include "base/path_service.h"
#include "base/strings/strcat.h"
#include "base/threading/scoped_blocking_call.h"
#include "base/values.h"
#include "chrome/common/chrome_paths.h"

namespace cobalt {
namespace {

struct BundledExtension {
  // Path inside the APK, as produced by the android_assets target in
  // //chrome/browser/cobalt/extensions.
  std::string_view asset_path;
  // File name to stage it under, referenced from external_extensions.json.
  std::string_view crx_name;
  std::string_view extension_id;
  std::string_view version;
};

constexpr auto kBundledExtensions = std::to_array<BundledExtension>({
    {"assets/ublock.crx", "ublock.crx", "%(ublock_id)s", "%(ublock_version)s"},
});

// Bumped when the staged set changes, so an upgrade restages rather than
// leaving an older CRX in place next to a newer manifest.
constexpr char kStampContents[] = "%(ublock_version)s";

bool CopyAssetToFile(std::string_view asset_path, const base::FilePath& dest) {
  base::MemoryMappedFile::Region region;
  int fd = base::android::OpenApkAsset(std::string(asset_path), &region);
  if (fd < 0) {
    base::android::DumpLastOpenApkAssetFailure();
    LOG(ERROR) << "Cobalt: bundled extension asset not found or compressed: "
               << asset_path;
    return false;
  }

  base::MemoryMappedFile mapped;
  if (!mapped.Initialize(base::File(fd), region)) {
    LOG(ERROR) << "Cobalt: could not map bundled extension asset: "
               << asset_path;
    return false;
  }

  // WriteFile is all-or-nothing, so a torn write cannot leave a truncated CRX
  // behind for the installer to reject.
  if (!base::WriteFile(dest, mapped.bytes())) {
    LOG(ERROR) << "Cobalt: could not write staged extension: " << dest;
    return false;
  }
  return true;
}

}  // namespace

void StageBundledExtensions() {
  base::ScopedBlockingCall scoped_blocking_call(FROM_HERE,
                                                base::BlockingType::MAY_BLOCK);

  base::FilePath dir;
  // The path provider creates the directory; DIR_EXTERNAL_EXTENSIONS is
  // declared with create_dir on this platform.
  if (!base::PathService::Get(chrome::DIR_EXTERNAL_EXTENSIONS, &dir)) {
    LOG(ERROR) << "Cobalt: no external extensions directory";
    return;
  }

  const base::FilePath stamp = dir.AppendASCII(".cobalt-staged");
  std::string existing;
  if (base::ReadFileToString(stamp, &existing) && existing == kStampContents) {
    return;
  }

  base::Value::Dict prefs;
  for (const BundledExtension& ext : kBundledExtensions) {
    const base::FilePath dest = dir.AppendASCII(ext.crx_name);
    if (!CopyAssetToFile(ext.asset_path, dest)) {
      // Staging is all-or-nothing: a half-written set would install some
      // extensions and silently drop others, and the stamp below would then
      // make that permanent.
      return;
    }
    base::Value::Dict entry;
    // Relative, resolved by ExternalProviderImpl against the provider's base
    // path -- which is this same directory.
    entry.Set("external_crx", std::string(ext.crx_name));
    entry.Set("external_version", std::string(ext.version));
    prefs.Set(std::string(ext.extension_id), std::move(entry));
  }

  std::optional<std::string> json = base::WriteJson(prefs);
  if (!json.has_value()) {
    LOG(ERROR) << "Cobalt: could not serialise external_extensions.json";
    return;
  }
  if (!base::WriteFile(dir.AppendASCII("external_extensions.json"), *json)) {
    LOG(ERROR) << "Cobalt: could not write external_extensions.json";
    return;
  }

  // Written last: it is the record that everything above succeeded.
  base::WriteFile(stamp, kStampContents);
}

}  // namespace cobalt
""" % {"ublock_id": UBLOCK_ID, "ublock_version": UBLOCK_VERSION}


# ------------------------------------------------------- 3. policy provider

POLICY_H = """\
// Copyright 2026 The Cobalt Authors
// Use of this source code is governed by a GPL-3.0 license that can be found in
// the LICENSE file.

#ifndef CHROME_BROWSER_POLICY_COBALT_BUNDLED_EXTENSION_POLICY_PROVIDER_H_
#define CHROME_BROWSER_POLICY_COBALT_BUNDLED_EXTENSION_POLICY_PROVIDER_H_

#include "components/policy/core/common/configuration_policy_provider.h"

namespace policy {

// Supplies the ExtensionSettings policy that keeps Cobalt's bundled extensions
// installable-but-not-removable.
//
// This is a policy provider rather than a pref default because
// ExtensionManagement loads extensions.management with force_managed=true: a
// value that is not marked as coming from a managed source is ignored outright.
//
// It is appended last in ChromeBrowserPolicyConnector::CreatePolicyProviders,
// which is the lowest priority, so an actual administrator's ExtensionSettings
// replaces it rather than merging with it.
class CobaltBundledExtensionPolicyProvider : public ConfigurationPolicyProvider {
 public:
  CobaltBundledExtensionPolicyProvider();
  CobaltBundledExtensionPolicyProvider(
      const CobaltBundledExtensionPolicyProvider&) = delete;
  CobaltBundledExtensionPolicyProvider& operator=(
      const CobaltBundledExtensionPolicyProvider&) = delete;
  ~CobaltBundledExtensionPolicyProvider() override;

  // ConfigurationPolicyProvider:
  void RefreshPolicies(PolicyFetchReason reason) override;
};

}  // namespace policy

#endif  // CHROME_BROWSER_POLICY_COBALT_BUNDLED_EXTENSION_POLICY_PROVIDER_H_
"""

POLICY_CC = """\
// Copyright 2026 The Cobalt Authors
// Use of this source code is governed by a GPL-3.0 license that can be found in
// the LICENSE file.

#include "chrome/browser/policy/cobalt_bundled_extension_policy_provider.h"

#include <string>
#include <utility>

#include "base/values.h"
#include "components/policy/core/common/policy_bundle.h"
#include "components/policy/core/common/policy_map.h"
#include "components/policy/core/common/policy_namespace.h"
#include "components/policy/core/common/policy_types.h"
#include "components/policy/policy_constants.h"

namespace policy {
namespace {

// Keep in sync with the staging table in
// //chrome/browser/cobalt/bundled_extensions.cc. An id here that is not staged
// there costs nothing; an id staged there but missing here would be an
// ordinary user-removable extension, which is the failure decision 0006 is
// about.
constexpr char kUBlockOriginId[] = "%(ublock_id)s";

// normal_installed refuses to parse without a valid update URL. Nothing fetches
// this today -- the local CRX installs at kExternalPref, which outranks the
// kExternalPrefDownload entry this URL produces, so the update-url provider
// reports ALREADY_INSTALLED and stops. It is here to be the real self-hosted
// update endpoint once bundled versions need refreshing without a full release.
constexpr char kCobaltUpdateUrl[] = "%(update_url)s";

base::Value BuildExtensionSettings() {
  base::Value::Dict ublock;
  // kRecommended: MustRemainInstalled is true, MustRemainEnabled is false.
  // force_installed would also block disabling, which decision 0006 rejected.
  ublock.Set("installation_mode", "normal_installed");
  ublock.Set("update_url", kCobaltUpdateUrl);

  base::Value::Dict settings;
  settings.Set(kUBlockOriginId, std::move(ublock));
  return base::Value(std::move(settings));
}

}  // namespace

CobaltBundledExtensionPolicyProvider::CobaltBundledExtensionPolicyProvider() {
  PolicyBundle bundle;
  bundle.Get(PolicyNamespace(POLICY_DOMAIN_CHROME, std::string()))
      .Set(key::kExtensionSettings, POLICY_LEVEL_MANDATORY, POLICY_SCOPE_USER,
           POLICY_SOURCE_ENTERPRISE_DEFAULT, BuildExtensionSettings(), nullptr);
  UpdatePolicy(std::move(bundle));
}

CobaltBundledExtensionPolicyProvider::~CobaltBundledExtensionPolicyProvider() =
    default;

void CobaltBundledExtensionPolicyProvider::RefreshPolicies(
    PolicyFetchReason reason) {
  // The value is compiled in, so there is nothing to re-read and nothing to
  // wait for. The bundle was published from the constructor.
}

}  // namespace policy
""" % {"ublock_id": UBLOCK_ID, "update_url": UPDATE_URL}


# ---------------------------------------------------------------- edits


def patch_chrome_paths() -> str:
    path = SRC / "chrome/common/chrome_paths.cc"
    old = """    case chrome::DIR_EXTERNAL_EXTENSIONS:
#if BUILDFLAG(IS_MAC)"""
    new = """    case chrome::DIR_EXTERNAL_EXTENSIONS:
#if BUILDFLAG(IS_ANDROID)
      // base::DIR_MODULE is the native library directory on Android: read-only,
      // so create_dir below would fail and the external extension provider --
      // which upstream already registers here on every non-Windows platform --
      // would find nothing. Cobalt stages its bundled CRXs into app data
      // instead. See //chrome/browser/cobalt/bundled_extensions.h.
      if (!base::PathService::Get(base::DIR_ANDROID_APP_DATA, &cur)) {
        return false;
      }
      cur = cur.Append(FILE_PATH_LITERAL("external_extensions"));
      create_dir = true;
#elif BUILDFLAG(IS_MAC)"""
    return edit(path, old, new, done_marker="external_extensions")


def patch_browser_main_android() -> str:
    path = SRC / "chrome/browser/chrome_browser_main_android.cc"
    text = path.read_text(encoding="utf-8")
    if "bundled_extensions.h" in text:
        return "already applied"

    inc_old = '#include "chrome/browser/browser_process.h"\n'
    inc_new = (
        '#include "chrome/browser/browser_process.h"\n'
        '#include "chrome/browser/cobalt/bundled_extensions.h"\n'
    )
    call_old = """  int result_code = ChromeBrowserMainParts::PreCreateThreads();
"""
    call_new = """  int result_code = ChromeBrowserMainParts::PreCreateThreads();

#if BUILDFLAG(ENABLE_EXTENSIONS_CORE)
  // Must happen before any profile exists: ExtensionService builds the external
  // extension providers during profile construction, and a provider that runs
  // first sees an empty directory.
  cobalt::StageBundledExtensions();
#endif
"""
    if inc_old not in text or call_old not in text:
        raise Failed(f"anchor not found in {path}")
    text = text.replace(inc_old, inc_new, 1).replace(call_old, call_new, 1)

    # The buildflag header is not otherwise included here.
    flag_old = '#include "components/crash/content/browser/child_exit_observer_android.h"\n'
    flag_new = (
        '#include "components/crash/content/browser/child_exit_observer_android.h"\n'
        '#include "extensions/buildflags/buildflags.h"\n'
    )
    if flag_old not in text:
        raise Failed(f"buildflags anchor not found in {path}")
    text = text.replace(flag_old, flag_new, 1)

    path.write_text(text, encoding="utf-8")
    return "patched"


def patch_policy_connector() -> str:
    path = SRC / "chrome/browser/policy/chrome_browser_policy_connector.cc"
    text = path.read_text(encoding="utf-8")
    if "cobalt_bundled_extension_policy_provider.h" in text:
        return "already applied"

    inc_old = '#include "chrome/browser/policy/chrome_browser_policy_connector.h"\n'
    inc_new = (
        '#include "chrome/browser/policy/chrome_browser_policy_connector.h"\n'
        '\n'
        '#include "chrome/browser/policy/cobalt_bundled_extension_policy_provider.h"\n'
        '#include "extensions/buildflags/buildflags.h"\n'
    )
    add_old = """  local_test_provider_ =
      LocalTestPolicyProvider::CreateIfAllowed(chrome::GetChannel());"""
    add_new = """#if BUILDFLAG(ENABLE_EXTENSIONS_CORE)
  // Appended last, so it is the lowest-priority provider: an administrator's
  // ExtensionSettings replaces Cobalt's rather than being merged with it.
  providers.push_back(
      std::make_unique<CobaltBundledExtensionPolicyProvider>());
#endif

  local_test_provider_ =
      LocalTestPolicyProvider::CreateIfAllowed(chrome::GetChannel());"""
    if inc_old not in text or add_old not in text:
        raise Failed(f"anchor not found in {path}")
    text = text.replace(inc_old, inc_new, 1).replace(add_old, add_new, 1)
    path.write_text(text, encoding="utf-8")
    return "patched"


def patch_browser_build_gn() -> str:
    path = SRC / "chrome/browser/BUILD.gn"
    old = """    "policy/chrome_browser_policy_connector.cc",
    "policy/chrome_browser_policy_connector.h",
"""
    new = """    "cobalt/bundled_extensions.h",
    "policy/chrome_browser_policy_connector.cc",
    "policy/chrome_browser_policy_connector.h",
    "policy/cobalt_bundled_extension_policy_provider.cc",
    "policy/cobalt_bundled_extension_policy_provider.h",
"""
    return edit(path, old, new,
                done_marker="policy/cobalt_bundled_extension_policy_provider.cc")


def patch_browser_build_gn_android() -> str:
    """The staging implementation is Android-only; add it to the Android block."""
    path = SRC / "chrome/browser/BUILD.gn"
    text = path.read_text(encoding="utf-8")
    if "cobalt/bundled_extensions.cc" in text:
        return "already applied"

    old = '    "chrome_browser_main_android.cc",\n'
    if old not in text:
        raise Failed(f"Android anchor not found in {path}")
    if text.count(old) != 1:
        raise Failed(f"Android anchor is ambiguous in {path}")
    new = '    "chrome_browser_main_android.cc",\n    "cobalt/bundled_extensions.cc",\n'
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    return "patched"


def patch_android_assets() -> str:
    path = SRC / "chrome/android/BUILD.gn"
    old = """  java_group("chrome_public_non_pak_assets") {
    deps = [
      "//chrome/android/webapk/libs/runtime_library:runtime_library_assets","""
    new = """  java_group("chrome_public_non_pak_assets") {
    deps = [
      "//chrome/browser/cobalt/extensions:bundled_extension_assets",
      "//chrome/android/webapk/libs/runtime_library:runtime_library_assets","""
    return edit(path, old, new,
                done_marker="bundled_extension_assets")


# ---------------------------------------------------------------- driver

STEPS = [
    ("stage ublock.crx into the tree", stage_crx),
    ("chrome/browser/cobalt/extensions/BUILD.gn",
     lambda: write(ASSET_DIR / "BUILD.gn", ASSET_BUILD_GN)),
    ("chrome/browser/cobalt/bundled_extensions.h",
     lambda: write(COBALT_DIR / "bundled_extensions.h", STAGER_H)),
    ("chrome/browser/cobalt/bundled_extensions.cc",
     lambda: write(COBALT_DIR / "bundled_extensions.cc", STAGER_CC)),
    ("chrome/browser/policy/cobalt_bundled_extension_policy_provider.h",
     lambda: write(SRC / "chrome/browser/policy/cobalt_bundled_extension_policy_provider.h",
                   POLICY_H)),
    ("chrome/browser/policy/cobalt_bundled_extension_policy_provider.cc",
     lambda: write(SRC / "chrome/browser/policy/cobalt_bundled_extension_policy_provider.cc",
                   POLICY_CC)),
    ("chrome/common/chrome_paths.cc", patch_chrome_paths),
    ("chrome/browser/chrome_browser_main_android.cc", patch_browser_main_android),
    ("chrome/browser/policy/chrome_browser_policy_connector.cc", patch_policy_connector),
    ("chrome/browser/BUILD.gn (shared sources)", patch_browser_build_gn),
    ("chrome/browser/BUILD.gn (android sources)", patch_browser_build_gn_android),
    ("chrome/android/BUILD.gn (assets)", patch_android_assets),
]


def main() -> int:
    if not (SRC / "chrome/browser/BUILD.gn").exists():
        print(f"not a chromium checkout: {SRC}", file=sys.stderr)
        return 1

    failures = 0
    for name, fn in STEPS:
        try:
            result = fn()
        except Failed as exc:
            print(f"  {name:<62} FAILED  {exc}")
            failures += 1
        else:
            print(f"  {name:<62} {result}")

    if failures:
        print(f"\n{failures} step(s) failed; tree is partially patched",
              file=sys.stderr)
        return 1
    print(f"\nuBlock Origin {UBLOCK_VERSION} ({UBLOCK_ID}) bundled.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

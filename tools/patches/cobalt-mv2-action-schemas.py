#!/usr/bin/env python3
"""Ship the browserAction and pageAction schemas on Android.

Found by running the bundled uBlock Origin on device. It installed, its renderer
started, and then died immediately:

    FATAL:extensions/renderer/native_extension_bindings_system.cc:213
    Unknown API browserAction
      "extension-1" = "fimbmjialkbnbbedhcpdodbhicmjfgli"

The feature is available -- `browserAction` in _api_features.json is
unconditional and depends only on `manifest:browser_action`, which uBO declares
and which parsed fine. What is missing is the *schema*:
`ExtensionAPI::GetSharedInstance()->GetSchema("browserAction")` returns null, and
that call site is a hard LOG_IF(FATAL), so the renderer aborts rather than
degrading.

The cause is a gating asymmetry in chrome/common/extensions/api/api_sources.gni.
`action.json` -- MV3's chrome.action -- sits in the unconditional
uncompiled_sources_ list. Its MV2 equivalents, browser_action.json and
page_action.json, sit inside `if (enable_extensions)`. Route B builds with
enable_extensions_core but not enable_extensions, so MV3 gets its schema and MV2
does not.

Upstream never hits this because every platform that enables extensions at all
today also sets enable_extensions. Cobalt does not: decision 0005 commits to MV2
as a first-class manifest version, and browser_action is *the* MV2 toolbar
surface -- uBlock Origin cannot run a single line without it.

These are `uncompiled` sources: schema-only, no generated C++, no new API
implementation. The browser side is already present unconditionally
(extension_action_dispatcher.cc is in the main //chrome/browser/extensions
source_set). So this moves two JSON files into a resource bundle and nothing
else.

Idempotent; the edit asserts.
"""
import sys
from pathlib import Path

SRC = Path(sys.argv[1] if len(sys.argv) > 1 else "/opt/cobalt/chromium/m140/src")
TARGET = SRC / "chrome/common/extensions/api/api_sources.gni"

# The unconditional list, which already carries MV3's action.json.
ANCHOR = '''uncompiled_sources_ = [
  "action.json",
  "browsing_data.json",
  "extension.json",
  "top_sites.json",
]
'''

REPLACEMENT = '''uncompiled_sources_ = [
  "action.json",

  # MV2's equivalents of action.json. Upstream keeps these inside the
  # `if (enable_extensions)` block below, which means a build with
  # enable_extensions_core but not enable_extensions ships the MV3 schema and
  # not the MV2 one. The renderer's GetAPISchema() is a LOG_IF(FATAL), so an
  # MV2 extension that touches chrome.browserAction kills its own renderer
  # rather than failing gracefully -- which is exactly what bundled uBlock
  # Origin did on the first Android run.
  #
  # Cobalt supports MV2 as a first-class manifest version (decision 0005), so
  # these belong with action.json. Schema-only: no generated C++, and the
  # browser-side dispatcher is already compiled unconditionally.
  "browser_action.json",
  "page_action.json",

  "browsing_data.json",
  "extension.json",
  "top_sites.json",
]
'''

# The same two names must leave the enable_extensions block, or they are listed
# twice and generated_json_strings emits duplicate bundle entries.
OLD_GATED = '''  uncompiled_sources_ += [
    "browser_action.json",
    "idltest.idl",
    "page_action.json",
  ]
'''

NEW_GATED = '''  # browser_action.json and page_action.json moved to the unconditional list
  # above; see the comment there.
  uncompiled_sources_ += [ "idltest.idl" ]
'''


def main() -> int:
    if not TARGET.exists():
        print(f"missing: {TARGET}", file=sys.stderr)
        return 1

    text = TARGET.read_text(encoding="utf-8")

    if REPLACEMENT in text and OLD_GATED not in text:
        print("already applied")
        return 0

    if ANCHOR not in text:
        print("unconditional uncompiled_sources_ block not found", file=sys.stderr)
        return 1
    if OLD_GATED not in text:
        print("gated browser_action entry not found", file=sys.stderr)
        return 1

    text = text.replace(ANCHOR, REPLACEMENT, 1).replace(OLD_GATED, NEW_GATED, 1)

    # A duplicate would be silently accepted by gn and produce a duplicate
    # resource id, so check rather than assume.
    for name in ("browser_action.json", "page_action.json"):
        count = text.count(f'"{name}"')
        if count != 1:
            print(f"{name} appears {count} times, expected 1", file=sys.stderr)
            return 1

    TARGET.write_text(text, encoding="utf-8")
    print("browserAction and pageAction schemas now built on Android")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Port the webNavigation API to desktop-android.

The blocker for bundled uBlock Origin. Its `js/webext.js:118` does
`promisify(chrome.webNavigation, 'getFrame')`, and on Android
`chrome.webNavigation` is undefined, so uBO throws during its own bootstrap and
never starts. `_permission_features.json` says why in as many words:

    "webNavigation": {
      ...
      // "desktop_android" is not supported.
      "platforms": ["chromeos", "linux", "mac", "win"]
    },

## Why the API was excluded, and why most of it need not be

The implementation is two classes with very different dependencies:

`WebNavigationTabObserver` is a `WebContentsObserver`. It produces every event
uBO actually consumes -- onBeforeNavigate, onCommitted, onDOMContentLoaded,
onCompleted, onErrorOccurred -- and it backs `getFrame` and `getAllFrames`.
Nothing about it is desktop-specific.

`WebNavigationEventRouter` is a `TabStripModelObserver` and a
`BrowserTabStripTrackerDelegate`. It exists to track tabs being *replaced* and
to correlate a newly opened WebContents with the frame that opened it. It serves
exactly two events, `onTabReplaced` and `onCreatedNavigationTarget`, and it is
the only reason the target asserts `enable_extensions` and pulls
//chrome/browser/ui:browser_tab_strip, :browser_list and /browser_window.

So this compiles the whole API on Android and guards the tab-strip half. The
guard is `#if !BUILDFLAG(IS_ANDROID)`, which is not a choice so much as a
match: `ExtensionTabUtil::GetTabStripModel`, the helper the guarded code calls,
is declared under that exact condition in extension_tab_util.h. Upstream already
drew this line; the API target simply had not been split along it.

## What Android does not get

`webNavigation.onTabReplaced` and `webNavigation.onCreatedNavigationTarget` never
fire. Both describe desktop tab-strip mechanics that Cobalt's shell does not have
yet, and neither is used by uBlock Origin. When Cobalt has its own tab model
(decision 0002), this is where it hooks in -- the guarded region is small and
self-contained on purpose, rather than deleted.

Everything else, including the two functions uBO calls, works.

Idempotent; every edit asserts.
"""
import sys
from pathlib import Path

SRC = Path(sys.argv[1] if len(sys.argv) > 1 else "/opt/cobalt/chromium/m140/src")

GUARD_OPEN = "#if !BUILDFLAG(IS_ANDROID)\n"
GUARD_CLOSE = "#endif  // !BUILDFLAG(IS_ANDROID)\n"


class Failed(Exception):
    pass


def edit(rel: str, pairs, *, done_marker: str) -> str:
    """Apply (old, new) replacements to one file, all or nothing."""
    path = SRC / rel
    if not path.exists():
        raise Failed(f"missing {rel}")
    text = path.read_text(encoding="utf-8")
    if done_marker in text:
        return "already applied"
    for old, _ in pairs:
        if old not in text:
            raise Failed(f"anchor not found in {rel}: {old.strip()[:60]!r}")
        if text.count(old) != 1:
            raise Failed(
                f"anchor ambiguous in {rel} ({text.count(old)}x): "
                f"{old.strip()[:60]!r}")
    for old, new in pairs:
        text = text.replace(old, new, 1)
    path.write_text(text, encoding="utf-8")
    return "patched"


# ------------------------------------------------------------ 1. permission


def patch_permission() -> str:
    return edit(
        "chrome/common/extensions/api/_permission_features.json",
        [(
            '''  "webNavigation": {
    "channel": "stable",
    "extension_types": ["extension", "legacy_packaged_app"],
    // "desktop_android" is not supported.
    "platforms": ["chromeos", "linux", "mac", "win"]
  },''',
            '''  "webNavigation": {
    "channel": "stable",
    "extension_types": ["extension", "legacy_packaged_app"],
    // Cobalt supports this on desktop_android. The tab-strip half of the
    // implementation is compiled out there, so onTabReplaced and
    // onCreatedNavigationTarget do not fire; every other event, and both
    // getFrame and getAllFrames, work normally. See
    // chrome/browser/extensions/api/web_navigation/web_navigation_api.h.
    "platforms": ["chromeos", "linux", "mac", "win", "desktop_android"]
  },''',
        )],
        done_marker='"win", "desktop_android"')


# ------------------------------------------------------------ 2. schema


def patch_schema_sources() -> str:
    return edit(
        "chrome/common/extensions/api/api_sources.gni",
        [
            # Out of the enable_extensions block.
            ('    "tab_groups.json",\n    "web_navigation.json",\n',
             '    "tab_groups.json",\n'),
            # Into the unconditional list, in alphabetical position.
            ('  "webrtc_audio_private.idl",\n  "webrtc_logging_private.idl",\n',
             '  # Compiled unconditionally: Cobalt supports webNavigation on\n'
             '  # desktop_android. Only its tab-strip half is platform-specific,\n'
             '  # and that is guarded inside the implementation.\n'
             '  "web_navigation.json",\n\n'
             '  "webrtc_audio_private.idl",\n  "webrtc_logging_private.idl",\n'),
        ],
        done_marker="Cobalt supports webNavigation on")


def patch_api_build() -> str:
    return edit(
        "chrome/browser/extensions/api/BUILD.gn",
        [
            ('      "//chrome/browser/extensions/api/web_navigation",\n', ""),
            ('    "//chrome/browser/extensions/api/web_authentication_proxy",\n',
             '    # Supported on desktop_android; see the target\'s own BUILD.gn.\n'
             '    "//chrome/browser/extensions/api/web_navigation",\n'
             '    "//chrome/browser/extensions/api/web_authentication_proxy",\n'),
        ],
        done_marker="Supported on desktop_android; see the target")


def patch_target_build() -> str:
    return edit(
        "chrome/browser/extensions/api/web_navigation/BUILD.gn",
        [
            ("assert(enable_extensions)\n",
             "# enable_extensions_core, not enable_extensions: the parts that need the\n"
             "# desktop browser are guarded with !IS_ANDROID inside the sources.\n"
             "assert(enable_extensions_core)\n"),
            ('''  public_deps = [
    "//base",
    "//chrome/browser/profiles:profile",
    "//chrome/browser/ui:browser_tab_strip",
    "//content/public/browser",
    "//extensions/browser",
    "//ui/base",
    "//url",
  ]

  deps = [
    "//chrome/browser/extensions",
    "//chrome/browser/ui:browser_list",
    "//chrome/browser/ui/browser_window",
    "//chrome/common",
''',
             '''  public_deps = [
    "//base",
    "//chrome/browser/profiles:profile",
    "//content/public/browser",
    "//extensions/browser",
    "//ui/base",
    "//url",
  ]

  deps = [
    "//chrome/browser/extensions",
    "//chrome/common",
'''),
            ('''  if (enable_pdf) {''',
             '''  # The tab-strip observer half of the API. WebNavigationEventRouter is a
  # TabStripModelObserver, which has no meaning on Android; the events it
  # serves (onTabReplaced, onCreatedNavigationTarget) are compiled out there.
  if (!is_android) {
    public_deps += [ "//chrome/browser/ui:browser_tab_strip" ]
    deps += [
      "//chrome/browser/ui:browser_list",
      "//chrome/browser/ui/browser_window",
    ]
  }

  if (enable_pdf) {'''),
        ],
        done_marker="The tab-strip observer half of the API")


# ------------------------------------------------------------ 3. sources


def patch_header() -> str:
    return edit(
        "chrome/browser/extensions/api/web_navigation/web_navigation_api.h",
        [
            # Desktop-only includes.
            ('''#include "chrome/browser/profiles/profile.h"
#include "chrome/browser/ui/browser_tab_strip_tracker.h"
#include "chrome/browser/ui/browser_tab_strip_tracker_delegate.h"
#include "chrome/browser/ui/tabs/tab_strip_model.h"
#include "content/public/browser/web_contents_observer.h"
''',
             '''#include "build/build_config.h"
#include "chrome/browser/profiles/profile.h"
#include "content/public/browser/web_contents_observer.h"
'''),
            ('''#include "extensions/browser/extension_function.h"
#include "url/gurl.h"
''',
             '''#include "extensions/browser/extension_function.h"
#include "url/gurl.h"

#if !BUILDFLAG(IS_ANDROID)
#include "chrome/browser/ui/browser_tab_strip_tracker.h"
#include "chrome/browser/ui/browser_tab_strip_tracker_delegate.h"
#include "chrome/browser/ui/tabs/tab_strip_model.h"
#endif  // !BUILDFLAG(IS_ANDROID)
'''),
            # The event router class itself.
            ('''// Tracks new tab navigations and routes them as events to the extension system.
class WebNavigationEventRouter : public TabStripModelObserver,
''',
             '''#if !BUILDFLAG(IS_ANDROID)
// Tracks new tab navigations and routes them as events to the extension system.
//
// Not built on Android: it observes the desktop TabStripModel, and the two
// events it serves -- onTabReplaced and onCreatedNavigationTarget -- describe
// tab-strip mechanics Cobalt's shell does not have. Everything else in this
// file, including getFrame and getAllFrames, is platform-neutral. When Cobalt
// has its own tab model this is where it hooks in.
class WebNavigationEventRouter : public TabStripModelObserver,
'''),
            ('''  BrowserTabStripTracker browser_tab_strip_tracker_;
};
''',
             '''  BrowserTabStripTracker browser_tab_strip_tracker_;
};
#endif  // !BUILDFLAG(IS_ANDROID)
'''),
            # The member on WebNavigationAPI.
            ('''  // Created lazily upon OnListenerAdded.
  std::unique_ptr<WebNavigationEventRouter> web_navigation_event_router_;
''',
             '''#if !BUILDFLAG(IS_ANDROID)
  // Created lazily upon OnListenerAdded.
  std::unique_ptr<WebNavigationEventRouter> web_navigation_event_router_;
#endif  // !BUILDFLAG(IS_ANDROID)
'''),
        ],
        done_marker="Not built on Android: it observes the desktop TabStripModel")


def patch_impl() -> str:
    return edit(
        "chrome/browser/extensions/api/web_navigation/web_navigation_api.cc",
        [
            ('''#include "chrome/browser/profiles/profile.h"
#include "chrome/browser/ui/browser_window/public/browser_window_interface.h"
#include "chrome/common/extensions/api/web_navigation.h"
''',
             '''#include "chrome/browser/profiles/profile.h"
#include "chrome/common/extensions/api/web_navigation.h"
'''),
            ('''#include "net/base/net_errors.h"
#include "pdf/buildflags.h"
''',
             '''#include "net/base/net_errors.h"
#include "pdf/buildflags.h"

#if !BUILDFLAG(IS_ANDROID)
#include "chrome/browser/ui/browser_window/public/browser_window_interface.h"
#endif  // !BUILDFLAG(IS_ANDROID)
'''),
            # The whole event router implementation.
            ('''// WebNavigtionEventRouter -------------------------------------------

WebNavigationEventRouter::PendingWebContents::PendingWebContents() = default;
''',
             '''// WebNavigtionEventRouter -------------------------------------------
//
// Desktop only; see the class comment in the header.
#if !BUILDFLAG(IS_ANDROID)

WebNavigationEventRouter::PendingWebContents::PendingWebContents() = default;
'''),
            ('''// WebNavigationTabObserver ------------------------------------------
''',
             '''#endif  // !BUILDFLAG(IS_ANDROID)

// WebNavigationTabObserver ------------------------------------------
'''),
            # onCreatedNavigationTarget: entirely routed through the event router.
            ('''  WebNavigationAPI* api = WebNavigationAPI::GetFactoryInstance()->Get(
      web_contents()->GetBrowserContext());
  if (!api)
    return;  // Possible in unit tests.
  WebNavigationEventRouter* router = api->web_navigation_event_router_.get();
  if (!router)
    return;

  TabStripModel* ignored_tab_strip_model = nullptr;
  int ignored_tab_index = -1;
  bool new_contents_is_present_in_tabstrip = ExtensionTabUtil::GetTabStripModel(
      new_contents, &ignored_tab_strip_model, &ignored_tab_index);
  router->RecordNewWebContents(
      web_contents(), source_render_frame_host->GetProcess()->GetDeprecatedID(),
      source_render_frame_host->GetRoutingID(), url, new_contents,
      !new_contents_is_present_in_tabstrip);
}
''',
             '''#if !BUILDFLAG(IS_ANDROID)
  // onCreatedNavigationTarget is delivered entirely through the tab-strip
  // event router, so on Android there is nothing left to do here.
  WebNavigationAPI* api = WebNavigationAPI::GetFactoryInstance()->Get(
      web_contents()->GetBrowserContext());
  if (!api)
    return;  // Possible in unit tests.
  WebNavigationEventRouter* router = api->web_navigation_event_router_.get();
  if (!router)
    return;

  TabStripModel* ignored_tab_strip_model = nullptr;
  int ignored_tab_index = -1;
  bool new_contents_is_present_in_tabstrip = ExtensionTabUtil::GetTabStripModel(
      new_contents, &ignored_tab_strip_model, &ignored_tab_index);
  router->RecordNewWebContents(
      web_contents(), source_render_frame_host->GetProcess()->GetDeprecatedID(),
      source_render_frame_host->GetRoutingID(), url, new_contents,
      !new_contents_is_present_in_tabstrip);
#endif  // !BUILDFLAG(IS_ANDROID)
}
'''),
            # Lazy creation of the router.
            ('''void WebNavigationAPI::OnListenerAdded(const EventListenerInfo& details) {
  web_navigation_event_router_ = std::make_unique<WebNavigationEventRouter>(
      Profile::FromBrowserContext(browser_context_));
  EventRouter::Get(browser_context_)->UnregisterObserver(this);
}
''',
             '''void WebNavigationAPI::OnListenerAdded(const EventListenerInfo& details) {
#if !BUILDFLAG(IS_ANDROID)
  web_navigation_event_router_ = std::make_unique<WebNavigationEventRouter>(
      Profile::FromBrowserContext(browser_context_));
#endif  // !BUILDFLAG(IS_ANDROID)
  EventRouter::Get(browser_context_)->UnregisterObserver(this);
}
'''),
        ],
        done_marker="Desktop only; see the class comment in the header")


STEPS = [
    ("_permission_features.json (allow desktop_android)", patch_permission),
    ("api_sources.gni (compile the schema unconditionally)", patch_schema_sources),
    ("chrome/browser/extensions/api/BUILD.gn (ungate the dep)", patch_api_build),
    ("web_navigation/BUILD.gn (split the desktop deps)", patch_target_build),
    ("web_navigation_api.h (guard the tab-strip observer)", patch_header),
    ("web_navigation_api.cc (guard the tab-strip observer)", patch_impl),
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
            print(f"  {name:<56} FAILED  {exc}")
            failures += 1
        else:
            print(f"  {name:<56} {result}")

    if failures:
        print(f"\n{failures} step(s) failed; tree is partially patched",
              file=sys.stderr)
        return 1
    print("\nwebNavigation available on desktop_android "
          "(minus onTabReplaced / onCreatedNavigationTarget)")
    return 0


if __name__ == "__main__":
    sys.exit(main())

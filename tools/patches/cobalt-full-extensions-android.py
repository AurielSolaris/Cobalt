#!/usr/bin/env python3
"""Let enable_extensions and is_desktop_android be set together.

Cobalt wants the FULL extensions platform on Android -- desktop-class
extensions on a phone, which is the entire point of the project -- rather than
the core subset upstream's desktop-android variant ships.

Both flags work individually. Together, gn fails:

    ERROR at //chrome/browser/BUILD.gn:7860: Duplicate item
    "//chrome/browser/extensions" in list.

because the enable_extensions block and the enable_desktop_android_extensions
block each append it to allow_circular_includes_from. Nobody has tried the
combination -- upstream's own comment on that block says
"TODO(crbug.com/356905053): Add more dependencies here and merge this block
with the `enable_extensions` block above."

So this is an upstream bug in a combination they intend to support, not a
design conflict. The fix is one guard, and it disappears on its own when
upstream merges the blocks. Worth sending back to them.
"""
import argparse, io, os, sys

OLD = '''    # Circular dependency due to some utility function in
    # //chrome/browser/extensions/extension_util.cc calling
    # DesktopAndroidExtensionSystem to reload extension as a temporary
    # workaround before ExtensionService is available on desktop android.
    # TODO(https://crbug.com/356905053): Remove the circular dependency once
    # we port ExtensionService and migrate away from
    # extensions/desktop_android/desktop_android_extension_system.cc.
    allow_circular_includes_from += [ "//chrome/browser/extensions" ]'''

NEW = '''    # Circular dependency due to some utility function in
    # //chrome/browser/extensions/extension_util.cc calling
    # DesktopAndroidExtensionSystem to reload extension as a temporary
    # workaround before ExtensionService is available on desktop android.
    # TODO(https://crbug.com/356905053): Remove the circular dependency once
    # we port ExtensionService and migrate away from
    # extensions/desktop_android/desktop_android_extension_system.cc.
    #
    # Cobalt: guarded because we set enable_extensions AND
    # is_desktop_android together, and the enable_extensions block above
    # already added this. Upstream has not hit it because the combination is
    # untried; their own TODO above says these two blocks should merge, at
    # which point this guard becomes unnecessary.
    if (!enable_extensions) {
      allow_circular_includes_from += [ "//chrome/browser/extensions" ]
    }'''

PAK_OLD = """    if (enable_extensions) {
      sources +=
          [ "$root_gen_dir/chrome/extensions_zero_state_promo_resources.pak" ]
      deps +=
          [ "//chrome/browser/resources/extensions_zero_state_promo:resources" ]
    }"""

PAK_NEW = """    # Cobalt: the zero-state promo is a desktop WebUI surface and its own
    # BUILD.gn asserts !is_android. It is UI advertising the Chrome Web Store
    # to users with no extensions installed -- not something a phone browser
    # needs, and not something that builds here.
    if (enable_extensions && !is_android) {
      sources +=
          [ "$root_gen_dir/chrome/extensions_zero_state_promo_resources.pak" ]
      deps +=
          [ "//chrome/browser/resources/extensions_zero_state_promo:resources" ]
    }"""

APPS_OLD = """    deps += [
      "//apps",
      "//chrome/browser/ui/web_applications",
      "//chrome/browser/web_applications/extensions",
      "//chrome/common/apps/platform_apps",
      "//chrome/common/extensions/api","""

APPS_NEW = """    deps += [
      "//chrome/common/extensions/api","""

APPS2_OLD = """      "//google_apis/drive",
    ]
    if (is_chromeos) {"""

APPS2_NEW = """      "//google_apis/drive",
    ]

    # Cobalt: the Chrome Apps and desktop web-app runtimes. //apps and
    # //chrome/browser/web_applications both assert !is_android, and none of
    # this is meaningful on a phone -- Chrome Apps are deprecated and desktop
    # web apps are a window-management feature. Upstream declares
    # enable_platform_apps separately so it can be turned off; this makes the
    # deps follow that flag rather than enable_extensions.
    if (enable_platform_apps) {
      deps += [
        "//apps",
        "//chrome/browser/ui/web_applications",
        "//chrome/browser/web_applications/extensions",
        "//chrome/common/apps/platform_apps",
      ]
    }

    if (is_chromeos) {"""

EDITS = [
    dict(name="dedupe circular include for full+android extensions",
         path="chrome/browser/BUILD.gn", old=OLD, new=NEW),
    dict(name="skip desktop zero-state promo pak on Android",
         path="chrome/chrome_paks.gni", old=PAK_OLD, new=PAK_NEW),
    dict(name="Chrome Apps deps follow enable_platform_apps",
         path="chrome/browser/BUILD.gn", old=APPS_OLD, new=APPS_NEW),
    dict(name="re-add Chrome Apps deps under their own guard",
         path="chrome/browser/BUILD.gn", old=APPS2_OLD, new=APPS2_NEW),
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=os.environ.get("SRC", "/opt/cobalt/chromium/m140/src"))
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()

    todo, done, missing = [], [], []
    for e in EDITS:
        p = os.path.join(a.src, e["path"])
        if not os.path.isfile(p):
            missing.append(e); continue
        s = io.open(p, encoding="utf-8").read()
        if e["new"] in s: done.append(e)
        elif e["old"] in s: todo.append((e, p, s))
        else: missing.append(e)

    for e in done: print("  %-46s already applied" % e["name"])
    for e, _, _ in todo: print("  %-46s %s" % (e["name"], "would apply" if a.check else "applying"))
    for e in missing: print("  %-46s TEXT NOT FOUND in %s" % (e["name"], e["path"]))

    if missing:
        print(); print("ERROR: upstream text moved; re-derive before continuing."); return 2
    if a.check:
        print(); print("up to date" if not todo else "%d pending" % len(todo)); return 1 if todo else 0
    for e, p, s in todo:
        io.open(p, "w", encoding="utf-8", newline="\n").write(s.replace(e["old"], e["new"], 1))
    print(); print("%d edit(s) written" % len(todo)); return 0


if __name__ == "__main__":
    sys.exit(main())

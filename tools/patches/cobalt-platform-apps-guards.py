#!/usr/bin/env python3
"""Make Chrome Apps dependencies follow enable_platform_apps, not enable_extensions.

Cobalt wants the full extensions platform on Android. The blocker is that four
BUILD.gn files pull //apps -- the Chrome Apps runtime -- from inside
`if (enable_extensions)` blocks. //apps asserts !is_android, and Chrome Apps are
deprecated and desktop-only, so on Android the dependency is both fatal and
pointless.

Upstream already declares enable_platform_apps as its own argument for exactly
this purpose:

    enable_platform_apps = enable_extensions   # extensions/buildflags

It simply defaults to following enable_extensions and the deps were never
separated. These edits move the Chrome Apps dependencies under that flag, so
setting enable_platform_apps = false drops them cleanly.

Same shape in every case: lift //apps (and its platform_apps siblings where
adjacent) out of the enable_extensions deps list into a guarded one. Minimal
diffs against files upstream edits often.

Idempotent. --check verifies without writing.
"""
import argparse, io, os, sys

G = "    # Cobalt: Chrome Apps are desktop-only and //apps asserts !is_android.\n"

EDITS = [
    dict(
        name="chrome/browser/extensions",
        path="chrome/browser/extensions/BUILD.gn",
        old='    deps += [\n      "//apps",\n      "//build:branding_buildflags",',
        new='    if (enable_platform_apps) {\n      deps += [ "//apps" ]\n    }\n'
            '    deps += [\n      "//build:branding_buildflags",',
    ),
    dict(
        name="chrome/browser/profiles",
        path="chrome/browser/profiles/BUILD.gn",
        old='  if (enable_extensions) {\n    deps += [\n      "//apps",\n'
            '      "//chrome/browser/apps/platform_apps",\n'
            '      "//chrome/browser/apps/platform_apps/api",\n'
            '      "//chrome/browser/extensions",',
        new='  if (enable_extensions) {\n'
            '    if (enable_platform_apps) {\n'
            '      deps += [\n'
            '        "//apps",\n'
            '        "//chrome/browser/apps/platform_apps",\n'
            '        "//chrome/browser/apps/platform_apps/api",\n'
            '      ]\n'
            '    }\n'
            '    deps += [\n      "//chrome/browser/extensions",',
    ),
    dict(
        name="chrome/browser/ui",
        path="chrome/browser/ui/BUILD.gn",
        old='  if (enable_extensions) {\n    deps += [\n      "//apps",\n'
            '      "//chrome/browser/apps:icon_standardizer",\n'
            '      "//chrome/browser/apps/platform_apps",\n'
            '      "//chrome/browser/apps/platform_apps/api",',
        new='  if (enable_extensions) {\n'
            '    if (enable_platform_apps) {\n'
            '      deps += [\n'
            '        "//apps",\n'
            '        "//chrome/browser/apps/platform_apps",\n'
            '        "//chrome/browser/apps/platform_apps/api",\n'
            '      ]\n'
            '    }\n'
            '    deps += [\n'
            '      "//chrome/browser/apps:icon_standardizer",',
    ),
    dict(
        name="chrome/browser/ui/extensions",
        path="chrome/browser/ui/extensions/BUILD.gn",
        old='    deps += [\n      ":extension_enable_flow_delegate",\n'
            '      ":extension_popup_types",\n      "//apps",\n'
            '      "//chrome/browser/apps/app_service",',
        new='    if (enable_platform_apps) {\n      deps += [ "//apps" ]\n    }\n'
            '    deps += [\n      ":extension_enable_flow_delegate",\n'
            '      ":extension_popup_types",\n'
            '      "//chrome/browser/apps/app_service",',
    ),
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

    for e in done: print("  %-32s already applied" % e["name"])
    for e, _, _ in todo: print("  %-32s %s" % (e["name"], "would apply" if a.check else "applying"))
    for e in missing: print("  %-32s TEXT NOT FOUND (%s)" % (e["name"], e["path"]))

    if missing:
        print(); print("ERROR: upstream text moved; re-derive before continuing."); return 2
    if a.check:
        print(); print("up to date" if not todo else "%d pending" % len(todo)); return 1 if todo else 0
    for e, p, s in todo:
        io.open(p, "w", encoding="utf-8", newline="\n").write(s.replace(e["old"], e["new"], 1))
    print(); print("%d edit(s) written" % len(todo)); return 0


if __name__ == "__main__":
    sys.exit(main())

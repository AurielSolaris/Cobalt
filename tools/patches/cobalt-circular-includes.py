#!/usr/bin/env python3
"""Keep allow_circular_includes_from consistent with the guarded deps.

A GN circular-include entry must name a target that is actually in deps. Once
the Chrome Apps dependencies moved under enable_platform_apps
(cobalt-platform-apps-guards.py), the matching circular-include entries had to
move with them or gn fails with "Label not in deps".

Idempotent. --check verifies without writing.
"""
import argparse, io, os, sys

OLD = '''    allow_circular_includes_from += [
      "//chrome/browser/apps/platform_apps",
      "//chrome/browser/apps/platform_apps/api",
      "//chrome/browser/ui/extensions:impl",
    ]'''

NEW = '''    # Cobalt: the platform_apps entries are only in deps when
    # enable_platform_apps is on, and a circular-include entry must name a
    # target that is actually a dependency.
    if (enable_platform_apps) {
      allow_circular_includes_from += [
        "//chrome/browser/apps/platform_apps",
        "//chrome/browser/apps/platform_apps/api",
      ]
    }
    allow_circular_includes_from += [
      "//chrome/browser/ui/extensions:impl",
    ]'''

EDITS = [dict(name="chrome/browser/ui circular includes",
              path="chrome/browser/ui/BUILD.gn", old=OLD, new=NEW)]


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
    for e in done: print("  %-36s already applied" % e["name"])
    for e, _, _ in todo: print("  %-36s %s" % (e["name"], "would apply" if a.check else "applying"))
    for e in missing: print("  %-36s TEXT NOT FOUND (%s)" % (e["name"], e["path"]))
    if missing:
        print(); print("ERROR: upstream text moved; re-derive before continuing."); return 2
    if a.check:
        print(); print("up to date" if not todo else "%d pending" % len(todo)); return 1 if todo else 0
    for e, p, s in todo:
        io.open(p, "w", encoding="utf-8", newline="\n").write(s.replace(e["old"], e["new"], 1))
    print(); print("%d edit(s) written" % len(todo)); return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Replace Kiwi's "|| true" platform hacks with explicit Android conditions.

Kiwi made these by appending "|| true" to a BUILDFLAG condition, which makes
the guard unconditional rather than saying which platform it is for. The
condition then documents nothing, and any platform upstream adds silently
inherits it. The intent is recoverable, so it is re-expressed by naming
BUILDFLAG(IS_ANDROID).

Written as a script rather than a context patch, per patches/cobalt/README.md:
a diff against a file upstream edits every milestone breaks on every rebase,
while matching on surrounding text survives it -- and reports loudly when the
text has moved, which is exactly what happened to Kiwi's own version of this.

Idempotent. --check verifies without writing.
"""
import argparse, io, os, sys

LINUX_OLD = (
    '#elif BUILDFLAG(IS_LINUX)\n'
    '  return ui::kKeybindingPlatformLinux;'
)
LINUX_NEW = (
    '#elif BUILDFLAG(IS_LINUX) || BUILDFLAG(IS_ANDROID)\n'
    '  // Android reports the Linux platform string: extensions declare their\n'
    '  // shortcuts under "linux" and the manifest schema has no "android" key,\n'
    '  // so otherwise no declared shortcut ever matches.\n'
    '  return ui::kKeybindingPlatformLinux;'
)

EDITS = [
    dict(
        name="keybinding platform on Android",
        path="extensions/common/command.cc",
        old=LINUX_OLD,
        new=LINUX_NEW,
    ),
    # Kiwi's second "|| true" hack -- kChromeAppsEnabled in
    # extensions/browser/pref_names.cc -- is deliberately NOT carried forward.
    # The symbol does not exist anywhere in M140; upstream removed the pref
    # along with Chrome Apps. Porting it would mean reintroducing a declaration
    # that nothing reads.
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
            missing.append(e)
            continue
        s = io.open(p, encoding="utf-8").read()
        if e["new"] in s:
            done.append(e)
        elif e["old"] in s:
            todo.append((e, p, s))
        else:
            missing.append(e)

    for e in done:
        print("  %-34s already applied" % e["name"])
    for e, _, _ in todo:
        print("  %-34s %s" % (e["name"], "would apply" if a.check else "applying"))
    for e in missing:
        print("  %-34s TEXT NOT FOUND in %s" % (e["name"], e["path"]))

    if missing:
        print()
        print("ERROR: upstream text moved; re-derive before continuing.")
        return 2
    if a.check:
        print()
        print("up to date" if not todo else "%d edit(s) pending" % len(todo))
        return 1 if todo else 0

    for e, p, s in todo:
        io.open(p, "w", encoding="utf-8", newline="\n").write(s.replace(e["old"], e["new"], 1))
    print()
    print("%d edit(s) written" % len(todo))
    return 0


if __name__ == "__main__":
    sys.exit(main())

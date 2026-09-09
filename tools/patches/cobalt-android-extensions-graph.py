#!/usr/bin/env python3
"""Prune desktop-only extension targets from the Android build graph.

enable_extensions is a GN argument, so turning it on needs no patch -- but
gn gen then evaluates targets that assume desktop and fails before compiling
anything. This is the smallest set of guards that gets a successful gn gen.

Kiwi solved the same problem by deleting the test target definitions outright
(patches/kiwi-105/extensions_BUILD.gn.patch, 53 removed lines). That patch no
longer applies to M140, and deleting definitions is the wrong shape anyway: it
is a large diff against a file upstream edits often, so it conflicts on every
rebase. Guarding the *reference* with !is_android is one line and says what it
means -- these are desktop test binaries, not part of an Android build.

Idempotent. --check verifies without writing.
"""
import argparse, io, os, sys

EDITS = [
    dict(
        name="gn_all: skip desktop extension test targets",
        path="BUILD.gn",
        old=(
            '    if (enable_extensions) {\n'
            '      deps += [\n'
            '        "//extensions:extensions_browsertests",\n'
            '        "//extensions:extensions_unittests",\n'
            '        "//extensions/browser/api/declarative_net_request/filter_list_converter",\n'
            '        "//extensions/shell:app_shell_unittests",\n'
            '      ]\n'
            '    }'
        ),
        new=(
            '    # Cobalt: these are desktop test and tool binaries. On Android they\n'
            '    # pull in //extensions/shell, which depends on keep_alive_registry,\n'
            '    # which asserts !is_android -- so gn gen fails before compiling\n'
            '    # anything. We build chrome_public_apk, not app_shell.\n'
            '    if (enable_extensions && !is_android) {\n'
            '      deps += [\n'
            '        "//extensions:extensions_browsertests",\n'
            '        "//extensions:extensions_unittests",\n'
            '        "//extensions/browser/api/declarative_net_request/filter_list_converter",\n'
            '        "//extensions/shell:app_shell_unittests",\n'
            '      ]\n'
            '    }'
        ),
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
        if e["new"] in s:
            done.append(e)
        elif e["old"] in s:
            todo.append((e, p, s))
        else:
            missing.append(e)

    for e in done:
        print("  %-46s already applied" % e["name"])
    for e, _, _ in todo:
        print("  %-46s %s" % (e["name"], "would apply" if a.check else "applying"))
    for e in missing:
        print("  %-46s TEXT NOT FOUND in %s" % (e["name"], e["path"]))

    if missing:
        print(); print("ERROR: upstream text moved; re-derive before continuing.")
        return 2
    if a.check:
        print(); print("up to date" if not todo else "%d pending" % len(todo))
        return 1 if todo else 0
    for e, p, s in todo:
        io.open(p, "w", encoding="utf-8", newline="\n").write(s.replace(e["old"], e["new"], 1))
    print(); print("%d edit(s) written" % len(todo))
    return 0


if __name__ == "__main__":
    sys.exit(main())

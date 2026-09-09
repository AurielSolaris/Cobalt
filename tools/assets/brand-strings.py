#!/usr/bin/env python3
"""Rename the product in user-facing strings, and nowhere else.

The launcher icon and the FRE logo were Cobalt's while the first screen still
read "Make Chrome your own". Icons are the easy half of branding; the strings
are where a fork usually gives itself away.

What must NOT be renamed, and why each would be a real bug:

  name="IDS_..."     message ids are internal and are referenced from C++ and
                     Java. Renaming them breaks the build.
  desc="..."         translator notes. Developer-facing, and rewriting them
                     makes the instructions to translators wrong.
  chrome://          a URL scheme, not a product name. cobalt://foo does not
                     resolve, so renaming these breaks navigation.
  Chrome Web Store   an actual Google product Cobalt does not operate. Renaming
                     it would claim a store we do not run.
  Chrome OS          a different product entirely.

So the rewrite touches text nodes inside <message> elements only, never
attributes, and masks the protected phrases before substituting.

Idempotent: running twice changes nothing the second time.

Usage:
    brand-strings.py [--src CHROMIUM_SRC] [--check]
"""
import argparse
import re
import sys
from pathlib import Path

PRODUCT = "Cobalt"

FILES = [
    "chrome/browser/ui/android/strings/android_chrome_strings.grd",
    "chrome/app/chromium_strings.grd",
]

# Longest first: "Google Chrome" must be consumed before "Chrome".
RENAMES = [
    ("Google Chrome", PRODUCT),
    ("Chromium", PRODUCT),
    ("Chrome", PRODUCT),
]

PROTECTED = [
    "chrome://",
    "Chrome Web Store",
    "Chrome OS",
    "ChromeOS",
    "Chrome Canary",
]

TAG = re.compile(r"(<[^>]*>)")
MSG_OPEN = re.compile(r"<message\b")
MSG_CLOSE = re.compile(r"</message>")


def rewrite_text(text: str) -> str:
    masked = {}
    for i, phrase in enumerate(PROTECTED):
        token = "\x00P" + str(i) + "\x00"
        if phrase in text:
            masked[token] = phrase
            text = text.replace(phrase, token)
    for old, new in RENAMES:
        text = text.replace(old, new)
    for token, phrase in masked.items():
        text = text.replace(token, phrase)
    return text


def process(path: Path, check: bool):
    original = path.read_text(encoding="utf-8")
    out = []
    depth = 0
    changed = 0

    for line in original.split(chr(10)):
        # Tag segments are preserved verbatim, so attributes -- ids and
        # translator descriptions alike -- are never touched.
        parts = TAG.split(line)
        rebuilt = []
        for part in parts:
            if part.startswith("<"):
                rebuilt.append(part)
                if MSG_OPEN.search(part):
                    depth += 1
                if MSG_CLOSE.search(part):
                    depth = max(0, depth - 1)
            else:
                if depth > 0 and part.strip():
                    new = rewrite_text(part)
                    if new != part:
                        changed += 1
                    rebuilt.append(new)
                else:
                    rebuilt.append(part)
        out.append("".join(rebuilt))

    text = chr(10).join(out)
    if check:
        return changed, 0
    if changed:
        path.write_text(text, encoding="utf-8", newline=chr(10))
    return changed, 1 if changed else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default="/opt/cobalt/chromium/m140/src")
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()

    src = Path(args.src)
    total = 0
    for rel in FILES:
        path = src / rel
        if not path.is_file():
            print("missing: " + rel, file=sys.stderr)
            return 1
        changed, _ = process(path, args.check)
        total += changed
        print("  %-58s %d segments" % (rel, changed))

    if args.check:
        print("check only; nothing written")
        return 0
    if total == 0:
        print("already applied")
    else:
        print("rewrote " + str(total) + " text segments")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Offer Ecosia, Kagi and Qwant as search engines in every region.

All three are already defined in Chromium's prepopulated_engines.json, so
nothing needs inventing -- but which engines a user is *offered* is decided
per country by regional_settings.json, and as shipped that is: Ecosia in 39 of
110 regions, Qwant in 27, Kagi in none at all.

Cobalt offers the same set everywhere. A user's choice of search engine should
not depend on which country the build thinks they are in, and Kagi in
particular is invisible by default despite being in the data.

The file is JSON with comments, so it is edited line-wise rather than parsed
and re-serialised: round-tripping through json would silently delete every
country-name comment in it.

Idempotent. Verified by stripping comments and parsing the result, because an
edit that produces invalid JSON here fails at build time in a generated header,
a long way from the cause.
"""
import json
import re
import sys
from pathlib import Path

ADD = ["ecosia", "kagi", "qwant"]

SRC = Path(sys.argv[1] if len(sys.argv) > 1 else "/opt/cobalt/chromium/m140/src")
TARGET = SRC / "third_party/search_engines_data/resources/definitions/regional_settings.json"

ENTRY = re.compile(r'^(\s*)"&([a-z0-9_]+)",?\s*$')


INLINE = re.compile(r'^(\s*)"search_engines": \[(.*)\](,?)\s*$')


def strip_comments(text):
    out = []
    in_string = False
    escaped = False
    i = 0
    while i < len(text):
        c = text[i]
        if in_string:
            out.append(c)
            if escaped:
                escaped = False
            elif c == chr(92):
                escaped = True
            elif c == '"':
                in_string = False
            i += 1
            continue
        if c == '"':
            in_string = True
            out.append(c)
            i += 1
            continue
        if c == "/" and i + 1 < len(text) and text[i + 1] == "/":
            while i < len(text) and text[i] != chr(10):
                i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


def main() -> int:
    if not TARGET.exists():
        print("missing: " + str(TARGET), file=sys.stderr)
        return 1

    lines = TARGET.read_text(encoding="utf-8").split(chr(10))
    out = []
    in_array = False
    block = []
    indent = "        "
    regions_touched = 0

    def flush():
        nonlocal regions_touched
        names = [ENTRY.match(b).group(2) for b in block if ENTRY.match(b)]
        missing = [a for a in ADD if a not in names]
        if not missing:
            out.extend(block)
            return
        regions_touched += 1

        # Insert after the LAST ENTRY, not the last line. Some arrays end with
        # a blank line or a comment (FR has one upstream), and appending at the
        # end of the block would leave the final entry without its comma --
        # producing "&yahoo_fr" "&ecosia" and invalid JSON.
        last_entry = max(i for i, b in enumerate(block) if ENTRY.match(b))
        for i, b in enumerate(block):
            m = ENTRY.match(b)
            if i == last_entry:
                out.append(m.group(1) + '"&' + m.group(2) + '",')
                for j, name in enumerate(missing):
                    comma = "," if j < len(missing) - 1 else ""
                    out.append(m.group(1) + '"&' + name + '"' + comma)
            else:
                out.append(b)

    for line in lines:
        if not in_array:
            # 8 of the 110 regions write the array on one line. Treating those
            # as "array opened" swallows the rest of the file, because the
            # closing bracket never arrives on a line of its own.
            inline = INLINE.match(line)
            if inline:
                pad, body, tail = inline.group(1), inline.group(2), inline.group(3)
                have = re.findall(r'"&([a-z0-9_]+)"', body)
                missing = [a for a in ADD if a not in have]
                if missing:
                    regions_touched += 1
                    body = body.rstrip()
                    if body and not body.endswith(","):
                        body += ","
                    body += " " + ", ".join('"&' + n + '"' for n in missing)
                out.append(pad + '"search_engines": [' + body + "]" + tail)
                continue
            out.append(line)
            if '"search_engines": [' in line:
                in_array = True
                block = []
            continue
        if line.strip().startswith("]"):
            flush()
            out.append(line)
            in_array = False
            block = []
            continue
        block.append(line)
        m = ENTRY.match(line)
        if m:
            indent = m.group(1)

    text = chr(10).join(out)

    # Comments make this invalid JSON, so strip them before validating. This is
    # a check on our own edit, not a re-serialisation.
    #
    # A line-anchored regex is not enough: the country-alias section of this
    # file uses TRAILING comments ("GP": "FR", // Guadeloupe), and a naive
    # strip of everything after // would also cut URLs in half. Scan instead,
    # tracking whether we are inside a string.
    stripped = strip_comments(text)
    try:
        data = json.loads(stripped)
    except json.JSONDecodeError as e:
        print("edit produced invalid JSON: " + str(e), file=sys.stderr)
        return 1

    for region, cfg in data["elements"].items():
        for name in ADD:
            if "&" + name not in cfg["search_engines"]:
                print("region " + region + " still missing " + name, file=sys.stderr)
                return 1

    if regions_touched == 0:
        print("already applied: all regions offer " + ", ".join(ADD))
        return 0

    TARGET.write_text(text, encoding="utf-8", newline=chr(10))
    print("added " + ", ".join(ADD) + " to " + str(regions_touched) + " regions "
          "(" + str(len(data["elements"])) + " total)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

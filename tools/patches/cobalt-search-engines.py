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

# The largest regional list after our additions. Non-EEA regions are truncated
# to this many engines, so it has to cover the longest list we produce.
WANT_VISIBLE = 12

SRC = Path(sys.argv[1] if len(sys.argv) > 1 else "/opt/cobalt/chromium/m140/src")
TARGET = SRC / "third_party/search_engines_data/resources/definitions/regional_settings.json"
ENGINES = SRC / "third_party/search_engines_data/resources/definitions/prepopulated_engines.json"
THRESHOLD = SRC / "components/regional_capabilities/regional_capabilities_utils.cc"

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


def raise_top_threshold():
    """Stop non-EEA regions being truncated to the first five engines.

    GetPrepopulatedEngines() takes .first(kTopSearchEnginesThreshold) for the
    kTopFive list type, which every non-EEA country gets. Appending engines to
    a region therefore does nothing visible: the data is correct, compiled in,
    and sliced off before it reaches the settings screen. This was only found
    by tracing the consumer -- the build, the generated header and the shipped
    libchrome.so all contained the new engines.

    EEA countries are unaffected: they use the shuffled list, which is complete.
    """
    if not THRESHOLD.exists():
        print("missing: " + str(THRESHOLD), file=sys.stderr)
        return False
    text = THRESHOLD.read_text(encoding="utf-8")
    m = re.search(r"(constexpr size_t kTopSearchEnginesThreshold = )(\d+)(;)", text)
    if not m:
        print("kTopSearchEnginesThreshold not found", file=sys.stderr)
        return False
    if int(m.group(2)) >= WANT_VISIBLE:
        return True
    updated = text[:m.start()] + m.group(1) + str(WANT_VISIBLE) + m.group(3) + text[m.end():]
    assert updated != text, "threshold unchanged"
    THRESHOLD.write_text(updated, encoding="utf-8", newline=chr(10))
    print("raised kTopSearchEnginesThreshold " + m.group(2) + " -> " + str(WANT_VISIBLE))
    return True


def bump_data_version():
    """Raise kCurrentDataVersion so existing profiles re-merge the engine list."""
    if not ENGINES.exists():
        print("missing: " + str(ENGINES), file=sys.stderr)
        return False
    text = ENGINES.read_text(encoding="utf-8")
    m = re.search(r'("kCurrentDataVersion":\s*)(\d+)', text)
    if not m:
        print("kCurrentDataVersion not found", file=sys.stderr)
        return False
    old = int(m.group(2))
    new = old + 1
    updated = text[:m.start()] + m.group(1) + str(new) + text[m.end():]
    assert updated != text, "version unchanged"
    ENGINES.write_text(updated, encoding="utf-8", newline=chr(10))
    print("bumped kCurrentDataVersion " + str(old) + " -> " + str(new))
    return True


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

    # A profile that already exists keeps the engine list it was created with.
    # The merge only re-runs when the stored prepopulate version is lower than
    # kCurrentDataVersion, so without a bump this patch is invisible to every
    # existing install -- which is exactly what the first on-device test showed:
    # the build had the data and the settings screen still listed the old five.
    if not raise_top_threshold():
        return 1

    if not bump_data_version():
        return 1

    TARGET.write_text(text, encoding="utf-8", newline=chr(10))
    print("added " + ", ".join(ADD) + " to " + str(regions_touched) + " regions "
          "(" + str(len(data["elements"])) + " total)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

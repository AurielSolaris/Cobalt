#!/usr/bin/env python3
"""Port Kiwi's chrome-search URL pattern scheme to M140.

Kiwi added content::kChromeSearchScheme to URLPattern's valid scheme list so
extensions can match chrome-search:// URLs. In M140 that constant no longer
exists in content: it lives in chrome/common/url_constants.h as
chrome::kChromeSearchScheme.

extensions/ sits below chrome/ in the dependency graph and never includes it --
verified: extensions/common includes content/public and its own constants, and
nothing from chrome/. So the symbol cannot simply be re-namespaced; taking it
from its new home would be a layering violation that gn would reject.

The scheme string is therefore defined locally, next to the array that uses it,
with the duplication called out. One duplicated string literal is the cheaper
correctness cost here.

Idempotent. Every replacement is asserted, because an unasserted str.replace
reports success while changing nothing -- twice already in this project.
"""
import sys
from pathlib import Path

SRC = Path(sys.argv[1] if len(sys.argv) > 1 else "/opt/cobalt/chromium/m140/src")
TARGET = SRC / "extensions/common/url_pattern.cc"

MOVED = "    content::kChromeSearchScheme"
LOCAL = "    kChromeSearchScheme"

ANCHOR = "namespace {\n"
DEFN = (
    "namespace {\n"
    "\n"
    "// chrome-search:// is a chrome/ concept, and extensions/ sits below chrome/\n"
    "// in the dependency graph -- it may not include chrome/common/url_constants.h,\n"
    "// where chrome::kChromeSearchScheme now lives. It was content:: in M105, which\n"
    "// is why Kiwi's patch referred to it that way. Duplicated deliberately rather\n"
    "// than reaching up a layer.\n"
    "constexpr char kChromeSearchScheme[] = \"chrome-search\";\n"
)


def main() -> int:
    if not TARGET.exists():
        print(f"missing: {TARGET}", file=sys.stderr)
        return 1

    text = TARGET.read_text(encoding="utf-8")

    if LOCAL in text and MOVED not in text:
        print("already ported")
        return 0

    if MOVED not in text:
        # The scheme entry is absent entirely: the Kiwi patch is not applied, so
        # there is nothing to port and silently "succeeding" would be a lie.
        print("chrome-search entry not present; nothing to port", file=sys.stderr)
        return 1

    before = text
    text = text.replace(MOVED, LOCAL, 1)
    assert text != before, "scheme reference unchanged"

    if "constexpr char kChromeSearchScheme[]" not in text:
        before = text
        assert text.count(ANCHOR) >= 1, "namespace anchor missing"
        text = text.replace(ANCHOR, DEFN, 1)
        assert text != before, "definition not inserted"

    TARGET.write_text(text, encoding="utf-8", newline="\n")
    print(f"ported chrome-search scheme in {TARGET}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

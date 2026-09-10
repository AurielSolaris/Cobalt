#!/usr/bin/env python3
"""Drop five Google Play Services dependencies chrome_java does not use.

`chrome_java` lists eight GMS modules. Grepping its own sources for what they
actually import:

    gms.cast    0 files      gms.gcm     3 files
    gms.iid     0 files      gms.common  1 file
    gms.auth    0 files
    gms.tasks   0 files

That is suggestive but not proof -- a Java target also needs a dependency on the
classpath when a *library it uses* exposes those types in its public API, which
is a real reason for a dep with no import of its own.

So it was tested rather than argued: the five were removed and `chrome_java`
built.

    $ autoninja -C out/Default -j 6 chrome_java
    Build Succeeded: 7 steps

**Edges 43 -> 38.** No Java changes at all -- these are dependency lines with
nothing behind them.

## Why the module count does not move

`auth_base`, `cast`, `cast_framework`, `iid` and `tasks` all keep referrers
elsewhere: `components/signin`, `components/media_router`,
`components/gcm_driver/instance_id`, `chrome/browser/webid`,
`components/webauthn`, `chrome/browser/ui/android/omnibox`, and
`chrome/android:base_module_java`. The APK still contains them. This removes
five of the edges holding them there, which is what has to happen before the
modules can leave.

## What is deliberately left

`base`, `basement` and `gcm` stay: `chrome/android/java` genuinely imports
`com.google.android.gms.common` (one file) and `com.google.android.gms.gcm`
(three). Those are real work -- decision 0013's Web Push question, which is not
answered yet -- not a bookkeeping tidy-up.

Decision 0014 puts `chrome/android` last on purpose, and this does not jump that
queue: no Java moves, no behaviour changes, and nothing here is the UI-layer
work 0014 is sequencing.

Idempotent; the edit asserts.
"""
import sys
from pathlib import Path

SRC = Path(sys.argv[1] if len(sys.argv) > 1 else "/opt/cobalt/chromium/m140/src")

REL = "chrome/android/BUILD.gn"

# The eight GMS deps chrome_java declares, in the order they appear.
OLD = (
    '      "$google_play_services_package:google_play_services_auth_base_java",\n'
    '      "$google_play_services_package:google_play_services_base_java",\n'
    '      "$google_play_services_package:google_play_services_basement_java",\n'
    '      "$google_play_services_package:google_play_services_cast_framework_java",\n'
    '      "$google_play_services_package:google_play_services_cast_java",\n'
    '      "$google_play_services_package:google_play_services_gcm_java",\n'
    '      "$google_play_services_package:google_play_services_iid_java",\n'
    '      "$google_play_services_package:google_play_services_tasks_java",\n'
)

# The three that chrome/android/java actually imports.
NEW = (
    '      # Cobalt: auth_base, cast, cast_framework, iid and tasks were listed\n'
    '      # here and nothing under chrome/android/java imports them. Verified by\n'
    '      # removing them and building chrome_java, not by grep alone -- a Java\n'
    '      # target can legitimately need a dep it never imports, when a library\n'
    '      # it uses exposes those types. Decision 0013.\n'
    '      #\n'
    '      # base, basement and gcm stay: gms.common and gms.gcm are genuinely\n'
    '      # imported here, and removing them is the Web Push question 0013\n'
    '      # leaves open rather than a bookkeeping change.\n'
    '      "$google_play_services_package:google_play_services_base_java",\n'
    '      "$google_play_services_package:google_play_services_basement_java",\n'
    '      "$google_play_services_package:google_play_services_gcm_java",\n'
)


def main() -> int:
    path = SRC / REL
    if not path.exists():
        print(f"missing {REL}", file=sys.stderr)
        return 1

    text = path.read_text(encoding="utf-8")
    if "auth_base, cast, cast_framework, iid and tasks were listed" in text:
        print("  chrome/android/BUILD.gn (chrome_java deps)         already applied")
        return 0
    if OLD not in text:
        print("  chrome/android/BUILD.gn (chrome_java deps)         FAILED  "
              "the chrome_java GMS dep block changed shape", file=sys.stderr)
        return 1
    if text.count(OLD) != 1:
        print(f"  chrome/android/BUILD.gn  FAILED  anchor matches {text.count(OLD)}x",
              file=sys.stderr)
        return 1

    path.write_bytes(text.replace(OLD, NEW, 1)
                     .replace("\r\n", "\n").encode("utf-8"))
    print("  chrome/android/BUILD.gn (chrome_java deps)         patched")
    print("\nFive dead GMS dependency edges gone; base, basement and gcm remain.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

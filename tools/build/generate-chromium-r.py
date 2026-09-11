#!/usr/bin/env python3
"""Regenerate the R classes `dist_aar` strips, as aliases of the app's R.

Chromium's Java does not use one R class. Every resource-owning target declares
a `resource_package`, and the build generates an `R` for each -- `org.chromium.ui.R`,
`org.chromium.chrome.R`, `org.chromium.components.browser_ui.widget.R`, and so
on. `dist_aar` excludes all of them from the AAR (`build/config/android/rules.gni`,
the `aar` template's default `jar_excluded_patterns`), because an AAR is meant to
carry one R and its consumer regenerates it.

So the AAR's 36,000 classes reference R classes that are not in it, and Chromium
dies the first time any of them is touched -- from inside native code, which
makes it look like a JNI failure rather than a missing class:

    at J.N.M1Y_XVCN(Native Method)
    Caused by: java.lang.NoClassDefFoundError:
    Failed resolution of: Lorg/chromium/ui/R$integer;
      at org.chromium.ui.base.DeviceFormFactor.detectScreenWidthBucket

## Aliases, not copies

The obvious repair -- ship Chromium's generated R classes -- is wrong: their
field values are ids from Chromium's own aapt2 link, and Cobalt's app links its
resources again with different ones. The classes would resolve and then return
numbers that address nothing.

What is generated here instead is one R class per package whose every field
reads the app's R:

    package org.chromium.ui;
    public final class R {
        public static final class integer {
            public static final int min_screen_width_bucket =
                app.auriel.cobalt.R.integer.min_screen_width_bucket;
        }
    }

The app's R is written by AGP after it merges the AAR's resources with the
app's and links them, so those are the ids that are actually in the APK. This
needs `android.nonTransitiveRClass=false`, which is what puts the library
resources into the app's R at all.

## Only what is referenced

Emitting every symbol into every package would be ~21,000 fields times a few
dozen packages, and would not fit a dex file. So the class files are read
directly and their constant pools scanned for field references to `.../R$type`
-- the exact set the code uses and nothing else.

The types come from the AAR's R.txt, which also says which symbols this build
actually defines; the ones it lists as undefined are given zero rather than a
forward to a field the app's R does not have.

Usage:  generate-chromium-r.py <aar> <output java dir> <app package>
"""

import collections
import io
import os
import shutil
import struct
import sys
import zipfile

# Constant pool tags this needs; the rest are skipped by width.
UTF8, INTEGER, FLOAT, LONG, DOUBLE, CLASS, STRING = 1, 3, 4, 5, 6, 7, 8
FIELDREF, METHODREF, IFACEREF, NAMEANDTYPE = 9, 10, 11, 12
METHODHANDLE, METHODTYPE, DYNAMIC, INVOKEDYNAMIC = 15, 16, 17, 18
MODULE, PACKAGE = 19, 20

WIDTHS = {
    INTEGER: 4, FLOAT: 4, LONG: 8, DOUBLE: 8, CLASS: 2, STRING: 2,
    FIELDREF: 4, METHODREF: 4, IFACEREF: 4, NAMEANDTYPE: 4,
    METHODHANDLE: 3, METHODTYPE: 2, DYNAMIC: 4, INVOKEDYNAMIC: 4,
    MODULE: 2, PACKAGE: 2,
}

# Packages whose R the app already has, because it depends on the library from
# Maven and AGP writes an R for every dependency (nonTransitiveRClass=false).
# Emitting a second one is a duplicate class: the APK tolerated it, but the
# unit-test build bundles classes into one jar and refuses --
#
#     classes.jar already contains entry 'androidx/appcompat/R$attr.class'
#
# AGP's copy holds the same linked ids, so skipping ours loses nothing -- as long
# as it has every field Chromium's bytecode reads, which it can only fail to at
# runtime (NoSuchFieldError). So the skipped references are written to
# PROVIDED_LIST, and ProvidedRFieldsTest checks each one against the app's R.
PROVIDED_BY_APP = {
    "androidx.appcompat",  # build.gradle.kts, for ChromeActivity's superclass
    "androidx.core",       # build.gradle.kts, forced to Chromium's version
}
PROVIDED_LIST = "provided-r-fields.txt"

JAVA_KEYWORDS = {
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
    "class", "const", "continue", "default", "do", "double", "else", "enum",
    "extends", "final", "finally", "float", "for", "goto", "if", "implements",
    "import", "instanceof", "int", "interface", "long", "native", "new",
    "package", "private", "protected", "public", "return", "short", "static",
    "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
    "transient", "try", "void", "volatile", "while",
}


def field_refs(data: bytes):
    """Every (class, field name) this class file refers to, from its pool.

    A minimal constant-pool walk. Nothing else in the class file is read: the
    pool is at a fixed offset and every entry's width is known, so the parse
    stops as soon as the pool ends.
    """
    if data[:4] != b"\xca\xfe\xba\xbe":
        return
    count = struct.unpack_from(">H", data, 8)[0]
    pool = {}
    offset = 10
    index = 1
    while index < count:
        tag = data[offset]
        offset += 1
        if tag == UTF8:
            length = struct.unpack_from(">H", data, offset)[0]
            pool[index] = data[offset + 2:offset + 2 + length].decode(
                "utf-8", "replace")
            offset += 2 + length
        else:
            width = WIDTHS.get(tag)
            if width is None:
                return  # unknown tag: refuse to guess rather than misparse
            pool[index] = struct.unpack_from(">HH", data, offset) \
                if width == 4 else struct.unpack_from(">H", data, offset)[0]
            offset += width
        # Long and double take two pool slots and leave one unusable.
        index += 2 if tag in (LONG, DOUBLE) else 1

    # Second pass over the raw pool, now that names can be resolved by index.
    offset = 10
    index = 1
    while index < count:
        tag = data[offset]
        offset += 1
        if tag == UTF8:
            length = struct.unpack_from(">H", data, offset)[0]
            offset += 2 + length
        else:
            if tag == FIELDREF:
                class_index, nat_index = struct.unpack_from(">HH", data, offset)
                class_name = pool.get(pool.get(class_index))
                nat = pool.get(nat_index)
                if class_name and isinstance(nat, tuple):
                    yield class_name, pool.get(nat[0])
            offset += WIDTHS[tag]
        index += 2 if tag in (LONG, DOUBLE) else 1


def read_symbols(aar: zipfile.ZipFile):
    """type -> {name: (java type, defined)} from R.txt.

    Two things come out of R.txt and both matter. The java type, because a
    `styleable` is `int[]` for the array and `int` for each index into it, and
    getting that wrong is a compile error rather than a wrong number.

    And whether the symbol is *defined*. R.txt lists undefined references as
    `0x0` -- resources Chromium's Java mentions but this build does not ship,
    such as Material's date-picker layouts:

        int layout mtrl_picker_fullscreen 0x0

    Those must not forward to the app's R, which has no such field. Chromium's
    own generated R gives them zero, so this does the same: the field exists,
    the class keeps its shape, and code that reads it gets the same nothing it
    would have got upstream.

    Styleable indices are exempt: their value is a position in the array, and
    position zero is a real one.
    """
    symbols = collections.defaultdict(dict)
    for line in aar.read("R.txt").decode("utf-8").splitlines():
        parts = line.split(None, 3)
        if len(parts) < 3:
            continue
        java_type = "int[]" if parts[0] == "int[]" else "int"
        value = parts[3] if len(parts) > 3 else ""
        defined = value != "0x0"
        symbols[parts[1]][parts[2]] = (java_type, defined)
    return symbols


def main() -> int:
    if len(sys.argv) != 4:
        print(__doc__.strip().splitlines()[-1], file=sys.stderr)
        return 2
    aar_path, out_dir, app_package = sys.argv[1], sys.argv[2], sys.argv[3]

    with zipfile.ZipFile(aar_path) as aar:
        symbols = read_symbols(aar)
        jar = zipfile.ZipFile(io.BytesIO(aar.read("classes.jar")))

    # package -> resource type -> set of names
    wanted = collections.defaultdict(lambda: collections.defaultdict(set))
    provided = set()
    for info in jar.infolist():
        if not info.filename.endswith(".class"):
            continue
        for class_name, field in field_refs(jar.read(info)):
            if field is None or "/R$" not in class_name:
                continue
            owner, _, res_type = class_name.partition("/R$")
            if "/" in res_type:
                continue
            package = owner.replace("/", ".")
            if package in PROVIDED_BY_APP:
                if field not in JAVA_KEYWORDS:
                    provided.add("%s.R$%s.%s" % (package, res_type, field))
                continue
            wanted[package][res_type].add(field)

    if not wanted:
        print("REFUSED: no R references found in the AAR's classes. Either the "
              "jar is empty or dist_aar stopped stripping R, and generating "
              "nothing here would fail much later.", file=sys.stderr)
        return 1

    missing = 0
    if os.path.isdir(out_dir):
        shutil.rmtree(out_dir)

    total_fields = 0
    for package, by_type in sorted(wanted.items()):
        path = os.path.join(out_dir, *package.split("."))
        os.makedirs(path, exist_ok=True)
        lines = [
            "// Generated by tools/build/generate-chromium-r.py. Do not edit.",
            "//",
            "// Chromium's R classes are stripped by dist_aar; these forward to",
            "// the app's R, which holds the ids AGP actually linked.",
            "package %s;" % package,
            "",
            "public final class R {",
            "    private R() {}",
        ]
        for res_type in sorted(by_type):
            known = symbols.get(res_type, {})
            lines.append("")
            lines.append("    public static final class %s {" % res_type)
            lines.append("        private %s() {}" % res_type)
            for name in sorted(by_type[res_type]):
                if name in JAVA_KEYWORDS:
                    continue
                entry = known.get(name)
                if entry is None or not entry[1]:
                    # Absent from R.txt, or listed there as undefined. Either
                    # way the app's R has no such field; zero is what Chromium's
                    # own R holds for it.
                    java_type = "int" if entry is None else entry[0]
                    lines.append(
                        "        public static final %s %s = %s;"
                        % (java_type, name,
                           "new int[0]" if java_type == "int[]" else "0"))
                    missing += 1
                    total_fields += 1
                    continue
                lines.append(
                    "        public static final %s %s = %s.R.%s.%s;"
                    % (entry[0], name, app_package, res_type, name))
                total_fields += 1
            lines.append("    }")
        lines.append("}")
        with open(os.path.join(path, "R.java"), "w", encoding="utf-8",
                  newline="\n") as handle:
            handle.write("\n".join(lines) + "\n")

    with open(os.path.join(out_dir, PROVIDED_LIST), "w", encoding="utf-8",
              newline="\n") as handle:
        handle.write("".join(line + "\n" for line in sorted(provided)))

    print("  generated %d R classes, %d fields" % (len(wanted), total_fields))
    print("  %d fields left to the app's own R (%s)"
          % (len(provided), ", ".join(sorted(PROVIDED_BY_APP))))
    if missing:
        print("  %d referenced symbols are undefined in this build and were "
              "given zero, as Chromium's own R does" % missing)
    return 0


if __name__ == "__main__":
    sys.exit(main())

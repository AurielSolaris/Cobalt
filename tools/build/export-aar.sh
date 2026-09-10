#!/usr/bin/env bash
# Copy the Chromium artifacts out of the WSL checkout into the Gradle project.
#
# Cobalt's interface is Compose and Chromium cannot compile Compose, so the
# shell is built in this Gradle project and Chromium arrives as an AAR. See
# docs/shell-integration.md. This is the wire between the two builds.
#
# It copies two things, and the second is the interesting one:
#
#   1. out/Default/apks/cobalt_content.aar  ->  modules/app/libs/
#
#   2. The runtime assets -- .pak bundles, ICU data, the bundled uBlock Origin
#      CRX -- into modules/app/src/main/assets/.
#
#      These cannot travel in the AAR. build/android/gyp/dist_aar.py takes
#      --jars, --dependencies-res-zips, --r-text-files, --proguard-configs and
#      --native-libraries, and has no assets argument of any kind; adding asset
#      targets to its deps builds them and drops them. So they come across
#      separately.
#
#      They land in src/chromium/assets/, NOT src/main/assets/, and that is not
#      cosmetic: src/main/assets holds files this repository owns and tracks --
#      google-fonts.json among them -- and an export that clears its
#      destination would delete them. It did, once. A separate source set means
#      the two can never collide and the wipe below is safe.
#
#      They are taken from ChromePublic.apk rather than from loose build
#      outputs, deliberately: the APK is the one place the exact set the engine
#      loads exists in the exact layout it expects. Reconstructing that from
#      out/Default means guessing which paks matter and where they go, and
#      being wrong produces a browser that starts and then cannot find its own
#      strings.
#
# Nothing this writes is committed: both destinations are gitignored. A ~260 MB
# AAR does not belong in a git repository, and it is reproducible from the
# checkout in under a minute.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SRC="${SRC:-/opt/cobalt/chromium/m140/src}"
OUT="${OUT:-out/Default}"

AAR="$SRC/$OUT/apks/cobalt_content.aar"
APK="$SRC/$OUT/apks/ChromePublic.apk"

LIBS="$REPO/modules/app/libs"
ASSETS="$REPO/modules/app/src/chromium/assets"

[ -d "$SRC" ] || { echo "no checkout at $SRC" >&2; exit 1; }

if [ ! -f "$AAR" ]; then
    echo "no AAR at $AAR" >&2
    echo "build it first:" >&2
    echo "  autoninja -C $OUT -j 6 chrome/android:cobalt_content_dist_aar" >&2
    exit 1
fi

if [ ! -f "$APK" ]; then
    echo "no APK at $APK -- the assets are taken from it" >&2
    exit 1
fi

mkdir -p "$LIBS" "$ASSETS"

echo "=== AAR"
# Not a plain copy: dist_aar writes resources as res/<n>_res/<type>/... and AAPT
# rejects that layout. flatten-aar-res.py strips the prefix, and refuses rather
# than guessing if there is ever more than one index directory to flatten.
python3 "$REPO/tools/build/flatten-aar-res.py" "$AAR" "$LIBS/cobalt-content.aar"
printf '  %s  %.1f MB\n' "modules/app/libs/cobalt-content.aar" \
    "$(echo "scale=1; $(stat -c %s "$LIBS/cobalt-content.aar") / 1048576" | bc)"

echo
echo "=== JNI registration"
# org.jni_zero.GEN_JNI and J.N are the classes libchrome.so registers its native
# methods against. They cannot come in the AAR: every Java target filters
# GEN_JNI out of its main jar by design, so that exactly one copy reaches the
# APK, and dist_aar packages main jars.
#
# "Exactly one copy per APK" makes this the APK's business, and Cobalt's Gradle
# app is the APK -- the same reason BuildConfig and NativeLibraries are written
# by hand in src/main/java. These two are generated and large, so they are
# copied rather than transcribed.
JNI_SRCJAR="$SRC/$OUT/gen/chrome/android/libchrome__jni_registration.srcjar"
JNI_DEST="$REPO/modules/app/src/chromium/java"
# Staleness is the real risk here, not absence.
#
# cobalt_content_dist_aar does not depend on the JNI registration -- only
# chrome_public_apk does -- so building the AAR relinks libchrome.so and leaves
# this srcjar exactly as it was. Adding Java that declares native methods then
# produces an AAR and a registration that disagree, and the disagreement is
# invisible until the method is called:
#
#   NoSuchMethodError: No static method
#   org_chromium_components_embedder_1support_view_ContentViewRenderView_init
#   in class Lorg/jni_zero/GEN_JNI;
#
# So it is compared against the library it has to match.
if [ -f "$JNI_SRCJAR" ] && [ "$SRC/$OUT/libchrome.so" -nt "$JNI_SRCJAR" ]; then
    echo "  STALE: $JNI_SRCJAR is older than libchrome.so" >&2
    echo "  The registration and the library must be generated from the same" >&2
    echo "  Java. Rebuild it:" >&2
    echo "    autoninja -C $OUT -j 6 chrome/android:libchrome__jni_registration" >&2
    exit 1
fi

if [ ! -f "$JNI_SRCJAR" ]; then
    echo "  MISSING: $JNI_SRCJAR" >&2
    echo "  build it:  autoninja -C $OUT chrome/android:libchrome__jni_registration" >&2
    exit 1
fi
rm -rf "$JNI_DEST"
mkdir -p "$JNI_DEST"
unzip -qo "$JNI_SRCJAR" -d "$JNI_DEST"
for f in "org/jni_zero/GEN_JNI.java" "J/N.java"; do
    if [ -f "$JNI_DEST/$f" ]; then
        printf '  %-24s %s bytes
' "$f" "$(stat -c %s "$JNI_DEST/$f")"
    else
        printf '  %-24s MISSING
' "$f"
    fi
done

echo
echo "=== R classes"
# dist_aar strips every generated R, and Chromium has ~145 of them -- one per
# resource_package. They are regenerated here as forwarders to the app's R,
# because Chromium's own carry ids from Chromium's aapt2 link, not the app's.
python3 "$REPO/tools/build/generate-chromium-r.py"     "$LIBS/cobalt-content.aar"     "$REPO/modules/app/src/chromium/r"     app.auriel.cobalt

echo
echo "=== assets, from ChromePublic.apk"
# -o overwrite, -q quiet, -d destination. The leading assets/ is stripped so the
# files land where Android's AssetManager will serve them from.
rm -rf "$ASSETS"/*
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
unzip -qo "$APK" 'assets/*' -d "$TMP"
if [ -d "$TMP/assets" ]; then
    cp -r "$TMP/assets/." "$ASSETS/"
fi

count=$(find "$ASSETS" -type f | wc -l | tr -d ' ')
bytes=$(du -sb "$ASSETS" | cut -f1)
printf '  %s files, %.1f MB\n' "$count" "$(echo "scale=1; $bytes / 1048576" | bc)"
echo
echo "  notable:"
for f in icudtl.dat resources.pak ublock.crx; do
    if [ -f "$ASSETS/$f" ]; then
        printf '    %-16s %s bytes\n' "$f" "$(stat -c %s "$ASSETS/$f")"
    else
        printf '    %-16s MISSING\n' "$f"
    fi
done

echo
echo "Done. Build the app with:  ./gradlew :modules:app:assembleDebug"

#!/usr/bin/env python3
"""Take Google Play Services out of //services/device/geolocation.

Second piece of decision 0013, in the order 0014 sets out. Geolocation keeps
working -- through AOSP -- and Cobalt stops calling Google's fused location
provider.

## The structure, which is as clean as shape_detection's

`LocationProviderFactory.create()` already chooses:

    if (sUseGmsCoreLocationProvider
            && LocationProviderGmsCore.isGooglePlayServicesAvailable(ctx)) {
        sProviderImpl = new LocationProviderGmsCore(ctx);
    } else {
        sProviderImpl = new LocationProviderAndroid();
    }

`LocationProviderAndroid` is `android.location.LocationManager` -- plain AOSP,
no Play Services, no Google endpoint. It is what upstream already runs on every
device without Play Services, so this hardcodes an exercised path rather than
inventing a degraded one.

There is a second gate on top: `use_gms_core_location_provider` is a parameter
threaded from `ContentBrowserClient::ShouldUseGmsCoreGeolocationProvider()`, so
the GMS branch is opt-in even before the availability check.

## What this does not have to remove, which was the surprise

`network_location_request.cc` posts WiFi and cell observations to
`https://www.googleapis.com/geolocation/v1/geolocate`. That is exactly the kind
of thing decision 0013 has to weigh -- a Google *service* rather than a GMS
*library*, like Safe Browsing.

It does not apply here. In `services/device/geolocation/BUILD.gn`,
`network_location_provider.cc` and `wifi_data_provider_common.cc` sit in the
**else** branch, not `is_android`. Chromium on Android delegates entirely to the
platform and never runs that path, so there is no judgement call to make and
nothing to disable. Verified rather than assumed, because the opposite would
have been a much bigger finding.

## What Cobalt gives up, and what it does not control

Losing the fused provider means cold fixes are slower and cost more battery on
devices that have Play Services: no WiFi/cell fusion, no batching, no low-power
modes -- GPS and the platform's own network provider only.

**And removing GMS from Cobalt removes Cobalt's dependency, not the device's.**
`LocationManager` resolves to whatever the OS supplies. On a stock GMS phone the
system network location provider *is* Play Services, and the OS may still
consult Google outside Cobalt's control. On GrapheneOS, LineageOS without GApps,
or microG, it is GPS plus whatever that OS provides. Cobalt's documentation must
not imply more than that.

## Scope

`geolocation_java` pulls `base`, `basement`, `location` and `tasks`. All four
leave this target, but `location` and `tasks` keep referrers in
`components/omnibox` and `chrome/browser/ui/android/omnibox`, so the module
count does not move until those are done -- only the edge count.

## Deliberately not touched

`LocationProviderTest.java` (junit) references `LocationProviderGmsCore` and so
no longer compiles. It is testonly, not in `chrome_public_apk`, and Cobalt does
not build it. Same call as shape_detection's; see docs/gms-removal.md.

Idempotent; every edit asserts.
"""
import sys
from pathlib import Path

SRC = Path(sys.argv[1] if len(sys.argv) > 1 else "/opt/cobalt/chromium/m140/src")

JAVA = "services/device/geolocation/android/java/src/org/chromium/device/geolocation"

DELETE = [f"{JAVA}/LocationProviderGmsCore.java"]


class Failed(Exception):
    pass


def edit(rel: str, pairs, *, done_marker: str) -> str:
    path = SRC / rel
    if not path.exists():
        raise Failed(f"missing {rel}")
    text = path.read_text(encoding="utf-8")
    if done_marker in text:
        return "already applied"
    for old, _ in pairs:
        if old not in text:
            raise Failed(f"anchor not found in {rel}: {old.strip()[:60]!r}")
        if text.count(old) != 1:
            raise Failed(f"anchor ambiguous in {rel} ({text.count(old)}x): "
                         f"{old.strip()[:60]!r}")
    for old, new in pairs:
        text = text.replace(old, new, 1)
    # Chromium sources are LF.
    path.write_bytes(text.replace("\r\n", "\n").encode("utf-8"))
    return "patched"


def patch_build_gn() -> str:
    return edit(
        "services/device/geolocation/BUILD.gn",
        [
            ('      "android/java/src/org/chromium/device/geolocation/LocationProviderFactory.java",\n'
             '      "android/java/src/org/chromium/device/geolocation/LocationProviderGmsCore.java",\n'
             '    ]\n'
             '\n'
             '    deps = [\n'
             '      ":geolocation_jni_headers",\n'
             '      "$google_play_services_package:google_play_services_base_java",\n'
             '      "$google_play_services_package:google_play_services_basement_java",\n'
             '      "$google_play_services_package:google_play_services_location_java",\n'
             '      "$google_play_services_package:google_play_services_tasks_java",\n'
             '      "//base:base_java",\n',
             '      "android/java/src/org/chromium/device/geolocation/LocationProviderFactory.java",\n'
             '    ]\n'
             '\n'
             '    # Cobalt ships no Google Play Services (decision 0013), so the fused\n'
             '    # location provider is gone and LocationProviderAndroid --\n'
             '    # android.location.LocationManager, plain AOSP -- is the only backend.\n'
             '    # That is already what upstream uses on any device without Play\n'
             '    # Services. See docs/gms-removal.md.\n'
             '    deps = [\n'
             '      ":geolocation_jni_headers",\n'
             '      "//base:base_java",\n'),
            ('      "//third_party/android_deps:chromium_play_services_availability_java",\n',
             ""),
        ],
        done_marker="Cobalt ships no Google Play Services")


JNI_SOURCES_OLD = (
    '      "android/java/src/org/chromium/device/geolocation/LocationProviderAdapter.java",\n'
    '      "android/java/src/org/chromium/device/geolocation/LocationProviderFactory.java",\n'
    '    ]\n'
    '  }\n'
    '\n'
    '  android_library("geolocation_java") {\n'
)

JNI_SOURCES_NEW = (
    '      # LocationProviderFactory has no @CalledByNative methods left -- the\n'
    '      # GMS Core opt-in was the only one -- and jni_zero fails outright on\n'
    '      # a file with none.\n'
    '      "android/java/src/org/chromium/device/geolocation/LocationProviderAdapter.java",\n'
    '    ]\n'
    '  }\n'
    '\n'
    '  android_library("geolocation_java") {\n'
)


def patch_jni_headers() -> str:
    """Stop generating JNI for a class that no longer has any.

    `useGmsCoreLocationProvider()` was LocationProviderFactory's only
    @CalledByNative method, and jni_zero refuses a file with none:

        No native methods found in .../LocationProviderFactory.java

    Nothing includes the generated LocationProviderFactory_jni.h any more --
    geolocation_provider_impl.cc held the only reference and this patch removed
    it. LocationProviderAdapter still has natives and stays.
    """
    return edit("services/device/geolocation/BUILD.gn",
                [(JNI_SOURCES_OLD, JNI_SOURCES_NEW)],
                done_marker="jni_zero fails outright")


def patch_factory() -> str:
    return edit(
        f"{JAVA}/LocationProviderFactory.java",
        [
            ('''public class LocationProviderFactory {
    private static @Nullable LocationProvider sProviderImpl;
    private static boolean sUseGmsCoreLocationProvider;

    private LocationProviderFactory() {}
''',
             '''public class LocationProviderFactory {
    private static @Nullable LocationProvider sProviderImpl;

    private LocationProviderFactory() {}
'''),
            ('''    @CalledByNative
    public static void useGmsCoreLocationProvider() {
        sUseGmsCoreLocationProvider = true;
    }

    public static LocationProvider create() {
        if (sProviderImpl != null) return sProviderImpl;

        if (sUseGmsCoreLocationProvider
                && LocationProviderGmsCore.isGooglePlayServicesAvailable(
                        ContextUtils.getApplicationContext())) {
            sProviderImpl = new LocationProviderGmsCore(ContextUtils.getApplicationContext());
        } else {
            sProviderImpl = new LocationProviderAndroid();
        }
        return sProviderImpl;
    }
''',
             '''    public static LocationProvider create() {
        if (sProviderImpl != null) return sProviderImpl;

        // Always the AOSP backend. Cobalt ships no Google Play Services
        // (decision 0013), so the GmsCore branch was unreachable and its
        // implementation is gone. LocationProviderAndroid uses
        // android.location.LocationManager, which is what upstream already
        // falls back to on every device without Play Services.
        //
        // Note this removes Cobalt's dependency on Google, not the device's:
        // what LocationManager resolves to is the operating system's business.
        sProviderImpl = new LocationProviderAndroid();
        return sProviderImpl;
    }
'''),
            # Both imports were only used by the code just removed, and an
            # unused import is an error under Chromium's Java checks.
            ('import org.jni_zero.CalledByNative;\n', ''),
            ('import org.chromium.base.ContextUtils;\n', ''),
        ],
        done_marker="Always the AOSP backend")


def patch_provider_impl() -> str:
    """Drop the JNI call, since the Java method it targets is gone.

    The `use_gms_core_location_provider` parameter itself is left in place. It
    is threaded from //content and //chrome, and removing it would spread this
    patch across three more directories for no gain -- it is now simply never
    acted on, and says so.
    """
    return edit(
        "services/device/geolocation/geolocation_provider_impl.cc",
        [
            ('''#if BUILDFLAG(IS_ANDROID)
#include "base/android/jni_android.h"
#include "services/device/geolocation/geolocation_jni_headers/LocationProviderFactory_jni.h"
#endif
''',
             ''),
            ('''  if (use_gms_core_location_provider) {
#if BUILDFLAG(IS_ANDROID)
    JNIEnv* env = base::android::AttachCurrentThread();
    Java_LocationProviderFactory_useGmsCoreLocationProvider(env);
#else
    NOTREACHED() << "GMS core location provider is only available for Android";
#endif
  }
''',
            '''  // Cobalt ships no Google Play Services (decision 0013), so there is no GMS
  // Core location provider to select: LocationProviderFactory always builds
  // LocationProviderAndroid over android.location.LocationManager.
  //
  // The parameter stays because //content and //chrome thread it through, and
  // removing it would spread this change across three more directories to
  // delete a value nothing reads.
  (void)use_gms_core_location_provider;
''',
            ),
        ],
        done_marker="there is no GMS\n  // Core location provider")


def delete_gms_provider() -> str:
    removed = []
    for rel in DELETE:
        path = SRC / rel
        if path.exists():
            path.unlink()
            removed.append(Path(rel).name)
    if not removed:
        return "already applied"
    return "removed " + ", ".join(removed)


STEPS = [
    ("services/device/geolocation/BUILD.gn", patch_build_gn),
    ("BUILD.gn: drop the factory from generate_jni", patch_jni_headers),
    ("LocationProviderFactory.java (always AOSP)", patch_factory),
    ("geolocation_provider_impl.cc (drop the JNI call)", patch_provider_impl),
    ("delete LocationProviderGmsCore.java", delete_gms_provider),
]


def main() -> int:
    if not (SRC / "services/device/geolocation/BUILD.gn").exists():
        print(f"not a chromium checkout: {SRC}", file=sys.stderr)
        return 1

    failures = 0
    for name, fn in STEPS:
        try:
            result = fn()
        except Failed as exc:
            print(f"  {name:<56} FAILED  {exc}")
            failures += 1
        else:
            print(f"  {name:<56} {result}")

    if failures:
        print(f"\n{failures} step(s) failed; tree is partially patched",
              file=sys.stderr)
        return 1
    print("\nGeolocation runs on android.location.LocationManager; "
          "the fused provider is gone.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

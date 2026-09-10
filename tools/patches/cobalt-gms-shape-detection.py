#!/usr/bin/env python3
"""Take Google Play Services out of //services/shape_detection.

First concrete piece of decision 0013. It removes two of the eighteen GMS
modules in the shipped APK -- `vision` and `vision_common` -- outright, and it
is the cleanest one to do first because upstream already ships the code path
Cobalt needs.

## The Shape Detection API, and what it is made of on Android

Three mojo services, backed by different things:

    FaceDetection     FaceDetectionImpl        android.media.FaceDetector, AOSP
                      FaceDetectionImplGmsCore com.google.android.gms.vision.face
    BarcodeDetection  BarcodeDetectionImpl     com.google.android.gms.vision.barcode
    TextDetection     TextDetectionImpl        com.google.android.gms.vision.text

Face detection has a real AOSP implementation. Barcode and text detection have
none: on Android they are GMS or they are nothing.

## Why this is small

Upstream already handles a device with no Play Services, because plenty of
devices have none. `FaceDetectionProviderImpl` picks between the two face
implementations on `ChromiumPlayServicesAvailability`, and both
`BarcodeDetectionProviderImpl.create()` and `TextDetectionImpl.create()` return
null when it is absent -- whereupon `InterfaceRegistrar` closes the message
pipe, and the renderer sees the service as unavailable. That is a supported,
exercised state, not an error path.

So Cobalt is not inventing degraded behaviour. It is hardcoding the branch
upstream already takes on every GMS-less device, and deleting the other one.
**Behaviour on a device without Play Services is unchanged; behaviour on a
device with them now matches it.**

GrapheneOS reached the same place from a different direction: Vanadium disabled
barcode and text detection precisely because they load Play Services dynamite
modules. Two projects, independent reasons, same conclusion -- which is the
corroboration test decision 0017 sets out, applied to a product change rather
than a security fix.

## What Cobalt loses

`BarcodeDetector` and `TextDetector` report unsupported. Both are non-standard
and Chromium-only; the Shape Detection API never became a web standard, and
`FaceDetector` -- the one with the AOSP backend -- keeps working.

## What is deliberately not touched

`services_javatests` and `services_junit_tests` still list
BarcodeDetectionImplTest, TextDetectionImplTest and BitmapUtilsTest, which
reference classes this removes, so those targets no longer compile. They are
testonly, they are not in chrome_public_apk, and Cobalt does not build them.
Editing them would add delta to files upstream keeps changing for no shipped
benefit. Recorded rather than fixed -- see docs/gms-removal.md.

One real loss inside that: FaceDetectionImplTest is the only coverage of
FaceDetectionImpl, the AOSP path Cobalt now depends on exclusively.

Idempotent; every edit asserts.
"""
import sys
from pathlib import Path

SRC = Path(sys.argv[1] if len(sys.argv) > 1 else "/opt/cobalt/chromium/m140/src")

JAVA = "services/shape_detection/android/java/src/org/chromium/shape_detection"

# GMS-only implementations. Nothing outside the test targets references them.
DELETE = [
    f"{JAVA}/BarcodeDetectionImpl.java",
    f"{JAVA}/BarcodeDetectionProviderImpl.java",
    f"{JAVA}/FaceDetectionImplGmsCore.java",
    f"{JAVA}/TextDetectionImpl.java",
]


class Failed(Exception):
    pass


def edit(rel: str, pairs, *, done_marker: str) -> str:
    """Apply (old, new) replacements to one file, all or nothing."""
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
    # Chromium sources are LF. Writing CRLF here breaks the build on Linux and
    # has already cost this project a day; write bytes rather than trust the
    # platform default.
    path.write_bytes(text.replace("\r\n", "\n").encode("utf-8"))
    return "patched"


# ------------------------------------------------------------ 1. the target


def patch_build_gn() -> str:
    return edit(
        "services/shape_detection/BUILD.gn",
        [
            # Sources: keep only what does not need GMS.
            ('''    sources = [
      "android/java/src/org/chromium/shape_detection/BarcodeDetectionImpl.java",
      "android/java/src/org/chromium/shape_detection/BarcodeDetectionProviderImpl.java",
      "android/java/src/org/chromium/shape_detection/BitmapUtils.java",
      "android/java/src/org/chromium/shape_detection/FaceDetectionImpl.java",
      "android/java/src/org/chromium/shape_detection/FaceDetectionImplGmsCore.java",
      "android/java/src/org/chromium/shape_detection/FaceDetectionProviderImpl.java",
      "android/java/src/org/chromium/shape_detection/InterfaceRegistrar.java",
      "android/java/src/org/chromium/shape_detection/TextDetectionImpl.java",
    ]
''',
             '''    # Cobalt ships no Google Play Services (decision 0013). Barcode and text
    # detection exist only as GMS vision wrappers on Android, so they are gone
    # and their services report unavailable -- the same state upstream reaches
    # on any device without Play Services. Face detection keeps working through
    # android.media.FaceDetector.
    sources = [
      "android/java/src/org/chromium/shape_detection/BitmapUtils.java",
      "android/java/src/org/chromium/shape_detection/FaceDetectionImpl.java",
      "android/java/src/org/chromium/shape_detection/FaceDetectionProviderImpl.java",
      "android/java/src/org/chromium/shape_detection/InterfaceRegistrar.java",
    ]
'''),
            # Deps: the four vision AARs and the availability helper.
            ('''    deps = [
      "$google_play_services_package:google_play_services_base_java",
      "$google_play_services_package:google_play_services_basement_java",
      "$google_play_services_package:google_play_services_vision_common_java",
      "$google_play_services_package:google_play_services_vision_java",
      "//base:base_java",
''',
             '''    deps = [
      "//base:base_java",
'''),
            ('      "//third_party/android_deps:chromium_play_services_availability_java",\n',
             ""),
        ],
        done_marker="Cobalt ships no Google Play Services")


# ------------------------------------------------------------ 2. the registrar


def patch_registrar() -> str:
    return edit(
        f"{JAVA}/InterfaceRegistrar.java",
        [
            ('''import org.chromium.mojo.system.MessagePipeHandle;
import org.chromium.mojo.system.impl.CoreImpl;
import org.chromium.shape_detection.mojom.BarcodeDetectionProvider;
import org.chromium.shape_detection.mojom.FaceDetectionProvider;
import org.chromium.shape_detection.mojom.TextDetection;
''',
             '''import org.chromium.mojo.system.MessagePipeHandle;
import org.chromium.mojo.system.impl.CoreImpl;
import org.chromium.shape_detection.mojom.FaceDetectionProvider;
'''),
            ('''    @CalledByNative
    static void bindBarcodeDetectionProvider(long nativeHandle) {
        // Immediately wrap |nativeHandle| as it cannot be allowed to leak.
        MessagePipeHandle handle = messagePipeHandleFromNative(nativeHandle);

        BarcodeDetectionProvider impl = BarcodeDetectionProviderImpl.create();
        if (impl == null) {
            handle.close();
            return;
        }

        BarcodeDetectionProvider.MANAGER.bind(impl, handle);
    }
''',
             '''    @CalledByNative
    static void bindBarcodeDetectionProvider(long nativeHandle) {
        // Immediately wrap |nativeHandle| as it cannot be allowed to leak.
        MessagePipeHandle handle = messagePipeHandleFromNative(nativeHandle);

        // Cobalt has no Google Play Services (decision 0013), and barcode
        // detection on Android has no other implementation. Closing the pipe is
        // exactly what upstream does here when the provider declines to build
        // itself, so the renderer already knows how to read it.
        handle.close();
    }
'''),
            ('''    @CalledByNative
    static void bindTextDetection(long nativeHandle) {
        // Immediately wrap |nativeHandle| as it cannot be allowed to leak.
        MessagePipeHandle handle = messagePipeHandleFromNative(nativeHandle);

        TextDetection impl = TextDetectionImpl.create();
        if (impl == null) {
            handle.close();
            return;
        }

        TextDetection.MANAGER.bind(impl, handle);
    }
''',
             '''    @CalledByNative
    static void bindTextDetection(long nativeHandle) {
        // Immediately wrap |nativeHandle| as it cannot be allowed to leak.
        MessagePipeHandle handle = messagePipeHandleFromNative(nativeHandle);

        // No Google Play Services, and no other text recogniser on Android.
        // See bindBarcodeDetectionProvider above.
        handle.close();
    }
'''),
        ],
        done_marker="Cobalt has no Google Play Services")


# ------------------------------------------------------------ 3. face provider


def patch_face_provider() -> str:
    return edit(
        f"{JAVA}/FaceDetectionProviderImpl.java",
        [
            ('''import org.chromium.base.ContextUtils;
import org.chromium.gms.ChromiumPlayServicesAvailability;
import org.chromium.mojo.bindings.InterfaceRequest;
''',
             '''import org.chromium.mojo.bindings.InterfaceRequest;
'''),
            ('''    @Override
    public void createFaceDetection(
            InterfaceRequest<FaceDetection> request, FaceDetectorOptions options) {
        final boolean isGmsCoreSupported =
                ChromiumPlayServicesAvailability.isGooglePlayServicesAvailable(
                        ContextUtils.getApplicationContext());

        if (isGmsCoreSupported) {
            FaceDetection.MANAGER.bind(new FaceDetectionImplGmsCore(options), request);
        } else {
            FaceDetection.MANAGER.bind(new FaceDetectionImpl(options), request);
        }
    }
''',
             '''    @Override
    public void createFaceDetection(
            InterfaceRequest<FaceDetection> request, FaceDetectorOptions options) {
        // Always the AOSP backend: Cobalt ships no Google Play Services
        // (decision 0013), so the GmsCore branch was unreachable and its
        // implementation is gone. android.media.FaceDetector has been in
        // Android since API 1.
        FaceDetection.MANAGER.bind(new FaceDetectionImpl(options), request);
    }
'''),
        ],
        done_marker="Always the AOSP backend")


# ------------------------------------------------------------ 4. bitmap utils


def patch_bitmap_utils() -> str:
    return edit(
        f"{JAVA}/BitmapUtils.java",
        [
            ('''import android.graphics.Bitmap;

import com.google.android.gms.vision.Frame;

import org.chromium.mojo_base.BigBufferUtil;
''',
             '''import android.graphics.Bitmap;

import org.chromium.mojo_base.BigBufferUtil;
'''),
            ('/** Utility class to convert a Bitmap to a GMS core YUV Frame. */\n',
             '/** Utility class to turn a mojo BitmapN32 into an android.graphics.Bitmap. */\n'),
            # convertToFrame produced the GMS vision Frame and had exactly one
            # caller class per GMS detector; all of them are gone.
            ('''
    public static @Nullable Frame convertToFrame(org.chromium.skia.mojom.BitmapN32 bitmapData) {
        Bitmap bitmap = convertToBitmap(bitmapData);
        if (bitmap == null) {
            return null;
        }

        // This constructor implies a pixel format conversion to YUV.
        return new Frame.Builder().setBitmap(bitmap).build();
    }
''',
             ''),
        ],
        done_marker="turn a mojo BitmapN32 into")


# ------------------------------------------------------------ 5. delete


def delete_gms_impls() -> str:
    removed, already = [], []
    for rel in DELETE:
        path = SRC / rel
        if path.exists():
            path.unlink()
            removed.append(Path(rel).name)
        else:
            already.append(Path(rel).name)
    if not removed:
        return f"already applied ({len(already)} gone)"
    return "removed " + ", ".join(removed)


# ------------------------------------------------ 6. the last vision referrer

VISION_DEPS = (
    '      "$google_play_services_package:google_play_services_tasks_java",\n'
    '      "$google_play_services_package:google_play_services_vision_common_java",\n'
    '      "$google_play_services_package:google_play_services_vision_java",\n'
)

VISION_GONE = (
    '      "$google_play_services_package:google_play_services_tasks_java",\n'
    '\n'
    '      # The vision AARs were listed here but nothing under chrome/ imports\n'
    '      # com.google.android.gms.vision. Removed alongside shape_detection\'s\n'
    '      # own vision deps (decision 0013) so the modules leave the APK.\n'
)


def patch_chrome_java() -> str:
    """chrome_java lists the vision AARs but no longer uses them.

    Nothing under chrome/ imports com.google.android.gms.vision -- verified by
    grep across every .java in the tree. These two lines are the only thing
    keeping vision and vision_common in the APK once shape_detection stops
    asking for them, and dropping them changes no Java at all.

    Decision 0014 puts chrome/android last on purpose, and this does not jump
    that queue: it is a dead dependency edge, not the UI-layer work.
    """
    return edit("chrome/android/BUILD.gn",
                [(VISION_DEPS, VISION_GONE)],
                done_marker="nothing under chrome/ imports")


STEPS = [
    ("services/shape_detection/BUILD.gn", patch_build_gn),
    ("InterfaceRegistrar.java (barcode + text unavailable)", patch_registrar),
    ("FaceDetectionProviderImpl.java (always AOSP)", patch_face_provider),
    ("BitmapUtils.java (drop the GMS Frame conversion)", patch_bitmap_utils),
    ("delete the GMS-only implementations", delete_gms_impls),
    ("chrome/android/BUILD.gn (drop the stale vision deps)", patch_chrome_java),
]


def main() -> int:
    if not (SRC / "services/shape_detection/BUILD.gn").exists():
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
    print("\nshape_detection builds without Play Services; "
          "vision and vision_common leave the graph.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

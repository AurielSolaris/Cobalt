# Removing Google Play Services

Release gate 2 of 3. Where it stands: **started, 4 of 18 modules gone.**

Decision: [0013](decisions/0013-remove-google-play-services.md).
Order: [0014](decisions/0014-gms-removal-before-shell.md).

## How much is left, and how to know

```
tools/build/gms-inventory.sh            # summary
tools/build/gms-inventory.sh --verbose  # include test-only referrers
```

It reads the real dependency graph of `chrome_public_apk` with `gn`, not a grep
over the tree. That distinction matters more than it sounds: **32 `BUILD.gn`
files mention `google_play_services`, but most belong to targets Cobalt never
builds.** Counting those makes the job look larger than it is while hiding which
parts are load-bearing. Decision 0013 quotes the 32 figure, which was the right
number for the question it was asking and the wrong one for planning the work.

**Done is `MODULES 0`.**

### Starting position

| | |
|---|---:|
| GMS modules in the APK | **18** |
| of which nothing first-party asks for | 5 |
| first-party dependency edges | 49 |
| non-test Java files importing `com.google.android.gms` | 81 |

The 18 modules are more than 0013's list of 10 — that list came from
`chrome/android/BUILD.gn` alone. In full: `auth_api_phone`, `auth_base`, `base`,
`basement`, `cast`, `cast_framework`, `clearcut`, `cloud_messaging`, `flags`,
`gcm`, `identity_credentials`, `iid`, `location`, `phenotype`, `stats`, `tasks`,
`vision`, `vision_common`.

**Five have no first-party referrer at all** — `clearcut`, `cloud_messaging`,
`flags`, `phenotype`, `stats`. They arrive underneath other AARs and leave for
free. So the work is 13 modules, not 18.

### Now

| | Then | Now |
|---|---:|---:|
| Modules | 18 | **14** |
| First-party edges | 49 | **43** |

## The method

Two techniques, and the choice between them is per-target.

**1. Take upstream's own no-GMS path.** Chromium runs on devices without Play
Services and has to, so many subsystems already carry a fallback or a graceful
"unavailable" branch behind `ChromiumPlayServicesAvailability`. Where one
exists, Cobalt hardcodes it and deletes the other branch. Behaviour on a
GMS-less device does not change at all; behaviour everywhere else becomes what
a GMS-less device already had.

This is the preferred technique because the surviving code is *upstream's*,
exercised by real users, rather than something Cobalt invented.

**2. Swap the file for a stub, in `BUILD.gn`.** Where no fallback exists, the
approach used by ungoogled-chromium-android: drop the `.java` from the target's
`sources` and add a Cobalt-owned replacement, rather than editing upstream's
file in place — csagan5's phrase for the alternative was "rebase hell with each
release". Not needed yet; recorded so the first case does not re-derive it.

Either way: **deleting a file is the cheapest kind of rebase conflict** to
resolve, and editing one is the most expensive. Prefer deletion; keep the edits
that remain small and marked.

## Corroboration

Every fork that ships without Play Services has solved this already, and
[decision 0017](decisions/0017-corroborate-security-fixes-across-forks.md)'s
reasoning applies to product changes as well as security fixes: independent
projects reaching the same conclusion is evidence.

- **Vanadium** (GrapheneOS) — ships on an OS that will never have Play Services
  or microG. It disabled barcode and text detection specifically because they
  load Play Services *dynamite modules*, a different reason from Cobalt's and
  the same outcome.
- **Cromite** — an Android Chromium fork with a large patch series, closest to
  Cobalt in shape.
- **ungoogled-chromium-android** — where the stub-swap technique above comes
  from, along with its guiding constraint: "as few changes as possible so future
  updates won't become a huge pain".

None of them hands Cobalt a patch, for the same reason none of them hands us a
security backport: they all track forward. The value is knowing what is safe to
remove and what breaks.

## Order of work

From [0014](decisions/0014-gms-removal-before-shell.md), amended: passwords move
*after* the removal rather than leading it, because the password work is
specified as GMS-free ([0015](decisions/0015-passwords-local-store-and-system-autofill.md))
and doing it first would mean building on a layer about to change underneath it.

1. ~~`services/shape_detection` — vision, vision_common~~ **done**
2. ~~`services/device/geolocation` — location, tasks, base, basement~~ **done**
3. `components/media_router` — cast, cast_framework (0013: casting is dropped)
4. `components/gcm_driver`, `components/signin`, `components/externalauth`,
   `components/module_installer`, `components/webauthn`, `components/omnibox`
5. `content/public/android` — auth_api_phone, base, basement, tasks
6. `chrome/browser/*` — omaha, password_manager, webid, webauthn, language,
   ui/android/omnibox
7. `chrome/android` — last, and partly moot by then
8. **Passwords** ([0015](decisions/0015-passwords-local-store-and-system-autofill.md))
   on the resulting GMS-free tree
9. The telemetry notice and its endpoints — removed with the code, never before
   it (0013)

Safe Browsing stays throughout: an HTTPS service, not a GMS library.

## 1. `services/shape_detection` — done

Applied by `tools/patches/cobalt-gms-shape-detection.py`, in the series.
Removed `vision` and `vision_common`; `clearcut` and `phenotype` fell out
underneath them. **18 → 14.**

The Shape Detection API is three mojo services with different backends:

| Service | Backend |
|---|---|
| `FaceDetection` | `android.media.FaceDetector` (AOSP) **or** `gms.vision.face` |
| `BarcodeDetection` | `gms.vision.barcode` only |
| `TextDetection` | `gms.vision.text` only |

Face detection has a real AOSP implementation and keeps working. Barcode and
text detection are GMS or nothing, so they now report unavailable — by
`InterfaceRegistrar` closing the message pipe, which is *upstream's own code
path* for a provider that declines to construct itself. Both are non-standard
and Chromium-only; the Shape Detection API never became a web standard.

`chrome/android/BUILD.gn` also listed the two vision AARs, and nothing under
`chrome/` imports `com.google.android.gms.vision` — a dead dependency edge, and
the last thing keeping the modules in the APK. Removing it does not jump 0014's
queue, because it changes no Java.

### Left broken on purpose (shape_detection)

`services_javatests` and `services_junit_tests` still list
`BarcodeDetectionImplTest`, `TextDetectionImplTest` and `BitmapUtilsTest`, which
reference removed classes, so those targets no longer compile. They are
testonly, not in `chrome_public_apk`, and Cobalt does not build them.

One real loss inside that: `FaceDetectionImplTest` is the only coverage of
`FaceDetectionImpl`, the AOSP path Cobalt now depends on exclusively. Worth
restoring if the test targets are ever brought back.

## 2. `services/device/geolocation` — done

Applied by `tools/patches/cobalt-gms-geolocation.py`, in the series.
**Edges 47 → 43**; modules stay at 14, because `location` and `tasks` keep
referrers in `components/omnibox` and `chrome/browser/ui/android/omnibox`.

Same shape as shape_detection. `LocationProviderFactory.create()` already chose
between `LocationProviderGmsCore` (the fused provider) and
`LocationProviderAndroid` (`android.location.LocationManager`, plain AOSP) on
`ChromiumPlayServicesAvailability`, with a second gate above it —
`use_gms_core_location_provider`, threaded from
`ContentBrowserClient::ShouldUseGmsCoreGeolocationProvider()`. The GMS branch is
deleted and the AOSP one is now unconditional.

### The Google endpoint that turned out not to be there

`network_location_request.cc` posts WiFi and cell observations to
`https://www.googleapis.com/geolocation/v1/geolocate` — exactly the Google
*service* vs GMS *library* call 0013 has to make for Safe Browsing.

**It does not apply.** `network_location_provider.cc` and
`wifi_data_provider_common.cc` sit in the `else` branch of the BUILD.gn, not
`is_android`. Chromium on Android delegates entirely to the platform and never
runs that path. Checked rather than assumed, because the opposite would have
been a much bigger finding.

### Two things the build caught that reading the Java did not

Removing `useGmsCoreLocationProvider()` left `LocationProviderFactory` with no
`@CalledByNative` methods at all, and `jni_zero` refuses such a file outright:

```
No native methods found in .../LocationProviderFactory.java
```

So the class also had to leave `generate_jni("geolocation_jni_headers")`.
`LocationProviderAdapter` still has natives and stays; nothing includes the
generated `LocationProviderFactory_jni.h` any more, because
`geolocation_provider_impl.cc` held the only reference and this patch removed
it. The unused `CalledByNative` and `ContextUtils` imports had to go too — an
unused import is an error under Chromium's Java checks.

Both surfaced as hard failures at step ~330 of 86,709, which is the argument for
every series step asserting.

### Verified on device

The dex check is inconclusive here — R8 minifies these class names — so the
verification is behavioural. With `enableHighAccuracy: true`,
`dumpsys location` shows Cobalt itself driving the GPS provider:

```
gps provider:
  service: ProviderRequest[@0, HIGH_ACCURACY, WorkSource{10670 app.auriel.cobalt}]
  mStarted=true   (changed +5s355ms ago)
```

That is `navigator.geolocation` → `LocationProviderAndroid` → `LocationManager`
→ GPS, with no Play Services anywhere in it.

The request still timed out, and **that is the OS, not Cobalt**. The test device
has no `network` location provider at all — only `passive`, `fused` and `gps` —
so a coarse request (`enableHighAccuracy: false`) has nothing to service it and
a fine one needs a satellite fix. Which is the caveat worth repeating: removing
GMS from Cobalt removes *Cobalt's* dependency, not the device's. What
`LocationManager` resolves to is the operating system's business, and a
de-Googled OS with no network location backend gives Cobalt GPS or nothing.

### Left broken on purpose (geolocation)

`LocationProviderTest.java` (junit) references `LocationProviderGmsCore` and no
longer compiles. Testonly, not in `chrome_public_apk`, not built by Cobalt —
same call as shape_detection's.

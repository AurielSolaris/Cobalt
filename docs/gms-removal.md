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
| First-party edges | 49 | **47** |

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
2. `services/device/geolocation` — location, tasks, base, basement
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

### Left broken on purpose

`services_javatests` and `services_junit_tests` still list
`BarcodeDetectionImplTest`, `TextDetectionImplTest` and `BitmapUtilsTest`, which
reference removed classes, so those targets no longer compile. They are
testonly, not in `chrome_public_apk`, and Cobalt does not build them.

One real loss inside that: `FaceDetectionImplTest` is the only coverage of
`FaceDetectionImpl`, the AOSP path Cobalt now depends on exclusively. Worth
restoring if the test targets are ever brought back.

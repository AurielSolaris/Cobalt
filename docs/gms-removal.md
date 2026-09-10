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

| | Then | Now (measured 2026-09-11) |
|---|---:|---:|
| Modules reaching the APK | 18 | **18** |
| ...with a first-party user | — | **13** |
| First-party edges | 49 | **43** |

**The "Now" column is what `tools/build/gms-inventory.sh` prints today, and it
does not match what this table said before (14 modules, 32 edges).** Part of the
gap is known: three `chrome_java` dead-dep removals were recorded as done and
then reverted when they broke `chrome_public_apk` — `language`,
`module_installer` and `externalauth` are all back in the inventory. That
accounts for a few edges, not eleven, and no attempt is made here to reconstruct
the rest. The earlier figures are treated as superseded rather than reconciled,
because a number nobody can reproduce is worse than one that is merely
disappointing. Re-run the script; believe the script.

The five modules with no first-party user at all — `clearcut`,
`cloud_messaging`, `flags`, `phenotype`, `stats` — arrive purely transitively
and will fall out when their referrers do.

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
3. `components/media_router` — cast, cast_framework (0013: casting is dropped).
   **Bigger than it looks — see below.**
4. ~~`components/externalauth`~~, ~~`components/module_installer`~~ **done**;
   `components/gcm_driver`, `components/signin`, `components/webauthn`,
   `components/omnibox`
5. `content/public/android` — auth_api_phone, base, basement, tasks
6. `chrome/browser/*` — ~~omaha~~, ~~webauthn~~, ~~language~~ **done**;
   password_manager, webid, ui/android/omnibox
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

## 3. `chrome_java`'s dead dependency lines — done

Applied by `tools/patches/cobalt-gms-chrome-java-dead-deps.py`.
**Edges 43 → 38**, no Java changes at all.

`chrome_java` declared eight GMS modules. Its own sources import three:

```
gms.cast   0 files      gms.gcm     3 files
gms.iid    0 files      gms.common  1 file
gms.auth   0 files
gms.tasks  0 files
```

Grep alone is not proof — a Java target legitimately needs a dependency it never
imports when a library it *uses* exposes those types in its public API, and
that is a plausible reason for exactly these five. So it was tested:
`auth_base`, `cast`, `cast_framework`, `iid` and `tasks` were removed and
`chrome_java` rebuilt clean.

`base`, `basement` and `gcm` stay. Those imports are real, and removing them is
0013's unanswered Web Push question rather than a tidy-up.

The module count does not move: all five keep referrers elsewhere. This removes
five of the edges holding them in, which is what has to happen first.

## 4. `components/media_router` — surveyed, not attempted

Casting is dropped by 0013, so this looked like the next clean cut. It is not,
and the reason is worth recording before someone picks it up expecting an
afternoon.

**There is no `enable_media_router` GN flag** in M140 — searched the whole tree.
Media Router is unconditional, so there is no supported "build without casting".

The Java splits into a framework layer and a Cast Application Framework layer:

```
org/chromium/components/media_router/*.java        23 files, the framework
org/chromium/components/media_router/caf/**        17 files, all GMS
```

`BrowserMediaRouter.addProviders()` has the same clean shape as shape detection
and geolocation — it checks `GoogleApiAvailability` and, without Play Services,
**registers no providers at all**. So the entry point is a one-line change.

What breaks the pattern is that **three framework files are Cast-typed too**:
`MediaSink` holds a `gms.cast.CastDevice`, `MediaStatusBridge` is explicitly "a
wrapper layer that exposes a gms.cast.MediaStatus to native code", and both are
referenced across the framework — `MediaRouteManager`, `DiscoveryDelegate`,
`FlingingControllerBridge`, the dialog managers. Deleting `caf/` is not enough;
the framework has GMS types in its own vocabulary.

And `//components/media_router/browser/android:java` is pulled by
`chrome/android:chrome_java`, so the fallout lands in the layer
[0014](decisions/0014-gms-removal-before-shell.md) defers to last.

**Not a quick win.** It is either a genuine refactor of the framework layer to
drop Cast types, or a removal that reaches into `chrome/android` — and the
second is better done when `chrome/android` comes up in the order anyway,
possibly alongside the shell that replaces much of it.

## 5. `components/externalauth` — done

Applied by `tools/patches/cobalt-gms-externalauth.py`. **Edges 38 → 36**, and
worth more than that number suggests.

`ExternalAuthUtils.canUseGooglePlayServices()` is *the* question the rest of the
tree asks before doing anything GMS-shaped — **fourteen non-test call sites**.
Answering `false` unconditionally makes every one of them take the path upstream
already ships for a device without Play Services, *before* their own removals
are written. The behaviour becomes the target behaviour and only the dependency
is left, which makes everything after it safer.

Five methods stop consulting GMS:

| Method | Now |
|---|---|
| `canUseGooglePlayServices(errorHandler)` | `false` |
| `canUseGooglePlayServices()` | `false` |
| `canUseFirstPartyGooglePlayServices(...)` | `false` |
| `isGooglePlayServicesMissing(context)` | `true` |
| `checkGooglePlayServicesAvailable` / `isUserRecoverableError` / `describeError` | deleted |

Those last three were `protected` hooks for subclasses, and they could not
survive in any form: their signatures traffic in `ConnectionResult` codes and
their bodies are `GoogleApiAvailability` calls, so they *are* the dependency.
Nothing overrides them outside one test class.

The `UserRecoverableErrorHandler` parameter stays. It exists to offer the user a
way to repair a *recoverable* Play Services problem, and no such journey exists
here — but keeping it avoids editing fourteen call sites to delete an argument
none of them will miss.

**Not touched, though it looks like it belongs:** `isGoogleSigned` and
`isChromeGoogleSigned` go through `mGoogleDelegate`, which is null in upstream
Chromium and exists as a downstream hook. No GMS, no change.

**One behaviour worth stating:** `SigninManagerImpl` asks
`isGooglePlayServicesMissing()` and now always hears `true`. That is correct and
it is the point — sign-in is dropped by 0013, and on a Cobalt device the honest
answer is yes.

Five imports were left holding nothing once the hooks went, and Chromium's Java
checks treat an unused import as an error, so they went too. `Log` stays;
`isSystemBuild` still uses it.

## 6. Four one-edge targets — done

Applied by `tools/patches/cobalt-gms-singles.py`. **Edges 36 → 32.** Grouped
because they are the same size and shape, and because two of them were nothing
at all.

| Target | Module | What it actually was |
|---|---|---|
| `chrome/browser/language/android` | `tasks` | **dead** — nothing there imports GMS |
| `components/module_installer/android` | `tasks` | **dead** in the library; only the junit test imports GMS |
| `chrome/browser/omaha/android` | `base` | one string constant |
| `chrome/browser/webauthn/android` | `tasks` | code that was already unreachable |

### omaha: a constant, and a check that was already wrong

`UpdateStatusProvider` gates "update available" on the Play Store being
installed, via `GooglePlayServicesUtil.GOOGLE_PLAY_STORE_PACKAGE` — which is the
string `"com.android.vending"`. Inlining it removes the target's only GMS
dependency and changes nothing.

**The check itself is left alone**, though it is already wrong for Cobalt, which
is not distributed through the Play Store. How Cobalt updates is a product
decision nobody has made, 0013 does not cover it, and changing update behaviour
inside a dependency patch would be smuggling a decision in under a
bookkeeping change. Recorded, not acted on.

### webauthn: the externalauth leverage, immediately

`CableAuthenticatorModuleProvider.getLinkingInformation()` opens by asking
`ExternalAuthUtils.canUseFirstPartyGooglePlayServices()` and returning null if
it says no. The previous patch made that `false` unconditionally, so the entire
`Fido2ApiCall` block below the guard **was already unreachable** — the method
already returned null on every call. Deleting it takes `gms.tasks.Task` with it.

That is the argument for having done `externalauth` first, arriving one patch
later than the claim: it converts downstream GMS paths into dead code *before*
anyone has to reason about what removing them would break, because the answer is
already "nothing, it never ran".

Two imports were orphaned by that deletion — `Parcel` and `Fido2ApiCall` — and
an unused import is an error under Chromium's Java checks.

# Device APIs: what Cobalt exposes, and what it does not

Applied by `tools/patches/cobalt-disable-device-apis.py`, in the series.

Each of these hands a web page access to hardware. On the target device — 4 GB,
two cores ([decision 0004](decisions/0004-performance-budget.md)) — they are
attack surface and fingerprinting surface for capabilities almost no site uses.
None is load-bearing for browsing.

## State

All of this is **verified on a device**, not inferred from the build files:

```
navigator.xr         undefined
navigator.usb        undefined
navigator.hid        undefined
window.NDEFReader    undefined
navigator.bluetooth  object      <- kept, and gated
navigator.serial     object      <- untouched, see below
```

| API | Cobalt | How |
|---|---|---|
| WebXR (`navigator.xr`) | **off** | `kWebXr` disabled **+** `status: {"Android": ""}` |
| WebUSB (`navigator.usb`) | **off** | `kWebUsb` disabled **+** `status: {"Android": ""}` |
| Web NFC (`NDEFReader`) | **off** on Android | `status: {"Android": ""}` |
| WebHID (`navigator.hid`) | off | **upstream already**, no patch |
| Web Bluetooth (`navigator.bluetooth`) | **on, gated** | upstream `BLUETOOTH_GUARD` |
| Web Serial (`navigator.serial`) | present — unresolved | see below |

## Measure, do not read the build files

`args.gn` sets `enable_vr`, `enable_openxr`, `enable_arcore` and
`enable_cardboard` false, and its comment claimed that covered WebXR and that
WebHID was "turned off as a feature default instead". Probed on a device running
that build:

```
navigator.hid                        undefined
navigator.xr                         object (XRSystem)
navigator.usb                        object
navigator.bluetooth                  object
navigator.serial                     object
xr.isSessionSupported("immersive-vr") false
xr.isSessionSupported("immersive-ar") false
xr.isSessionSupported("inline")       true
```

**The GN flags removed WebXR's device backends and left the API.** `navigator.xr`
was still exposed, `XRSystem` was still a constructor, and inline sessions still
ran. Reading the GN files would have said the job was done — the same error that
cost a session on `unlimitedStorage`.

And WebHID was off for a reason that had nothing to do with Cobalt: upstream
declares it `{"Android": "", "default": "stable"}` and never enables it on
Android. That is inherited, not caused, and args.gn's comment has been corrected
rather than left looking like a Cobalt guarantee.

## Two levers, and the one that actually counts

`base_feature: "none"` in `runtime_enabled_features.json5` looks like it means
no `--disable-features` name exists for these. **It does not**, and believing it
cost a full build cycle.

`content/child/runtime_features.cc:295` maps a **separately named** base::Feature
onto the Blink feature:

```c++
{wf::EnableWebUSB, raw_ref(features::kWebUsb)},
{wf::EnableWebXR,  raw_ref(features::kWebXr)},
```

Neither entry carries `kSetOnlyIfOverridden`, so content **unconditionally**
overwrites whatever the json5 status set, using the defaults in
`content/public/common/content_features.cc` — where both were
`FEATURE_ENABLED_BY_DEFAULT`.

Only a device probe could find this. Every static signal agreed the job was
done: the patch applied, the series was green, the build succeeded through
57,765 steps, the generated `runtime_enabled_features.cc` had
`is_web_xr_enabled_ = false` inside the `IS_ANDROID` block, and
`runtime_enabled_features.o` was recompiled against it. **The generated source
was correct and the runtime overwrote it before the
first page loaded.**

Web NFC has neither a `runtime_features.cc` entry nor a base::Feature, which is
exactly why it was the one that worked from the json5 change alone.

So both levers are patched. **The json5 status is kept even where it is not
sufficient on its own** — it is the correct declaration for the platform, and it
becomes the operative one if a rebase ever adds `kSetOnlyIfOverridden` to those
entries, which is the direction upstream has been moving. The failure it guards
against is silent; the cost is two lines.

The json5 value has to be an empty string **inside a platform map** —
`{"Android": "", "default": "stable"}`. A bare `status: ""` is rejected by
`json5_generator.py`, whose `_is_valid` accepts `""` only as a dict value. The
first attempt used a bare one and the build died at step 1 of 23731 with
`Unknown value: ''` — the series failing loudly, as it is meant to. WebHID's own
declaration, a hundred lines away in the same file, is the correct shape and is
the one used. Features declaring
`depends_on` follow automatically, so WebXR takes eleven dependent features with
it and WebUSB takes its worker variants; listing them here would only rot.

## Web Bluetooth stays, and needed nothing

Asked for as "keep it but make it permission gated". It already is — verified in
the source rather than assumed:

- `content_settings_registry.cc` registers `BLUETOOTH_GUARD` for
  `PLATFORM_ANDROID` with **`CONTENT_SETTING_ASK` as the default** and only
  `{ASK, BLOCK}` as valid values, so it cannot be set to blanket-allow.
- `BLUETOOTH_CHOOSER_DATA` holds per-site grants, scoped `TOP_ORIGIN_ONLY` and
  `EXCEPTIONS_ON_SECURE_ORIGINS_ONLY`.
- Android's `SiteSettingsCategory` exposes both `Type.BLUETOOTH` and
  `Type.BLUETOOTH_SCANNING` as user-visible categories, so it is reachable in
  Site settings rather than buried.
- `requestDevice()` additionally requires a user gesture and shows a chooser.

Confirmed at runtime on the device rather than only in the source:

```
navigator.bluetooth.getAvailability()        -> true
navigator.bluetooth.requestDevice({...})     -> SecurityError:
                                                Must be handling a user gesture
```

Adding a Cobalt-specific gate on top would replace a reviewed upstream mechanism
with a worse one. The right work here, if any, is making sure Cobalt's own shell
([0002](decisions/0002-shell-design.md)) surfaces that category — a UI question,
not a platform one.

## Web Serial is unresolved

`navigator.serial` was exposed on the device, but `runtime_enabled_features.json5`
declares Serial `{"Android": "test", "default": "stable"}` — and `test` means web
tests only. The likely explanation is its `base_feature: "WebSerialAPI"`, which
can enable the runtime feature independently of the status field.

Not touched, because it was not asked for and because acting on a mechanism not
yet understood is how the `browserAction` and policy-provider bugs happened.
**Open question:** confirm the base feature is the cause, then decide. If Serial
really is reachable from a page on Android, it belongs in the table above with
the others.

## The generated code is the check, not the source

The json5 change lands in `out/Default/gen/third_party/blink/renderer/platform/runtime_enabled_features.cc`,
which carries one block of initialisers **per platform**. The Android block is
the first one, and after this patch it reads:

```c++
bool RuntimeEnabledFeaturesBase::is_web_hid_enabled_ = false;
bool RuntimeEnabledFeaturesBase::is_web_nfc_enabled_ = false;
bool RuntimeEnabledFeaturesBase::is_web_usb_enabled_ = false;
bool RuntimeEnabledFeaturesBase::is_web_xr_enabled_  = false;
```

Check that file, not the header — the header holds only accessors
(`return is_web_xr_enabled_;`) and does not change when a status does, so its
timestamp is a misleading signal.

`navigator.xr` is `[RuntimeEnabled=WebXR]` and `navigator.usb` is
`[RuntimeEnabled=WebUSB]` in their IDL, so those booleans are exactly the gate.

## Verifying

The same CDP method as
[`tools/device/check-unlimited-storage.py`](../tools/device/check-unlimited-storage.py):
attach to the browser endpoint, navigate a tab, and read the objects off
`navigator`. Per-page WebSocket endpoints do not answer on Android — sessions
have to be opened with `Target.attachToTarget` from `/devtools/browser`.

#!/usr/bin/env python3
"""Remove the device APIs Cobalt does not ship: WebXR, WebUSB, Web NFC.

Each is a large, rarely-used attack and fingerprinting surface on a browser
aimed at 4 GB / two-core hardware (decision 0004). None of them is load-bearing
for browsing the web.

## Measured first, because the GN flags did less than args.gn claimed

`args.gn` sets `enable_vr`, `enable_openxr`, `enable_arcore` and
`enable_cardboard` false, and its comment says WebHID "is turned off as a
feature default instead". On a device running that build:

    navigator.hid          undefined
    navigator.xr           object (XRSystem)
    navigator.usb          object
    navigator.bluetooth    object
    navigator.serial       object
    isSessionSupported("immersive-vr") false
    isSessionSupported("inline")       true

So the GN flags removed WebXR's *device backends* and left the *API* exposed,
inline sessions included. Reading the build files would have said otherwise --
the same mistake unlimitedStorage cost a session.

## What this changes

| Feature  | Was                                        | Now |
|----------|--------------------------------------------|-----|
| WebXR    | `status: "stable"`                         | removed on Android; 11 dependent features go with it |
| WebUSB   | `status: "stable"`                         | removed on Android |
| Web NFC  | `{"Android": "stable", "iOS": "stable", "default": "test"}` | removed on Android |

## What needs nothing, and why saying so matters

**WebHID** is already `{"Android": "", "default": "stable"}` upstream --
Chromium never enables it on Android. `navigator.hid` is undefined, and that is
inherited, not caused. args.gn's comment overstated the project's involvement
and has been corrected rather than left to look like a Cobalt guarantee.

**Web Bluetooth stays, and is already permission-gated** -- verified rather than
assumed. `content_settings_registry.cc` registers `BLUETOOTH_GUARD` for
`PLATFORM_ANDROID` with `CONTENT_SETTING_ASK` as the default and only
{ASK, BLOCK} as valid values, `BLUETOOTH_CHOOSER_DATA` holds per-site grants,
and Android's `SiteSettingsCategory` exposes both it and `BLUETOOTH_SCANNING`
as user-visible categories. `requestDevice()` additionally needs a user gesture
and shows a chooser. There is nothing to add: writing a Cobalt-specific gate
here would replace a reviewed upstream mechanism with a worse one.

**Web Serial** was not asked about and is not touched, but the probe found
`navigator.serial` exposed on Android even though its runtime status is
`{"Android": "test"}` -- its `base_feature: "WebSerialAPI"` can turn it on
independently of the status field. Recorded in docs/device-apis.md as an open
question rather than silently changed.

## Two levers, because the obvious one is not enough

`base_feature: "none"` in runtime_enabled_features.json5 looks like it means no
`--disable-features` name exists. **It does not.** content maps a *separately
named* base::Feature onto the Blink feature:

    content/child/runtime_features.cc:295
        {wf::EnableWebUSB, raw_ref(features::kWebUsb)},
        {wf::EnableWebXR,  raw_ref(features::kWebXr)},

Neither entry carries `kSetOnlyIfOverridden`, so content **unconditionally**
overwrites whatever the json5 status set, from
`content/public/common/content_features.cc`:

    BASE_FEATURE(kWebUsb, "WebUSB", base::FEATURE_ENABLED_BY_DEFAULT);
    BASE_FEATURE(kWebXr,  "WebXR",  base::FEATURE_ENABLED_BY_DEFAULT);

This was found the only way it could have been. The json5 edit alone built
cleanly through 57,765 steps, produced `is_web_xr_enabled_ = false` in the
Android block of the generated code, installed -- and `navigator.xr` was still
an object on the device. The generated source was right; the runtime overwrote
it.

Web NFC has neither a runtime_features.cc entry nor a base::Feature, so for it
the json5 status really is the only lever, and it worked.

**Both levers are applied, and the json5 one is kept even where it is not
sufficient on its own.** It is the correct declaration for the platform, and it
becomes the operative one if a rebase ever adds `kSetOnlyIfOverridden` to those
entries -- upstream has been migrating toward that. The failure it guards
against is silent, and the cost is two lines.

The json5 value has to be an empty string *inside a platform map* --
`{"Android": "", "default": "stable"}`. A bare `status: ""` is rejected by
`json5_generator.py`, whose `_is_valid` accepts `""` only as a dict value; the
first attempt here used a bare one and the build failed at step 1 of 23731,
which is the series working as intended. WebHID's own declaration is the
correct shape, and it is the one used.

Idempotent; every edit asserts.
"""
import sys
from pathlib import Path

SRC = Path(sys.argv[1] if len(sys.argv) > 1 else "/opt/cobalt/chromium/m140/src")

REL = "third_party/blink/renderer/platform/runtime_enabled_features.json5"

# (label, old block, new block, marker that means "already applied")
EDITS = [
    (
        "WebXR",
        '''    {
      name: "WebXR",
      public: true,
      status: "stable",
      base_feature: "none",
    },
''',
        '''    {
      // Cobalt does not ship WebXR. enable_vr / enable_openxr / enable_arcore /
      // enable_cardboard are all false in args.gn, which removes the device
      // backends but leaves navigator.xr exposed and inline sessions working.
      // An empty status for Android removes the API itself, and takes the
      // eleven features that declare depends_on: ["WebXR"] with it.
      //
      // The empty value has to sit inside a platform map: json5_generator's
      // _is_valid accepts "" only as a dict value, and a bare status: "" fails
      // the build. WebHID a few hundred lines up is exactly this shape, which
      // is why it is the shape used here. base_feature: "none" means there is
      // no runtime switch to reach for instead.
      name: "WebXR",
      public: true,
      status: {"Android": "", "default": "stable"},
      base_feature: "none",
    },
''',
        "Cobalt does not ship WebXR",
    ),
    (
        "WebUSB",
        '''    {
      name: "WebUSB",
      public: true,
      status: "stable",
      base_feature: "none",
    },
''',
        '''    {
      // Cobalt does not ship WebUSB: raw USB access from a web page is a large
      // attack surface for a capability essentially no site needs, and the
      // devices this browser targets are not developer workstations.
      // WebUSBOnDedicatedWorkers and WebUSBOnServiceWorkers depend on this and
      // go with it. Same platform-map shape as WebXR above, for the same
      // generator reason.
      name: "WebUSB",
      public: true,
      status: {"Android": "", "default": "stable"},
      base_feature: "none",
    },
''',
        "Cobalt does not ship WebUSB",
    ),
    (
        "Web NFC",
        '''    {
      name: "WebNFC",
      public: true,
      status: {"Android": "stable", "iOS": "stable", "default": "test"},
      base_feature: "none",
    },
''',
        '''    {
      // Cobalt does not ship Web NFC. Android is the only platform where it
      // was on, which is precisely why it has to be turned off here rather
      // than inherited: proximity-triggered reads and writes from a web page
      // are not a tradeoff this browser wants to make on its users' behalf.
      name: "WebNFC",
      public: true,
      status: {"Android": "", "iOS": "stable", "default": "test"},
      base_feature: "none",
    },
''',
        "Cobalt does not ship Web NFC",
    ),
]


# The operative lever for WebXR and WebUSB. content/child/runtime_features.cc
# copies these onto the Blink features with no kSetOnlyIfOverridden, so whatever
# they say wins over runtime_enabled_features.json5.
CONTENT_FEATURES = "content/public/common/content_features.cc"

CONTENT_EDITS = [
    (
        "kWebUsb",
        '''// Controls whether the WebUSB API is enabled:
// https://wicg.github.io/webusb
BASE_FEATURE(kWebUsb, "WebUSB", base::FEATURE_ENABLED_BY_DEFAULT);
''',
        '''// Controls whether the WebUSB API is enabled:
// https://wicg.github.io/webusb
//
// Cobalt does not ship WebUSB (see
// tools/patches/cobalt-disable-device-apis.py). This is the lever that counts:
// runtime_features.cc copies this onto the Blink feature with no
// kSetOnlyIfOverridden, so it overwrites the runtime_enabled_features.json5
// status either way.
BASE_FEATURE(kWebUsb, "WebUSB", base::FEATURE_DISABLED_BY_DEFAULT);
''',
    ),
    (
        "kWebXr",
        '''// Controls whether the WebXR Device API is enabled.
BASE_FEATURE(kWebXr, "WebXR", base::FEATURE_ENABLED_BY_DEFAULT);
''',
        '''// Controls whether the WebXR Device API is enabled.
//
// Cobalt does not ship WebXR. args.gn already sets enable_vr, enable_openxr,
// enable_arcore and enable_cardboard false, which removes the device backends;
// this removes the API. runtime_features.cc copies this onto the Blink feature
// unconditionally, so the json5 status alone did not survive to runtime.
BASE_FEATURE(kWebXr, "WebXR", base::FEATURE_DISABLED_BY_DEFAULT);
''',
    ),
]


def patch_content_features() -> int:
    """Flip kWebUsb and kWebXr off. Returns the number of failures."""
    path = SRC / CONTENT_FEATURES
    if not path.exists():
        print(f"  {'content_features.cc':<12} FAILED  missing {CONTENT_FEATURES}",
              file=sys.stderr)
        return 1

    text = path.read_text(encoding="utf-8")
    failures = 0
    changed = False

    for label, old, new in CONTENT_EDITS:
        if new.split("\n")[-2] in text:
            print(f"  {label:<12} already applied")
            continue
        if old not in text:
            print(f"  {label:<12} FAILED  anchor not found in {CONTENT_FEATURES}",
                  file=sys.stderr)
            failures += 1
            continue
        if text.count(old) != 1:
            print(f"  {label:<12} FAILED  anchor matches {text.count(old)}x",
                  file=sys.stderr)
            failures += 1
            continue
        text = text.replace(old, new, 1)
        changed = True
        print(f"  {label:<12} patched")

    if changed and not failures:
        path.write_bytes(text.replace("\r\n", "\n").encode("utf-8"))
    return failures


def main() -> int:
    path = SRC / REL
    if not path.exists():
        print(f"missing {REL}", file=sys.stderr)
        return 1

    text = path.read_text(encoding="utf-8")
    failures = 0
    changed = False

    for label, old, new, marker in EDITS:
        if marker in text:
            print(f"  {label:<12} already applied")
            continue
        if old not in text:
            print(f"  {label:<12} FAILED  anchor not found; the block changed shape",
                  file=sys.stderr)
            failures += 1
            continue
        if text.count(old) != 1:
            print(f"  {label:<12} FAILED  anchor matches {text.count(old)}x",
                  file=sys.stderr)
            failures += 1
            continue
        text = text.replace(old, new, 1)
        changed = True
        print(f"  {label:<12} patched")

    if failures:
        print(f"\n{failures} edit(s) failed; nothing written", file=sys.stderr)
        return 1

    if changed:
        # Chromium sources are LF; writing CRLF from Windows breaks the build.
        path.write_bytes(text.replace("\r\n", "\n").encode("utf-8"))

    # The second lever. Without this, WebXR and WebUSB come back at runtime no
    # matter what the json5 above says -- verified on a device, not assumed.
    failures += patch_content_features()
    if failures:
        print(f"\n{failures} edit(s) failed; tree is partially patched",
              file=sys.stderr)
        return 1

    print("\nnavigator.xr, navigator.usb and NDEFReader are gone. WebHID needed "
          "nothing (upstream leaves it off on Android); Web Bluetooth stays and "
          "is already ASK-by-default through BLUETOOTH_GUARD.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

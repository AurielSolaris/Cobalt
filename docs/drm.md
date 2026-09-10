# DRM and Widevine

**Cobalt supports Widevine, it costs nothing, and there is no licence to sign.**
That surprised me, so this records why, with the checks.

## Android is not desktop, and that is the whole answer

On desktop, Widevine is a **binary CDM** — a proprietary `.so`/`.dll` Google
distributes, which Chrome bundles and Chromium-derived browsers download as a
component. That is the arrangement Brave documents: Widevine is off by default
and fetched from Google's servers on demand, and Brave "reuses most of
Chromium's Widevine bundling infra except default Widevine shipping".

**On Android there is no CDM binary at all.** Widevine is part of the operating
system, reached through `android.media.MediaDrm`, and Chromium simply calls it
via `MediaDrmBridge`. Nothing is bundled, nothing is downloaded, nothing is
licensed by the browser vendor. The device already has it.

`third_party/widevine/cdm/widevine.gni` says so directly:

```gn
enable_widevine = ((is_chrome_branded || is_chrome_for_testing_branded) &&
                   !is_fuchsia) || is_android

library_widevine_cdm_available =
    (is_chromeos && ...) || (target_os == "linux" && ...) ||
    (target_os == "mac" && ...) || (target_os == "win" && ...)

enable_library_widevine_cdm =
    enable_widevine && enable_library_cdms && library_widevine_cdm_available
```

Two things follow:

- **`|| is_android`** — `enable_widevine` is true for *every* Android build,
  Chrome-branded or not. Cobalt gets it without being branded as Chrome.
- Android is **absent** from `library_widevine_cdm_available`, so
  `enable_library_widevine_cdm` is false. There is no library CDM to ship.

Confirmed in Cobalt's own build: `media_drm_bridge.o` and
`media_drm_storage_impl.o` are compiled into the APK.

## Verified on device

```js
navigator.requestMediaKeySystemAccess("com.widevine.alpha", [{
  initDataTypes: ["webm", "cenc"],
  videoCapabilities: [{contentType: 'video/webm; codecs="vp9"'}],
}])
```

| Key system | Result |
|---|---|
| `com.widevine.alpha` | **SUPPORTED** |
| `org.w3.clearkey` | **SUPPORTED** |

So EME works today, unmodified. No patch was needed and none is proposed.

### The false alarm, recorded because it is an easy trap

The first probe asked for `video/mp4; codecs="avc1.42E01E"` and **both** key
systems returned `NotSupportedError` — which looks exactly like "EME is broken
on desktop_android" and sent me hunting through `chrome_key_systems.cc` and the
`GetSupportedKeySystems` plumbing for a gating bug that does not exist.

The cause was the codec, not the key system:

```
canPlayType('video/mp4; codecs="avc1.42E01E"')  ->  ""          (no)
canPlayType('video/webm; codecs="vp9"')         ->  "probably"
```

`requestMediaKeySystemAccess` fails if *any* part of the configuration is
unsupported, and it does not say which part. **A DRM check that specifies H.264
tests the codec as much as the CDM.**

## The real limitation: H.264 and AAC, not DRM

Cobalt builds with `is_official_build = false`, which means
`ffmpeg_branding = "Chromium"` and **no H.264, and no AAC**. That is a licensing
boundary in the *codec*, entirely separate from Widevine.

It matters more than the DRM question, because most commercial streaming is
H.264 or HEVC in an MP4/CENC container. VP9 and AV1 in WebM work; a service that
only offers H.264 will not play, DRM or not.

Options, none taken yet:

1. **`ffmpeg_branding = "Chrome"`** — builds the H.264/AAC decoders. This is a
   patent-licensing question, not a technical one, and it belongs to whoever
   ships the binary. Recorded, not decided.
2. **Platform decoders.** Android has hardware H.264 and HEVC through
   `MediaCodec`, and Chromium can use them (`enable_platform_hevc` covers
   Android). This is the route that gets H.264 playback without shipping an
   ffmpeg decoder, and it is the more promising one for Cobalt.
3. **Ship without.** VP9/AV1/Opus/Vorbis cover a great deal of the open web and
   all of YouTube.

**Route 2 is the one worth investigating**, and it is unmeasured. The question is
whether `MediaCodec`-backed H.264 is reachable on a build without
`proprietary_codecs`, or whether that flag gates the platform path too.

## What Cobalt does *not* have to do

- No Widevine licence, agreement or fee. The browser is not the licensee; the
  device is.
- No CDM binary to bundle, download, update or verify.
- No component updater for Widevine.
- No "install Widevine" user flow, which is the Brave/desktop shape.

## Levels, briefly

Widevine has security levels L1 (hardware-backed, in the TEE) and L3
(software). Which one a device offers is a property of the device, not the
browser — Cobalt gets whatever `MediaDrm` reports. Services that require L1 for
HD will behave on Cobalt as they do in any other browser on that handset.

## Is there an open-source Widevine?

**No, and there cannot be one that works.** Widevine's security model rests on a
device-provisioned key the licence server checks; an open implementation would
have no valid provisioning and every licence request would be refused. Projects
that reverse-engineer L3 exist and are neither legal to ship nor functional at
scale.

The good news is that the question does not arise here: **Cobalt does not need
one**, because it never ships a CDM in the first place.

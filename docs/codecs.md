# H.264 and AAC

Cobalt carries its own H.264 and AAC decoders from 0.4.4. Before that it
carried none, and a video played only if the phone's own MediaCodec offered
the format.

## What changed

Two arguments in `tools/build/build-chromium.sh`:

```gn
proprietary_codecs = true
ffmpeg_branding = "Chrome"
```

They do different jobs, and one without the other is broken in an obvious way:

- **`proprietary_codecs`** gates the demuxers and the answer Blink gives to
  `canPlayType` / `MediaSource.isTypeSupported`. With it alone, the browser
  claims H.264 and AAC and then has nothing to decode them with.
- **`ffmpeg_branding`** picks which of ffmpeg's checked-in configurations is
  compiled. Cobalt built `"Chromium"` until now; its android/arm64 config sets

  ```c
  #define CONFIG_H264_DECODER 0
  #define CONFIG_AAC_DECODER 0
  ```

  and the `"Chrome"` config sets both to 1, plus `CONFIG_MP3_DECODER 1`.

Nothing else in the tree changes. This is the same switch Chrome itself is
built with; Chromium leaves it off because the formats are patent-encumbered,
not because the code is absent.

## What it does not do

**Encoding.** The same Chrome config has `CONFIG_AAC_ENCODER 0`, and
`media/media_options.gni` forces

```gn
media_use_openh264 = false   # if (is_ios || is_android || !proprietary_codecs)
```

so no software H.264 encoder is compiled on Android either. Anything that
*produces* H.264 or AAC — `MediaRecorder`, WebCodecs' `VideoEncoder`, a WebRTC
call that negotiates H.264 — still goes to the platform's MediaCodec, exactly
as it did before. Bundling an encoder means patching that `.gni` to let
openh264 build on Android, which is a separate decision and a larger one: it is
a deliberate upstream exclusion, not an oversight.

**HEVC.** `CONFIG_HEVC_DECODER` is 0 in the Chrome config too. HEVC on Android
is platform-only, and stays that way.

## Why bundle decoders at all

The platform decoder is the phone vendor's, and what it supports varies. On the
low-end hardware Cobalt targets, a missing or broken profile is not rare, and
the failure is a video that silently does not play. A decoder in the APK is the
same decoder on every device, and it is the one upstream tests against.

The cost is binary size — ffmpeg grows by the decoders — and a slower path than
hardware decoding for large videos. Chromium still prefers MediaCodec where it
can; the bundled decoders are what it falls back to rather than failing.

## Licensing

H.264 and AAC are covered by patents. Chrome ships them under licences Google
holds, and those licences do not extend to a fork that builds from source and
distributes the result. Cobalt's own APKs carry these decoders; anyone
redistributing them, or shipping a build to a jurisdiction where those patents
are enforced, is responsible for their own position. Building without them is
one flag: set `proprietary_codecs = false` and rebuild.

This is the same tradeoff every Chromium fork makes, and it is written down
here rather than left implicit in a GN file.

## Checking it on a device

`chrome://version` does not report codecs. These do:

```js
document.createElement('video').canPlayType('video/mp4; codecs="avc1.42E01E"')
document.createElement('audio').canPlayType('audio/mp4; codecs="mp4a.40.2"')
```

Both answered `""` before 0.4.4 and answer `"probably"` after. `chrome://media-internals`
names the decoder actually chosen for a playing video, which is how to tell
`FFmpegVideoDecoder` from `MediaCodecVideoDecoder`.

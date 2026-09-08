# Gate A — unpatched M140 build

Run 2026-09-08 against the first APK Cobalt has produced. Per
[`acceptance.md`](acceptance.md), Gate A proves the **toolchain**, not Cobalt:
this is upstream Chromium with none of Kiwi's patches and none of our branding.

## Device

| | |
|---|---|
| Model | Samsung SM-M315F (Galaxy M31) |
| Android | 16 |
| ABI | arm64-v8a |
| RAM | 7.6 GB |
| Cores | 8 |

**Not the target hardware.** Decision [0004](decisions/0004-performance-budget.md)
sets the budget at 4 GB and 2 cores. This device has roughly twice the memory and
four times the cores, so nothing here says anything about performance on the
machine we are actually building for. Functional results transfer; timings do not.

## Results

| Check | Result |
|---|---|
| `ChromePublic.apk` exists, plausible size | **pass** — 303 MB, valid zip, 4,415 entries |
| `AndroidManifest.xml`, `classes.dex`, `resources.arsc` present | **pass** |
| Installs via `adb install` | **pass** |
| Launches without crashing | **pass** |
| Loads a heavy real page over HTTPS | **pass** — `en.wikipedia.org`, rendered correctly |
| Text, images, links, SVG icons render | **pass** |
| Scrolling | **pass** |
| Back navigation | **pass** |
| Rotation | **pass** |
| Background/foreground cycle | **pass** |
| No crash in logcat | **pass** — 0 fatal exceptions, 0 ANRs |

Pinch-zoom was **not run** — `adb input` cannot synthesise a two-finger gesture.
It needs checking by hand.

## Native libraries shipped

```
lib/arm64-v8a/libchrome.so                  209 MB
lib/arm64-v8a/libchrome_crashpad_handler.so 1.8 MB
lib/arm64-v8a/libarcore_sdk_c.so             68 KB
```

`libarcore_sdk_c.so` is WebXR, which is on the removal list — confirmation that
`enable_arcore` and `enable_cardboard` are live and worth turning off.

## Process model and memory

Five processes after browsing one page:

```
org.chromium.chrome                       browser
org.chromium.chrome:privileged_process0   GPU
org.chromium.chrome_zygote                renderer template
...SandboxedProcessService0:30            renderer
...SandboxedProcessService0:31            renderer
```

```
TOTAL PSS   196 MB
TOTAL RSS   402 MB
Native heap  20 MB
Dalvik heap  15 MB
```

**196 MB PSS for a single tab**, before any of our work. On a 4 GB device where
the browser realistically gets 1–1.5 GB before the low-memory killer takes an
interest, that is the number Stage 7's tab eviction has to work against — and it
is measured on a *generous* device. Two renderers are already running for one
page, which is what site isolation costs.

## What this does not prove

- Nothing about Kiwi's patches; none are applied.
- Nothing about extensions; the subsystem is untouched upstream.
- Nothing about performance on 4 GB / 2 cores.
- Nothing about Cobalt's own UI, which is not in this build at all.

Gate B covers those, after batch 1 lands.

# Building Cobalt

## Current milestone (0.1.0)

0.1.0 builds with Gradle alone. The Chromium toolchain is not needed until Stage 3.

### Requirements

- JDK 17
- Android SDK, `compileSdk 35`, `minSdk 24`
- A device or emulator running Android 7.0 (API 24) or later

### Setup

`local.properties` is not committed. Create it at the repository root:

```properties
sdk.dir=/path/to/Android/Sdk
```

On Windows, use forward slashes: `sdk.dir=C:/Users/you/AppData/Local/Android/Sdk`.

### Commands

```sh
./gradlew :modules:app:assembleDebug     # build the APK
./gradlew :modules:app:installDebug      # build and install on a connected device
./gradlew check                          # unit tests across all modules
./gradlew :modules:core:allTests         # one module's tests
```

The Kotlin Multiplatform modules (`core`, `engine`) declare `linuxX64` and `mingwX64`
targets alongside Android. Those exist to keep the shared code free of accidental JVM
dependencies; only the Android target is shipped.

### Layout

```
modules/core      URL handling, HTTP, text decoding, the JS engine interface
modules/engine    HTML tokenizer and tree builder, the stub JS engine
modules/app       Android Compose shell
```

## From Stage 3 onward

Stage 3 builds Kiwi's Chromium tree, which is a different exercise entirely: a
`depot_tools` checkout, roughly 100 GB of disk, and a build measured in hours rather
than seconds. Those instructions are written when that stage starts. The reference
source sits in `.ref/kiwi`, which is deliberately not committed.

## Troubleshooting

**`SDK location not found`** — `local.properties` is missing or `sdk.dir` is wrong.

**Configuration cache errors after a Gradle upgrade** — `./gradlew --stop`, then
delete `.gradle/configuration-cache`.

**A Kotlin/Native target fails to resolve on first build** — the toolchain downloads
on demand; the first build of `linuxX64` or `mingwX64` needs network access.

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

## Building Chromium (Stage 3 onward)

A different exercise entirely: tens of gigabytes and a build measured in hours.
Everything below is scripted in `tools/`; this explains what the scripts assume.

### The host

Chromium's Android build requires **Linux**. On Windows that means WSL2 with a
Debian-based distro — Chromium's `install-build-deps.sh` supports Debian and
Ubuntu only, so Fedora means installing dependencies by hand.

Our setup: **Ubuntu 24.04 in WSL2**, 16 cores and 10 GB (`~/.wslconfig`), with
16 GB of swap as a cushion for link steps.

### The build volume: use ext4, not a Windows drive

**A Chromium checkout cannot live on an exFAT or NTFS drive mounted at
`/mnt/…`.** exFAT has no symlinks and no permission bits, and the 9p filesystem
WSL uses for Windows drives is far too slow for a tree of hundreds of thousands
of small files.

The answer that avoids repartitioning is an **ext4 virtual disk stored as a file
on the Windows drive**, attached to WSL as a real block device:

```powershell
# Once, elevated. 500 GB expandable, on the external SSD.
diskpart /s mkvhd.txt     # create vdisk file="E:\cobalt-build.vhdx" maximum=512000 type=expandable
```

```powershell
wsl --mount "E:\cobalt-build.vhdx" --vhd --bare
```

```sh
sudo mkfs.ext4 -L cobalt-build /dev/sdX     # the 500 GB blank device
sudo mkdir -p /build && sudo mount -L cobalt-build /build
echo 'LABEL=cobalt-build /build ext4 defaults,nofail 0 2' | sudo tee -a /etc/fstab
```

Real ext4 semantics, native speed, the Windows drive stays fully usable, and
removing it is deleting one file.

> **`wsl --shutdown` detaches the VHDX.** After any WSL restart — including one
> caused by editing `~/.wslconfig` — `/build` is gone until you re-run
> `wsl --mount … --vhd --bare`. The `fstab` entry only remounts it once the disk
> is attached again. A checkout that appears to have vanished is almost always
> this.

### The sequence

```sh
tools/setup-build-host.sh                  # as root: mount, deps, depot_tools
tools/fetch-chromium.sh                    # ~36 GB, pinned revision
tools/build-chromium.sh deps               # as root: Chromium's own installer
tools/build-chromium.sh hooks              # NDK, SDK, toolchains
tools/check-gn-args.sh                     # verify args before gn gen
tools/build-chromium.sh gen                # write args.gn, run gn gen
tools/build-chromium.sh build              # autoninja the APK
```

`tools/sync-progress.sh` and `tools/build-progress.sh` report progress; neither
touches the checkout.

### Things that cost us hours, so they are written down

**Long jobs must not be backgrounded with `setsid nohup`.** WSL terminated them
twice, silently, mid-sync. Run them in the foreground of a held session instead.

**`gclient` with `managed: False` does not clone `src`.** It expects the checkout
to exist and resolves only DEPS against it.

**Never pass `--revision` when `src` is already at the pin.** Against a shallow
single-branch clone, gclient falls back to `git fetch origin` with no depth limit
— Chromium's entire history, to reach a commit already checked out.

**`chromium.googlesource.com` rate-limits.** `-j 12` earned HTTP 429s and
quarantined half-fetched trees into `_bad_scm/`. `-j 4` is both kinder and
faster, because the cap is on request rate rather than bandwidth.

**Verify GN args against the revision.** `gn gen` fails on an unknown argument,
and it runs *after* the hooks step has pulled gigabytes. `enable_nacl` was
ordinary on Kiwi's M105 and does not exist on M140 — NaCl was removed from
Chromium in between.

**Sleep kills builds; screen blanking does not.** Check
`powercfg /q SCHEME_CURRENT SUB_SLEEP STANDBYIDLE`. On battery the default is
often a few minutes, which will suspend WSL mid-link.

## Troubleshooting

**`SDK location not found`** — `local.properties` is missing or `sdk.dir` is wrong.

**Configuration cache errors after a Gradle upgrade** — `./gradlew --stop`, then
delete `.gradle/configuration-cache`.

**A Kotlin/Native target fails to resolve on first build** — the toolchain downloads
on demand; the first build of `linuxX64` or `mingwX64` needs network access.

## The build volume: three failures on a USB SSD

Recorded because the symptoms were misleading each time, and the same
misreading would cost days again.

The build lived on a 500 GB ext4 VHDX on an external SanDisk Portable SSD. It
failed three times under sustained build load, presenting differently each time:

**1. Write errors → read-only remount.** ext4 saw write failures on the
superblock and remounted `/build` read-only to protect the data. Every compile
then failed with `unable to open output file: 'Read-only file system'`, which
siso reported as 107 compiler errors. One hardware fault wearing a hundred
disguises.

**2. Corrupted output surviving a clean fsck.** After a repair that reported
*clean, no errors*, the resumed build failed at link time with hundreds of
`undefined symbol` errors against V8 Torque builtins, and siso reported its own
deps log damaged. `localexec` reported zero compile errors that run and the
volume stayed writable throughout — the objects were not being produced badly
then, they had been produced badly earlier and kept.

> **A passing `e2fsck` says the filesystem metadata is consistent. It says
> nothing about the contents of files written while the disk was failing.**
> Half-written objects look present, the deps log records them complete, and the
> damage surfaces hours later looking like a source problem.

**3. Read errors.** The drive failed to *read* `third_party/angle/src/libANGLE/
Stream.cpp` — a file written days earlier and verified by `e2fsck` an hour
before — with 14 consecutive I/O errors on one sector. Writes failing costs
time; reads failing costs data.

Windows logged **zero** Event 153 retries during that last hour while the Linux
kernel recorded continuous I/O errors. The Windows-side counter is not a
trustworthy health signal for a disk passed through to WSL.

### Repairing a WSL bare-mounted volume

`e2fsck` could not run, for three stacked reasons:

1. A `umount -l` fallback leaves the filesystem live; e2fsck aborts with exit 8
   while the caller sees a writable volume and assumes it was checked. **Never
   lazy-unmount before a check.**
2. systemd's fstab-generator remounts the volume the instant it is unmounted.
3. **WSL's bare-disk attachment holds an exclusive claim on the block device.**
   `/proc/mounts` showed zero references while `O_RDONLY|O_EXCL` still failed
   with `EBUSY` — and that open is what e2fsck actually tests.

The sequence that works: disable the fstab entry, `wsl --shutdown`, re-attach
the VHDX, boot with nothing mounting it, then check. `tools/fsck-volume.sh`
does this.

### Build sizing, learned the slow way

- A clean `chrome_public_apk` is **70,491 edges**. Earlier 40–50k guesses,
  extrapolated from object counts, were wrong.
- `autoninja` sizes `-j` from **CPU count and ignores memory**. On 16 cores with
  a 10 GB cap, heavy Blink and V8 units peak near 1 GB per clang; sixteen of them
  exhausted RAM and all 16 GB of swap. The build did not fail — it thrashed at
  4 objects/min with 60% I/O wait, which reads as slowness rather than
  misconfiguration. `-j 6` is the working figure here.

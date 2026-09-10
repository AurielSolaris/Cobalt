<p align="center">
  <img src="branding/generated/icon-256.png" alt="Cobalt" width="128" height="128">
</p>

<h1 align="center">Cobalt</h1>

<p align="center">
  A revival of <a href="https://github.com/kiwibrowser">Kiwi Browser</a> for Android.
</p>

<p align="center">
  <em>The mark is inspired by Kiwi Browser's own, and inverted — a blue bird on a
  dark ground where the original is a white bird on a coloured one. The silhouette
  carries the lineage; the inversion says this is a separate project, not something
  Kiwi ships. Derived from <code>kiwi_logo_circle.svg</code>, Copyright &copy; 2022
  Geometry OU (Kiwi Browser), BSD 3-Clause. Neither the Kiwi Browser name nor
  Geometry OU endorses Cobalt.</em>
</p>

---

Kiwi is a Chromium fork whose defining feature — desktop-class extension support on
mobile — has gone unmaintained. Cobalt picks the project up, carries its patches
forward onto a current Chromium, and ships it under its own identity.

It is three things at once, and the distinction between them is the whole design:

| | What | Why |
|---|---|---|
| **Fork** | The engine tree — Blink, V8, the network stack, the extension system | Inherited whole from Chromium via Kiwi. Untouched except where a patch demands it. |
| **Port** | Kiwi's patch set, carried onto a current Chromium; the build, identity, and signing moved to ours | Kiwi is years behind upstream. Closing that gap, repeatably, is the actual work. |
| **Rewrite** | The Android shell above the engine — UI, address bar, tabs, settings | Written fresh in Kotlin and Compose rather than inherited from Kiwi's Java. This is the part we own. |

**App ID:** `app.auriel.cobalt` · **Repository:** <https://github.com/AurielSolaris/Cobalt>


## Status

**Extensions work on Android.** Chromium 140 builds as Cobalt and runs on device
with the extension subsystem compiled in: an MV2 extension installs, survives a
restart still enabled, and its `webRequestBlocking` listener genuinely blocks
requests — verified, with a control test, in
[`docs/gate-b-results.md`](docs/gate-b-results.md). MV3 runs alongside it.

That arrived earlier than the roadmap expected, because upstream turned out to
already build the extension system for Android behind `is_desktop_android`
([decision 0010](docs/decisions/0010-desktop-android-extensions.md)) — no patch
of ours was needed to switch it on.

**uBlock Origin ships preinstalled, and works.** It rides in the APK as a signed
CRX, installs itself on first run, compiles 176,450 network filters, and blocks
requests on device — `static.doubleclick.net` returns `ERR_BLOCKED_BY_CLIENT`.
It **cannot be uninstalled but can be disabled**, which is the semantic
[decision 0006](docs/decisions/0006-bundle-ublock-origin.md) asked for. Getting
there needed two upstream gaps closed: MV2's `browserAction` schema was not built
for Android at all, and `webNavigation` was excluded from `desktop_android`. Both
are now ported. See [`docs/ublock-bundling.md`](docs/ublock-bundling.md).

In progress: porting the rest of Kiwi's patch set. Two of its patches have been
**refused** rather than ported, one of which had disabled a security check
([decision 0011](docs/decisions/0011-refuse-mechanical-disablers.md)).

---

## Which Chromium, and what that means for security

Cobalt is built on **Chromium 140**, and stays there on purpose. Chrome ships a
milestone every two weeks; chasing that with a patch set this size would mean
around 26 rebases a year and no capacity left for the browser itself. That is
the treadmill Kiwi lost to.

Instead Cobalt **pins 140 and backports security fixes from current stable**,
rebasing only when a concrete trigger fires — a fix that cannot be backported, a
platform capability that cannot be, or a hard 12-month ceiling on any one base.
This is how Android ships a Linux kernel: years behind mainline, continuously
patched, rebased at platform transitions rather than at mainline's pace. *Old*
and *unpatched* are different properties, and only the second one matters.

Two things follow, and Cobalt commits to both:

- **The user agent tells the truth.** It reports Chromium 140, because that is
  what Cobalt is built from. It will never claim a version it is not running.
- **Applied backports are published** — which CVEs, which upstream commits,
  which release. "We backport aggressively" is worth nothing if you cannot check
  it.

The full reasoning, including the part of the Android analogy that does **not**
carry — Chromium publishes no LTS branch, so the curation is ours to do — is in
[decision 0016](docs/decisions/0016-pin-chromium-140-and-backport.md).

Knowing *what* to backport is the hard part, because Chromium restricts
visibility on security bugs until well after a fix ships. Cobalt corroborates
across independent Chromium forks — Vanadium, Cromite, Brave — on top of
upstream's own record, because every fork carrying an out-of-tree patch set has
the same problem and several have been solving it for years
([decision 0017](docs/decisions/0017-corroborate-security-fixes-across-forks.md)).
That runs daily as `.github/workflows/security-watch.yml`, which files a triage
issue per CVE; the procedure is in
[`docs/backporting.md`](docs/backporting.md). Forks are a signal, not a source
of code: the patch itself comes from upstream, adapted to M140.

## What Cobalt does not ship

**Google Play Services is being removed entirely**
([decision 0013](docs/decisions/0013-remove-google-play-services.md)), and it is
a release gate. Progress is a number anyone can produce —
`tools/build/gms-inventory.sh` reads the shipped APK's real dependency graph —
and it currently reads **14 modules, down from 18**. See
[`docs/gms-removal.md`](docs/gms-removal.md).

**WebXR, WebUSB and Web NFC are off.** Each hands a web page access to hardware
that essentially no site uses, on a browser targeting 4 GB and two cores. WebHID
was already off — upstream never enables it on Android. **Web Bluetooth stays**,
and is ask-before-use by default, which upstream already implements properly.
See [`docs/device-apis.md`](docs/device-apis.md).

## Building it yourself

Everything Cobalt changes about Chromium is declared in
`tools/patches/series.txt`, and one command applies it:

```sh
tools/build/build-chromium.sh all      # hooks + patch + gen + build
```

Full instructions, host requirements and the ccache setup are in
[`docs/reproducible-build.md`](docs/reproducible-build.md).

### What you get today, and what you don't

Building from `nightly` right now gives you **Chromium 140 with Cobalt's engine
work**: extensions on Android, MV2 and MV3, uBlock Origin preinstalled and
blocking, the extra search engines, and Cobalt's name and icons throughout.

**You do not get Cobalt's own shell.** The UI is still Chromium's Android
front-end wearing Cobalt's branding — its toolbar, its tab switcher, its
settings. The Kotlin/Compose shell with the bottom bar
([decision 0002](docs/decisions/0002-shell-design.md)) is not wired to the engine
yet; `./gradlew` builds it as a standalone 0.1.0 chassis that does not embed
Chromium. Replacing the Chromium shell is one of the three gates before a
`stable` release, along with uBlock Origin (**done**) and removing Google Play
Services ([decision 0013](docs/decisions/0013-remove-google-play-services.md),
not started). See [`docs/branching.md`](docs/branching.md).

---

### Extensions: both MV2 and MV3

Cobalt supports **Manifest V2 alongside Manifest V3, and will not drop MV2**.

MV2 is the only manifest version that grants `webRequestBlocking` — a blocking,
full-context view of network requests. MV3 replaces it with `declarativeNetRequest`,
a static rule list, which is strictly less capable. Content blockers and anything
that needs to reason about a request before it leaves the device work properly only
under MV2.

Extension support on mobile was Kiwi's reason for existing. Shipping it with the
more capable half removed would be a downgrade wearing a revival's clothes.
See [decision 0005](docs/decisions/0005-support-mv2-and-mv3.md).

---

## Where the line is

The engine is forked, never rewritten. Blink renders the pages, V8 runs the
JavaScript, and Chromium's network stack fetches the bytes — we do not reimplement any
of it, and patches that try will be declined regardless of quality.

Everything above the engine is fair game to rewrite, and the shell largely is one.

The JavaScript engine is **not** being replaced. A JavaScriptCore backend is parked as
a post-1.0 experiment behind a build flag that is off by default; see Stage 12 of the
project plan. The engine interface in `:modules:core` is kept engine-neutral so that
experiment stays possible, and for no other reason.

## The 0.1.0 shell

Cobalt is two programs until Stage 6 joins them. The Chromium tree above is what
installs and browses today; the Compose shell below is the chassis its UI will be
rebuilt from, and it still runs against its own bring-up renderer.

**Milestone 0.1.0 works**: type an address, and the page is fetched, parsed,
and rendered on device. Links navigate. Tabs and incognito tabs work.

What is real today:

- Address bar with URL normalization — a bare host always becomes `https`, never `http`
- Fetching over http(s) with redirects, charset detection, and typed, readable errors
- A tolerant HTML parser that never throws on malformed markup
- A renderer covering headings, paragraphs, inline emphasis, links, lists, `<pre>`,
  block quotes, rules, and image alt text
- In-memory tabs, a tab switcher, and incognito tabs
- One Dark interface, blue accent, near-square corners

What is deliberately not there yet: CSS, JavaScript execution, images, history,
bookmarks, downloads, and extensions. The renderer is a bring-up shim with a scheduled
deletion date — Blink replaces it in Stage 6, once the Chromium tree builds.

The roadmap is in [`docs/roadmap.md`](docs/roadmap.md); design and engine decisions,
including the ones that were rejected, are in [`docs/decisions/`](docs/decisions).

## Built for 4 GB and 2 cores

That is the target device, not the floor. Chromium's defaults assume far more
headroom than a budget Android phone has, and at this size memory is the binding
constraint rather than CPU — so background tabs are evicted by design, and the
process model is a tuning decision rather than an inherited default.

Memory saving and prerendering are **settings**, not silent policy. Both trade
something you can feel — a reload when you return to a tab, data and battery spent
on a guess — so both say what they cost and let you choose.

## Modules

| Module | Contents |
|---|---|
| `:modules:core` | URL handling, HTTP fetching, text decoding, the JS engine interface |
| `:modules:engine` | HTML tokenizer and tree builder, the stub JS engine |
| `:modules:app` | Android Compose shell — chrome, tabs, address bar, renderer |

## Building

```sh
./gradlew :modules:app:assembleDebug
./gradlew check
```

Requires JDK 17 and the Android SDK (compileSdk 35). Point `local.properties` at your
SDK with `sdk.dir=...`. Full instructions, including the Chromium build environment
needed from Stage 3 onward, are in [`docs/build.md`](docs/build.md).

## Interface

One Dark, blue accent, near-square corners. Three bundled variable fonts: Open Sans for
the interface, EB Garamond for display text and document headings, JetBrains Mono for
code. A bottom bar carries Home, Extensions, Tabs, and Downloads; bookmarks and settings
sit in the address bar's overflow menu.

There is no shortcut grid and no trending-searches feed on the new-tab page, and there
will not be one. Both are advertising surfaces. When Cobalt has browsing history to draw
on, that page can show your own most-visited sites — earned, not sold.

## Branches

`nightly` is the integration line and everything lands there first. `stable` is the
release line, and **it is dormant** — nothing has been promoted to it yet. Three
things gate the first release: uBlock Origin bundled and working, Google Play
Services removed entirely, and Cobalt's own shell in place of Chromium's Android
UI. Until then `nightly` is the only line that means anything.

From 0.2.0 to 1.0.0 every milestone also keeps a `nightly-0.x.0` and a
`stable-0.x.0`, and those are never deleted. The risk in this project is
concentrated in a few very large steps, and when one of them goes wrong a tag is a
point whereas a branch is somewhere a fix can actually land. See
[`docs/branching.md`](docs/branching.md).

## Licensing

Cobalt is free software under the **GNU General Public License, version 3**
([`LICENSE`](LICENSE)).

That licence applies on top of, and does not replace, the licences of the upstream
projects Cobalt is built from. Chromium, Blink, and V8 remain BSD-3-Clause; Kiwi
Browser keeps its own terms. Those notices are reproduced in full in
[`NOTICE`](NOTICE), and redistributing Cobalt means honouring all of them together.

## Contact

Debaditya Malakar — <debadityamalakar@gmail.com>

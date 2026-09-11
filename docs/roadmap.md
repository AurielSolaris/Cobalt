# Roadmap

The public stage map. Detailed per-stage checklists live in the maintainers' plan;
this file is the shared vocabulary the rest of the docs refer to when they say
"Stage 6".

Cobalt is a revival of Kiwi Browser: the engine tree is **forked** from Chromium and
left alone, Kiwi's patch set is **ported** onto a current Chromium, and the Android
shell above the engine is **rewritten** in Kotlin and Compose. Stages 0–2 are chassis
and reference work, 3–8 are the port itself, 9–11 make it shippable, and 12 is an
experiment that may never run.

| # | Stage | Version | Outcome |
|---|---|---|---|
| 0 | Foundations | — | Repo, branches, CI skeleton |
| 1 | Chassis | 0.1.0 | Installable app; a URL renders as a readable page |
| 2 | Reference pinning | 0.2.0 | Kiwi source pinned, its delta from Chromium mapped |
| 3 | Upstream build | 0.3.0 | Kiwi's Chromium tree builds an APK on our toolchain |
| 4 | Identity fork | 0.4.0 | It builds as *Cobalt* — branding, endpoints, signing |
| 5 | Rebase to modern Chromium | 0.5.0 | Kiwi's patches carried onto a current Chromium |
| 6 | Real renderer | 0.6.0 | Blink renders; the Compose shim is deleted |
| 7 | Browser features | 0.7.0 | Tabs, history, bookmarks, downloads, settings |
| 8 | Extensions | 0.8.0 | Kiwi's headline feature working again |
| 9 | Privacy & security | 0.9.0 | Hardened defaults, signing, updater |
| 10 | Release engineering | 0.9.x | Nightly and stable pipelines automated |
| 11 | Stabilization | 1.0.0 | Ship it |
| 12 | JavaScriptCore | post-1.0 | Flag-gated experiment; may be abandoned |

## Where Cobalt actually is

The stage numbers are a plan, not a report, and the plan has already been
overtaken in one place worth recording.

**Stage 8 arrived during Stage 5.** Extensions were expected to be the last
hard thing before release engineering. They work now — MV2 and MV3, on device,
with `webRequestBlocking` genuinely blocking — because upstream already builds
the extension system for Android behind `is_desktop_android`, so switching it on
took configuration rather than a port. See
[`gate-b-results.md`](gate-b-results.md) and
[decision 0010](decisions/0010-desktop-android-extensions.md).

**uBlock Origin now ships preinstalled and works** — installed from the APK on
first run, 176,450 filters compiled, blocking verified on device, and
disableable but not uninstallable. That closes release gate 1. See
[`ublock-bundling.md`](ublock-bundling.md).

Getting there closed two upstream gaps that were not on this map: MV2's
`browserAction` schema was not built for Android at all, and `webNavigation` was
excluded from `desktop_android`. The `_locales` side-loading defect was traced
to `base::FileEnumerator` never handling SAF virtual document paths, and fixed —
see [`extension-loading-android.md`](extension-loading-android.md), though it
still needs re-testing through the picker.

Two items have since been added ahead of 1.0 that were not on this map, both
because they are release gates rather than features:

- **Removing Google Play Services entirely**
  ([decision 0013](decisions/0013-remove-google-play-services.md)). The shell
  now comes first; GMS comes out when it blocks the shell, and otherwise after
  it — [decision 0018](decisions/0018-shell-before-gms-removal.md), superseding
  [0014](decisions/0014-gms-removal-before-shell.md)'s order.
- **Passwords**, because Chromium's Android password manager is disabled
  outright without a closed-source backend
  ([decision 0012](decisions/0012-own-password-store.md)). Cobalt enables
  Chromium's own local store with CSV import/export, and makes system autofill a
  first-class choice, rather than writing any credential code
  ([decision 0015](decisions/0015-passwords-local-store-and-system-autofill.md)).

Neither `stable` nor a 1.0 happens while those are open; see
[`branching.md`](branching.md) for the three gates on the first promotion.

## Next

1. **The shell** — tab model, bottom bar, the non-content surfaces, in the order
   in [`shell-integration.md`](shell-integration.md). GMS dependencies it runs
   into are removed as they are hit ([0018](decisions/0018-shell-before-gms-removal.md)).
2. **Passwords** — starting with the spike in
   [decision 0015](decisions/0015-passwords-local-store-and-system-autofill.md):
   turn `use_login_database_as_backend` on and measure what the settings surface
   actually does.
3. **The rest of GMS removal**, in the order set out in
   [decision 0014](decisions/0014-gms-removal-before-shell.md).

## The target device

**4 GB of RAM and 2 CPU cores.** Not a floor Cobalt tolerates — the machine it is
built for. Chromium's defaults assume much more headroom, and at this size memory
is the binding constraint rather than CPU. That shapes the tab model (eviction is
designed in, not retrofitted), the process model, and the defaults for
prerendering — which, like memory saving, is a setting the user controls rather
than policy applied to them. See
[`decisions/0004-performance-budget.md`](decisions/0004-performance-budget.md).

Optimising here means configuration, defaults, and the shell we own. It never
means patching engine internals: a configuration change survives a rebase, and a
hand-tuned renderer does not.

## Two dates worth knowing

**Stage 5 is the hard one.** Kiwi sits on Chromium 105 (August 2022); current stable
is 152. That four-year lag is the actual reason it needs reviving.

It is done as one hop, M105 to M140, rather than one leap to current. M140 is
three years on from Kiwi's base to within days. A single jump to 152 would land
every conflict at once with no checkpoint and no way to tell progress from thrash.

**The planned second hop, M140 to current, is cancelled.** Chrome now ships a
milestone every two weeks, so tracking stable would mean roughly 26 rebases a
year against this patch set — which is not a schedule, it is the whole schedule,
and it is exactly what killed Kiwi. Cobalt instead pins M140 and backports
security fixes aggressively, rebasing only when a defined trigger fires. This is
the model Android uses for the Linux kernel, and the reasoning is in
[decision 0016](decisions/0016-pin-chromium-140-and-backport.md).

Kiwi's source is a whole-file **overlay**, not a patch series: it says which files
were touched, never what changed inside them. Recovering real diffs — by checking
out Chromium 105 and comparing — is Stage 2's job, and everything after it depends
on that. See [`decisions/0003-staged-chromium-rebase.md`](decisions/0003-staged-chromium-rebase.md)
and [`kiwi-delta.md`](kiwi-delta.md).

**Stage 6 deletes code on purpose.** The 0.1.0 Compose renderer, the standalone HTML
parser, and probably the HTTP client all go when Blink lands. They exist to get pixels
on screen before the Chromium build works, and nothing should be built on top of them.

## Stage 12 is not a goal

V8 is the shipping JavaScript engine, indefinitely. The JavaScriptCore experiment sits
on `experiment/jsc` behind a build flag that defaults to off, and it cannot start
until 1.0 has shipped and someone has written down, with measurements, why it is worth
doing. A documented "no" is a successful outcome for that stage. See
[`decisions/0001-keep-v8.md`](decisions/0001-keep-v8.md).

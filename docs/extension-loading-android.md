# Loading unpacked extensions on Android

What works, what does not, and the one bug that matters.

`chrome://extensions` on the Route B build offers **Load unpacked**, which opens
the Android Storage Access Framework directory picker. Extensions load from a
folder the user grants access to. That much works.

## The `_locales` failure

Any extension declaring `default_locale` **fails to load**:

```
Failed to load extension
Error   Default locale is defined but default data couldn't be loaded.
        Could not load manifest.
```

This was first hit with uBlock Origin 1.74.0 — 656 files — where the obvious
suspects were size or archive layout. Both are wrong. The failure reproduces
with a **two-file extension**:

```
manifest.json                 { "default_locale": "en", ... }
_locales/en/messages.json     { "extName": { "message": "..." } }
```

Both files are demonstrably on the device and readable over adb
(`_locales/en/messages.json`, 47,298 bytes for uBO).

**It is not nested directories.** A control extension with its background
script at `js/bg.js` and no `default_locale` loads, enables, and runs its
background page normally. The difference between the two probes is one manifest
key and one directory name.

So the SAF-backed loader resolves ordinary subdirectories but does not resolve
the message bundle for `default_locale`.

## Why it matters

Nearly every real extension is localised. uBlock Origin is, which is the one
Cobalt has committed to bundling. On the current build, **side-loading a
localised extension through the picker is impossible**, which covers most of
what a user would actually want to install.

## What it does not block

Bundling. A preinstalled extension does not come through SAF — it is staged from
the APK onto a real filesystem path the browser reads directly, which is the
path `js/bg.js` already proves works. The bundling work is unaffected; this is a
separate defect on the side-loading route.

## Status

Not yet fixed, and not yet traced to a specific function. The next step is to
find where the unpacked loader reads `_locales` and see how it differs from the
path used for other extension files, since one resolves through SAF and the
other evidently does not.

Reproduce with the two probes described above; both are two files.

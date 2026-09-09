# Loading unpacked extensions on Android

What works, what does not, and the bug that explains it.

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

A control extension with its background script at `js/bg.js` and no
`default_locale` loads, enables, and runs normally. The difference between the
two probes is one manifest key and one directory name.

## Root cause: FileEnumerator cannot list a SAF path

**Traced.** The bug is in `//base/files`, not in the extension code.

1. `DeveloperPrivateLoadUnpackedFunction::StartFileLoad` converts the picked
   directory with `base::ResolveToVirtualDocumentPath` and loads the extension
   straight from the result. So `extension.path()` is a **`/SAF/...` virtual
   document path**, not a real filesystem path. Upstream has a TODO on this
   (b/433416481) describing it as temporary.

2. `DefaultLocaleHandler::Validate` calls `base::PathExists(<ext>/_locales)`.
   That **succeeds** — `file_util_posix.cc` handles content URIs and virtual
   document paths alike — so the load gets past `kLocalesTreeMissing`.

3. It then enumerates the locale directories with `base::FileEnumerator`. And
   `file_enumerator_posix.cc` is **the one file in `//base/files` that handles
   `IsContentUri()` but never `IsVirtualDocumentPath()`**. The `/SAF/...` path
   falls through to `opendir()`, which fails, so the enumeration yields nothing.

4. With no entries, `has_default_locale_message_file` stays false and Validate
   reports `kLocalesNoDefaultMessages` — the exact error on screen.

That also explains the shape of the earlier probes. Reading a file resolves
correctly; only **directory enumeration** is broken. `_locales` is simply the
one directory the unpacked loader is obliged to enumerate, so it is the only
place the gap shows.

## The fix

`tools/patches/cobalt-saf-file-enumerator.py` adds a virtual-document-path
branch to `FileEnumerator`: it resolves the `/SAF/...` root to a content URI,
lists it with the existing `ListContentUriDirectory`, and returns each child as
`root_path_.Append(name)`.

Returning the child's own opaque per-document content URI would not do. Callers
build paths themselves and compare — `Validate` compares an enumerated path
against `path.AppendASCII(default_locale)` — and an opaque URI matches nothing.
Keeping children addressed by name under the parent's virtual path is exactly
what the `/SAF/` format exists for; `virtual_document_path.h` says it was
introduced "to be safely manipulated by `FilePath`'s string operations".

Unlike the existing content-URI branch, the new one honours `file_type_` and the
name pattern rather than `CHECK`ing that neither is used. It has to: the caller
that exposed the bug asks for `DIRECTORIES` only, which that `CHECK` would
abort on.

## What it did not block

Bundling. A preinstalled extension never comes through SAF — it is staged from
the APK onto a real filesystem path the browser reads directly. uBlock Origin
installs and loads through that route with this bug still present, which is
what [`ublock-bundling.md`](ublock-bundling.md) records.

## Status

Fixed and built. **Not yet re-tested on device through the picker** — the fix
landed in the same build as the uBO bundling work, and the bundling route was
tested first because it is the release gate. Re-running the two-file probe
through `Load unpacked` is the outstanding verification.

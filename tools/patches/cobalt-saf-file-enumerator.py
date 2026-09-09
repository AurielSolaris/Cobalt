#!/usr/bin/env python3
"""Teach base::FileEnumerator to list Android SAF virtual document paths.

This is the cause of the bug in docs/extension-loading-android.md: every
extension declaring `default_locale` fails to load through `Load unpacked` with
"Default locale is defined but default data couldn't be loaded."

The trace, end to end:

* `DeveloperPrivateLoadUnpackedFunction::StartFileLoad` converts the directory
  the user picked with `base::ResolveToVirtualDocumentPath` and loads the
  extension from that path. So `extension.path()` is a `/SAF/...` virtual
  document path, not a real filesystem path. (Upstream has a TODO on this,
  b/433416481.)

* `DefaultLocaleHandler::Validate` checks `base::PathExists(<ext>/_locales)`,
  which succeeds -- `file_util_posix.cc` handles both content URIs and virtual
  document paths -- so the load gets past `kLocalesTreeMissing`.

* It then enumerates the locale directories with `base::FileEnumerator`. And
  `file_enumerator_posix.cc` is the one file in //base/files that handles
  `IsContentUri()` but never `IsVirtualDocumentPath()`. The `/SAF/...` path
  falls through to `opendir()`, which fails, so the enumeration yields nothing.

* With no entries, `has_default_locale_message_file` stays false and Validate
  reports `kLocalesNoDefaultMessages` -- the exact error on screen.

That also explains why the earlier probes narrowed the way they did: a control
extension with `js/bg.js` and no `default_locale` loads fine, because reading a
file resolves correctly and only *directory enumeration* is broken. `_locales`
is simply the one directory the unpacked loader has to enumerate.

The fix returns children as `root_path_.Append(name)` rather than as their own
opaque per-document content URIs. That is the whole point of the `/SAF/...`
format -- `virtual_document_path.h` says it exists "to be safely manipulated by
FilePath's string operations" -- and it is what callers require: Validate
compares an enumerated path against `path.AppendASCII(default_locale)`, which it
built itself. Returning content URIs would leave that comparison failing.

Unlike the existing content-URI branch, this one honours `file_type_` and the
name pattern instead of CHECKing that neither is used. It has to: the caller
that exposed the bug asks for DIRECTORIES only, which that CHECK would abort on.

Idempotent; every edit asserts.
"""
import sys
from pathlib import Path

SRC = Path(sys.argv[1] if len(sys.argv) > 1 else "/opt/cobalt/chromium/m140/src")
TARGET = SRC / "base/files/file_enumerator_posix.cc"

MARKER = "IsVirtualDocumentPath()"

INCLUDE_ANCHOR = """#if BUILDFLAG(IS_ANDROID)
#include "base/android/content_uri_utils.h"
#endif
"""

INCLUDE_NEW = """#if BUILDFLAG(IS_ANDROID)
#include <optional>

#include "base/android/content_uri_utils.h"
#include "base/android/virtual_document_path.h"
#endif
"""

# End of the existing content-URI branch inside the directory-advance loop,
# immediately before the plain opendir() path.
BRANCH_ANCHOR = """      break;
    }
#endif

    DIR* dir = opendir(root_path_.value().c_str());
"""

BRANCH_NEW = """      break;
    }

    // Android SAF virtual document paths ("/SAF/..."). Every other file API in
    // //base/files handles these alongside content URIs; this one did not, so
    // the path fell through to opendir() below, failed, and reported an empty
    // directory rather than an error. See
    // docs/extension-loading-android.md in the Cobalt tree.
    if (root_path_.IsVirtualDocumentPath()) {
      std::optional<files_internal::VirtualDocumentPath> vpath =
          files_internal::VirtualDocumentPath::Parse(root_path_.value());
      std::optional<std::string> content_uri =
          vpath ? vpath->ResolveToContentUri() : std::nullopt;
      if (!content_uri) {
        if (error_policy_ == ErrorPolicy::IGNORE_ERRORS) {
          continue;
        }
        error_ = File::FILE_ERROR_NOT_FOUND;
        return FilePath();
      }

      directory_entries_.clear();
      current_directory_entry_ = 0;
      for (FileInfo& info :
           internal::ListContentUriDirectory(FilePath(*content_uri))) {
        // Drop the per-document content URI. Children are addressed by name
        // under the parent's virtual path, which is the property the /SAF/
        // format exists to provide: callers Append to these paths and compare
        // them against paths they built themselves, and an opaque document URI
        // matches neither.
        info.content_uri_ = FilePath();

        if (ShouldSkip(info.filename_)) {
          continue;
        }
        const bool is_pattern_matched = IsPatternMatched(info.filename_);
        if (folder_search_policy_ == FolderSearchPolicy::MATCH_ONLY &&
            !is_pattern_matched) {
          continue;
        }

        const bool is_dir = info.IsDirectory();
        // No MarkVisited: SAF exposes no symlinks, so there is no cycle to
        // guard against and no stat to dedupe on.
        if (recursive_ && is_dir) {
          pending_paths_.push(root_path_.Append(info.filename_));
        }
        if (is_pattern_matched && IsTypeMatched(is_dir)) {
          directory_entries_.push_back(std::move(info));
        }
      }

      if (directory_entries_.empty()) {
        continue;
      }
      break;
    }
#endif

    DIR* dir = opendir(root_path_.value().c_str());
"""


def main() -> int:
    if not TARGET.exists():
        print(f"missing: {TARGET}", file=sys.stderr)
        return 1

    text = TARGET.read_text(encoding="utf-8")
    if MARKER in text:
        print("already applied")
        return 0

    for name, anchor in (("include block", INCLUDE_ANCHOR),
                         ("enumeration branch", BRANCH_ANCHOR)):
        if anchor not in text:
            print(f"anchor not found: {name}", file=sys.stderr)
            return 1
        if text.count(anchor) != 1:
            print(f"anchor is ambiguous: {name} ({text.count(anchor)})",
                  file=sys.stderr)
            return 1

    text = text.replace(INCLUDE_ANCHOR, INCLUDE_NEW, 1)
    text = text.replace(BRANCH_ANCHOR, BRANCH_NEW, 1)
    TARGET.write_text(text, encoding="utf-8")
    print("FileEnumerator now lists /SAF/ virtual document paths")
    return 0


if __name__ == "__main__":
    sys.exit(main())

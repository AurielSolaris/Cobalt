#!/usr/bin/env python3
"""Give Cobalt's WebContents a WebContentsDelegate.

Chrome attaches a `TabWebContentsDelegateAndroid` to every tab, from native code
inside `TabAndroid`. Cobalt has its own tab model and no `TabAndroid`, so its
`WebContents` had **no delegate at all** -- and Chromium treats a missing
delegate as "no" in several places. The one that surfaced first:

    // chrome/browser/download/download_request_limiter.cc
    if (!originating_contents->GetDelegate()) {
      std::move(callback).Run(false);
      return;
    }

Every download was refused there, silently: the navigation became a download
(`NavigationHandle.isDownload()` was true, measured on device) and no download
item was ever created. The same delegate is where Chromium asks about new
windows (`target=_blank`), fullscreen, and more.

There is no Java-callable way to attach one. This adds the smallest one there
can be: a Java class in Chromium's build with `attach` and `detach`, backed by
Chromium's stock `web_contents_delegate_android::WebContentsDelegateAndroid`,
which forwards to a Java `WebContentsDelegateAndroid` the shell subclasses in
Kotlin. Its `CanDownload` is content's default: allow.

## Lifetime

`~WebContentsDelegate` detaches itself from its WebContents, so destroying the
delegate first is safe; destroying the WebContents first would leave the
delegate holding a dangling pointer. So delegates live in a map keyed by
WebContents, and the shell calls `detach` before it destroys a tab -- the same
order `TabAndroid` uses. A WebContents destroyed without `detach` leaks its
delegate rather than crashing later.

## Where it goes

Java into `chrome_all_java` and C++ into `libchrome` and `libmonochrome`, for
the reason cobalt-content-view-render-view.py spells out: GEN_JNI comes from
chrome_public_apk's Java graph, and jni_zero refuses a native library that sees
the Java without the C++ behind it.

Idempotent; every edit asserts.
"""
import sys
from pathlib import Path

SRC = Path(sys.argv[1] if len(sys.argv) > 1 else "/opt/cobalt/chromium/m140/src")
DIR = "chrome/browser/cobalt/android"
JAVA_REL = "java/src/org/chromium/chrome/browser/cobalt/CobaltWebContentsDelegate.java"

JAVA = """\
// Copyright 2026 The Cobalt Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.cobalt;

import org.jni_zero.JNINamespace;
import org.jni_zero.NativeMethods;

import org.chromium.components.embedder_support.delegate.WebContentsDelegateAndroid;
import org.chromium.content_public.browser.WebContents;

/**
 * Attaches a {@link WebContentsDelegateAndroid} to a WebContents that has no
 * Chrome tab behind it. Cobalt's shell owns its tabs; see
 * tools/patches/cobalt-webcontents-delegate.py in the Cobalt repository.
 *
 * <p>Call {@link #detach} before destroying the WebContents.
 */
@JNINamespace("cobalt")
public final class CobaltWebContentsDelegate {
    private CobaltWebContentsDelegate() {}

    public static void attach(WebContents webContents, WebContentsDelegateAndroid delegate) {
        CobaltWebContentsDelegateJni.get().attach(webContents, delegate);
    }

    public static void detach(WebContents webContents) {
        CobaltWebContentsDelegateJni.get().detach(webContents);
    }

    @NativeMethods
    interface Natives {
        void attach(WebContents webContents, WebContentsDelegateAndroid delegate);

        void detach(WebContents webContents);
    }
}
"""

CC = """\
// Copyright 2026 The Cobalt Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include <map>
#include <memory>

#include "base/android/jni_android.h"
#include "base/check.h"
#include "base/no_destructor.h"
#include "components/embedder_support/android/delegate/web_contents_delegate_android.h"
#include "content/public/browser/web_contents.h"

// Must come after all headers that specialize FromJniType() / ToJniType().
#include "chrome/browser/cobalt/android/jni_headers/CobaltWebContentsDelegate_jni.h"

using base::android::JavaParamRef;

namespace cobalt {

namespace {

using Delegate = web_contents_delegate_android::WebContentsDelegateAndroid;
using Delegates = std::map<content::WebContents*, std::unique_ptr<Delegate>>;

// Keyed by WebContents; erased by Detach before the WebContents is destroyed.
// Destroying a delegate detaches it from its WebContents
// (~WebContentsDelegate), so erasing is the whole of detaching.
Delegates& GetDelegates() {
  static base::NoDestructor<Delegates> delegates;
  return *delegates;
}

}  // namespace

static void JNI_CobaltWebContentsDelegate_Attach(
    JNIEnv* env,
    const JavaParamRef<jobject>& jweb_contents,
    const JavaParamRef<jobject>& jdelegate) {
  content::WebContents* web_contents =
      content::WebContents::FromJavaWebContents(jweb_contents);
  CHECK(web_contents);
  Delegates& delegates = GetDelegates();
  delegates.erase(web_contents);
  auto delegate = std::make_unique<Delegate>(env, jdelegate);
  web_contents->SetDelegate(delegate.get());
  delegates[web_contents] = std::move(delegate);
}

static void JNI_CobaltWebContentsDelegate_Detach(
    JNIEnv* env,
    const JavaParamRef<jobject>& jweb_contents) {
  content::WebContents* web_contents =
      content::WebContents::FromJavaWebContents(jweb_contents);
  if (web_contents) {
    GetDelegates().erase(web_contents);
  }
}

}  // namespace cobalt
"""

GN = f"""\
# Copyright 2026 The Cobalt Authors
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

# Written by Cobalt's tools/patches/cobalt-webcontents-delegate.py.

import("//build/config/android/rules.gni")
import("//third_party/jni_zero/jni_zero.gni")

android_library("java") {{
  srcjar_deps = [ ":jni_headers" ]
  sources = [ "{JAVA_REL}" ]
  deps = [
    "//components/embedder_support/android:web_contents_delegate_java",
    "//content/public/android:content_java",
    "//third_party/jni_zero:jni_zero_java",
  ]
}}

generate_jni("jni_headers") {{
  sources = [ "{JAVA_REL}" ]
}}

source_set("android") {{
  sources = [ "cobalt_web_contents_delegate.cc" ]
  deps = [
    ":jni_headers",
    "//base",
    "//components/embedder_support/android:web_contents_delegate",
    "//content/public/browser",
  ]
}}
"""

REL = "chrome/android/BUILD.gn"
DONE = "Cobalt: a WebContentsDelegate"

JAVA_ANCHOR = '      "//chrome/browser/tabwindow/internal:java",\n'
JAVA_ADDED = (
    "      # Cobalt: a WebContentsDelegate for tabs Chrome's TabAndroid does not\n"
    "      # own. Without one Chromium refuses every download. Here for GEN_JNI,\n"
    "      # like embedder_support:view_java.\n"
    '      "//chrome/browser/cobalt/android:java",\n'
)

NATIVE_ANCHOR = """    module_descs = chrome_module_descs

    # Java and native targets form two independent compile graphs. Deps from java targets
"""
NATIVE_ADDED = (
    "    # Cobalt: a WebContentsDelegate for tabs without a TabAndroid, native half.\n"
    "    if (defined(deps)) {\n"
    '      deps += [ "//chrome/browser/cobalt/android" ]\n'
    "    } else {\n"
    '      deps = [ "//chrome/browser/cobalt/android" ]\n'
    "    }\n"
    "\n"
)

MONO_ANCHOR = """    deps = [
      \"//android_webview\",
      \"//base:jni_onload\",
      \"//components/crash/android:crashpad_main\",
"""
MONO_ADDED = (
    "      # Cobalt: the same C++ as libchrome; chrome_all_java feeds this\n"
    "      # library's Java graph too.\n"
    '      "//chrome/browser/cobalt/android",\n'
)


def write(rel: str, text: str) -> str:
    path = SRC / DIR / rel
    if path.exists() and path.read_text(encoding="utf-8") == text:
        return "already applied"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(text.encode("utf-8"))
    return "written"


def main() -> int:
    for rel, text in ((JAVA_REL, JAVA), ("cobalt_web_contents_delegate.cc", CC), ("BUILD.gn", GN)):
        print(f"  {DIR}/{rel.split('/')[-1]:<44} {write(rel, text)}")

    path = SRC / REL
    text = path.read_text(encoding="utf-8")
    if DONE in text or "//chrome/browser/cobalt/android:java" in text:
        print(f"  {REL} (WebContentsDelegate)                    already applied")
        return 0
    for name, anchor in (("chrome_all_java", JAVA_ANCHOR),
                         ("libchrome", NATIVE_ANCHOR),
                         ("libmonochrome_tmpl", MONO_ANCHOR)):
        n = text.count(anchor)
        if n != 1:
            print(f"  {REL}: the {name} anchor appears {n} times; refusing to guess",
                  file=sys.stderr)
            return 1
    text = text.replace(JAVA_ANCHOR, JAVA_ANCHOR + JAVA_ADDED, 1)
    text = text.replace(NATIVE_ANCHOR, NATIVE_ADDED + NATIVE_ANCHOR, 1)
    text = text.replace(MONO_ANCHOR, MONO_ANCHOR + MONO_ADDED, 1)
    path.write_bytes(text.replace("\r\n", "\n").encode("utf-8"))
    print(f"  {REL} (WebContentsDelegate)                    patched")
    return 0


if __name__ == "__main__":
    sys.exit(main())

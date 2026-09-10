#!/usr/bin/env python3
"""Bring a terminated extension back, because Android kills it and nothing else will.

## The bug, reproduced deterministically

    $ adb shell su -c 'kill -9 <extension renderer pid>'
    developerPrivate.getExtensionsInfo  ->  state: "TERMINATED"
    navigate to static.doubleclick.net/instream/ad_status.js
      before:  ERR_BLOCKED_BY_CLIENT, "blocked by an extension"
      after:   window.google_ad_status = 1;     <- the ad script runs

and it never comes back. The process stays dead, the extension stays
`TERMINATED`, and Cobalt's UI says nothing at all.

For a browser whose headline feature is a bundled content blocker, that is the
worst failure available: **it stops blocking and does not tell anyone.** It was
first noticed by accident, when quota numbers dropped back to the shared pool
during unrelated measurement.

## Why upstream does not have this problem

`ExtensionService::OnExtensionHostRenderProcessGone` posts
`ExtensionRegistrar::TerminateExtension`, with the comment "either fully working
or not loaded at all, but never half-crashed". That is deliberate and correct.

What follows it on desktop is a **crash bubble** offering Reload -- a piece of
UI. There is no automatic reload anywhere in Chromium, because desktop does not
need one: desktop renderers are not killed by an out-of-memory killer, and when
one does crash a human is looking at the window.

Neither holds here. Android kills background renderers under memory pressure as
routine behaviour, Cobalt targets 4 GB and two cores
([0004](docs/decisions/0004-performance-budget.md)), and Cobalt's Android UI has
no crash bubble to click.

## What this adds

A Cobalt-owned `ExtensionHostRegistry::Observer` that reloads a terminated
extension, with backoff and a cap so a genuinely broken extension cannot spin:

    attempt   1     2     3      4      5      then stop
    delay     2s    8s    30s    60s    120s

The counter resets once an extension has survived five minutes, so an extension
killed once a day is reloaded every time rather than eventually giving up.

Only extensions the user has **not** disabled are reloaded: a user-disabled
extension is left alone, and so is one disabled by policy.

## Shape of the patch

The logic lives in `chrome/browser/cobalt/extension_recovery/extension_auto_reload.{h,cc}`
-- files upstream does not have and will never conflict with -- following the
precedent set by `chrome/browser/cobalt/bundled_extensions.cc`. Upstream is
touched in exactly three places: one member, one initialiser, one BUILD.gn
source entry.

This is recovery, not prevention. The process still dies; it just comes back.
Prevention would mean teaching `ProcessRankPolicyAndroid` that an extension
background host is not a discardable background tab -- it ranks purely on focus,
visibility and active-tab, none of which a background page ever satisfies. That
is the better fix and a larger one, and it can never be complete anyway: on a
4 GB device Android will eventually win, so the recovery path has to exist
regardless.

Idempotent; every edit asserts.
"""
import sys
from pathlib import Path

SRC = Path(sys.argv[1] if len(sys.argv) > 1 else "/opt/cobalt/chromium/m140/src")

COBALT_DIR = "chrome/browser/cobalt/extension_recovery"

HEADER = r'''// Copyright 2026 The Cobalt Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CHROME_BROWSER_COBALT_EXTENSION_RECOVERY_EXTENSION_AUTO_RELOAD_H_
#define CHROME_BROWSER_COBALT_EXTENSION_RECOVERY_EXTENSION_AUTO_RELOAD_H_

#include <map>
#include <string>

#include "base/memory/raw_ptr.h"
#include "base/memory/weak_ptr.h"
#include "base/scoped_observation.h"
#include "base/time/time.h"
#include "extensions/browser/extension_host_registry.h"

namespace content {
class BrowserContext;
}

namespace cobalt {

// Reloads an extension whose renderer was killed.
//
// Chromium terminates an extension when its render process goes away and waits
// for a human to press Reload in a crash bubble. Cobalt has no such bubble on
// Android, and Android kills background renderers under memory pressure as a
// matter of course -- so without this, a bundled content blocker silently stops
// blocking for the rest of the session and nothing says so.
//
// Reloads are backed off and capped, so an extension that crashes on startup
// cannot spin. The attempt counter resets once an extension has stayed up for
// `kResetAfter`.
class ExtensionAutoReload : public extensions::ExtensionHostRegistry::Observer {
 public:
  explicit ExtensionAutoReload(content::BrowserContext* context);
  ExtensionAutoReload(const ExtensionAutoReload&) = delete;
  ExtensionAutoReload& operator=(const ExtensionAutoReload&) = delete;
  ~ExtensionAutoReload() override;

  // Give up after this many consecutive failures.
  static constexpr int kMaxAttempts = 5;
  // A run of this length without a termination clears the attempt counter.
  static constexpr base::TimeDelta kResetAfter = base::Minutes(5);

 private:
  // extensions::ExtensionHostRegistry::Observer:
  void OnExtensionHostRenderProcessGone(
      content::BrowserContext* browser_context,
      extensions::ExtensionHost* extension_host) override;
  void OnExtensionHostRegistryShutdown(
      extensions::ExtensionHostRegistry* registry) override;

  void Reload(const std::string& extension_id);

  struct Attempt {
    int count = 0;
    base::TimeTicks last;
  };

  raw_ptr<content::BrowserContext> context_;
  std::map<std::string, Attempt> attempts_;

  base::ScopedObservation<extensions::ExtensionHostRegistry,
                          extensions::ExtensionHostRegistry::Observer>
      observation_{this};

  base::WeakPtrFactory<ExtensionAutoReload> weak_factory_{this};
};

}  // namespace cobalt

#endif  // CHROME_BROWSER_COBALT_EXTENSION_RECOVERY_EXTENSION_AUTO_RELOAD_H_
'''

IMPL = r'''// Copyright 2026 The Cobalt Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "chrome/browser/cobalt/extension_recovery/extension_auto_reload.h"

#include "base/functional/bind.h"
#include "base/logging.h"
#include "base/task/single_thread_task_runner.h"
#include "content/public/browser/browser_context.h"
#include "extensions/browser/disable_reason.h"
#include "extensions/browser/extension_host.h"
#include "extensions/browser/extension_prefs.h"
#include "extensions/browser/extension_registrar.h"
#include "extensions/browser/extension_registry.h"

namespace cobalt {

namespace {

// Long enough for the termination this reload answers to have been processed --
// ExtensionService posts TerminateExtension as a task -- and long enough that a
// device already under memory pressure is not immediately asked for another
// renderer.
//
// A switch rather than an array because Chromium builds with
// -Werror,-Wunsafe-buffer-usage, which rejects indexing a C array.
base::TimeDelta ReloadDelay(int attempt) {
  switch (attempt) {
    case 0:
      return base::Seconds(2);
    case 1:
      return base::Seconds(8);
    case 2:
      return base::Seconds(30);
    case 3:
      return base::Seconds(60);
    default:
      return base::Seconds(120);
  }
}

}  // namespace

ExtensionAutoReload::ExtensionAutoReload(content::BrowserContext* context)
    : context_(context) {
  observation_.Observe(extensions::ExtensionHostRegistry::Get(context));
}

ExtensionAutoReload::~ExtensionAutoReload() = default;

void ExtensionAutoReload::OnExtensionHostRegistryShutdown(
    extensions::ExtensionHostRegistry* registry) {
  observation_.Reset();
}

void ExtensionAutoReload::OnExtensionHostRenderProcessGone(
    content::BrowserContext* browser_context,
    extensions::ExtensionHost* extension_host) {
  // May fire more than once per process death when several hosts shared the
  // process; the attempt bookkeeping below makes that harmless.
  const std::string extension_id = extension_host->extension_id();

  Attempt& attempt = attempts_[extension_id];
  const base::TimeTicks now = base::TimeTicks::Now();
  if (!attempt.last.is_null() && now - attempt.last > kResetAfter) {
    // It stayed up long enough to count as healthy; start over.
    attempt.count = 0;
  }
  attempt.last = now;

  if (attempt.count >= kMaxAttempts) {
    LOG(ERROR) << "Cobalt: extension " << extension_id << " terminated "
               << attempt.count << " times; not reloading again. It is not "
               << "running, and anything it provides is not happening.";
    return;
  }

  const base::TimeDelta delay = ReloadDelay(attempt.count);
  attempt.count++;

  LOG(WARNING) << "Cobalt: extension " << extension_id
               << " terminated (renderer gone); reloading in "
               << delay.InSeconds() << "s (attempt " << attempt.count << " of "
               << kMaxAttempts << ")";

  base::SingleThreadTaskRunner::GetCurrentDefault()->PostDelayedTask(
      FROM_HERE,
      base::BindOnce(&ExtensionAutoReload::Reload, weak_factory_.GetWeakPtr(),
                     extension_id),
      delay);
}

void ExtensionAutoReload::Reload(const std::string& extension_id) {
  auto* registry = extensions::ExtensionRegistry::Get(context_);
  auto* registrar = extensions::ExtensionRegistrar::Get(context_);
  if (!registry || !registrar) {
    return;
  }

  // Gone entirely -- uninstalled while the reload was pending.
  if (!registry->GetInstalledExtension(extension_id)) {
    attempts_.erase(extension_id);
    return;
  }

  // Only revive what the user still wants running. A disabled extension is a
  // decision, not a crash, and reloading it would override that decision.
  if (registry->disabled_extensions().Contains(extension_id) ||
      registry->blocklisted_extensions().Contains(extension_id)) {
    return;
  }

  // Already back -- something else reloaded it, or the user did.
  if (registry->enabled_extensions().Contains(extension_id) &&
      !registry->terminated_extensions().Contains(extension_id)) {
    return;
  }

  registrar->ReloadExtension(extension_id);
}

}  // namespace cobalt
'''

BUILD_GN = r'''# Copyright 2026 The Cobalt Authors
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.

import("//extensions/buildflags/buildflags.gni")

assert(enable_extensions_core)

source_set("extension_auto_reload") {
  sources = [
    "extension_auto_reload.cc",
    "extension_auto_reload.h",
  ]

  deps = [
    "//base",
    "//content/public/browser",
    "//extensions/browser",
  ]
}
'''


class Failed(Exception):
    pass


def edit(rel: str, pairs, *, done_marker: str) -> str:
    path = SRC / rel
    if not path.exists():
        raise Failed(f"missing {rel}")
    text = path.read_text(encoding="utf-8")
    if done_marker in text:
        return "already applied"
    for old, _ in pairs:
        if old not in text:
            raise Failed(f"anchor not found in {rel}: {old.strip()[:60]!r}")
        if text.count(old) != 1:
            raise Failed(f"anchor ambiguous in {rel} ({text.count(old)}x): "
                         f"{old.strip()[:60]!r}")
    for old, new in pairs:
        text = text.replace(old, new, 1)
    path.write_bytes(text.replace("\r\n", "\n").encode("utf-8"))
    return "patched"


def write(rel: str, body: str) -> str:
    path = SRC / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    data = body.replace("\r\n", "\n").encode("utf-8")
    if path.exists() and path.read_bytes() == data:
        return "unchanged"
    path.write_bytes(data)
    return "written"


def write_sources() -> str:
    a = write(f"{COBALT_DIR}/extension_auto_reload.h", HEADER)
    b = write(f"{COBALT_DIR}/extension_auto_reload.cc", IMPL)
    c = write(f"{COBALT_DIR}/BUILD.gn", BUILD_GN)
    return f"{a}/{b}/{c}"


def patch_service_header() -> str:
    return edit(
        "chrome/browser/extensions/extension_service.h",
        [(
            '''  std::unique_ptr<ChromeExtensionRegistrarDelegate>
      extension_registrar_delegate_;
''',
            '''  std::unique_ptr<ChromeExtensionRegistrarDelegate>
      extension_registrar_delegate_;

  // Cobalt: reloads an extension whose renderer Android killed. Upstream waits
  // for a human to press Reload in a crash bubble, which Cobalt's Android UI
  // does not have, so a terminated content blocker would otherwise stay dead
  // and silent for the rest of the session.
  std::unique_ptr<::cobalt::ExtensionAutoReload> cobalt_extension_auto_reload_;
''',
        )],
        done_marker="cobalt_extension_auto_reload_")


FWD_OLD = "class ProfileManager;\n"

# A unique_ptr member only needs the forward declaration; ExtensionService's
# destructor is out of line, so the full header stays out of extension_service.h.
#
# Declared at global scope deliberately. The obvious anchor a few lines down --
# next to ChromeExtensionRegistrarDelegate -- is inside `namespace extensions`,
# and putting it there declares extensions::cobalt::ExtensionAutoReload, which
# compiles as a forward declaration and then fails at the point of use with
# "allocation of incomplete type".
FWD_NEW = (
    "class ProfileManager;\n"
    "\n"
    "namespace cobalt {\n"
    "// Cobalt-owned; see chrome/browser/cobalt/extension_recovery/.\n"
    "class ExtensionAutoReload;\n"
    "}  // namespace cobalt\n"
)


def patch_service_forward_decl() -> str:
    return edit(
        "chrome/browser/extensions/extension_service.h",
        [(FWD_OLD, FWD_NEW)],
        done_marker="class ExtensionAutoReload;")


def patch_service_impl() -> str:
    return edit(
        "chrome/browser/extensions/extension_service.cc",
        [
            ('      extension_registrar_delegate_(\n'
             '          std::make_unique<ChromeExtensionRegistrarDelegate>(profile_)),\n',
             '      extension_registrar_delegate_(\n'
             '          std::make_unique<ChromeExtensionRegistrarDelegate>(profile_)),\n'
             '      // Cobalt: see the member declaration. Android kills background\n'
             '      // renderers routinely and nothing in Chromium brings the extension\n'
             '      // back, so a bundled content blocker silently stops blocking.\n'
             '      cobalt_extension_auto_reload_(\n'
             '          std::make_unique<::cobalt::ExtensionAutoReload>(profile_)),\n'),
            ('#include "chrome/browser/extensions/chrome_extension_registrar_delegate.h"\n',
             '#include "chrome/browser/cobalt/extension_recovery/extension_auto_reload.h"\n'
             '#include "chrome/browser/extensions/chrome_extension_registrar_delegate.h"\n'),
        ],
        done_marker="cobalt_extension_auto_reload_")


def patch_browser_build_gn() -> str:
    return edit(
        "chrome/browser/extensions/BUILD.gn",
        # Several deps repeat across the targets in this file, so anchor on one
        # that appears exactly once -- inside source_set("extensions")'s deps.
        [('    "//chrome/browser/bitmap_fetcher",\n',
          '    "//chrome/browser/bitmap_fetcher",\n'
          '    "//chrome/browser/cobalt/extension_recovery:extension_auto_reload",\n')],
        done_marker="cobalt/extension_recovery:extension_auto_reload")


STEPS = [
    ("chrome/browser/cobalt/extension_recovery/ (new sources)", write_sources),
    ("extension_service.h (forward declaration)", patch_service_forward_decl),
    ("extension_service.h (own the reloader)", patch_service_header),
    ("extension_service.cc (construct it)", patch_service_impl),
    ("chrome/browser/extensions/BUILD.gn (dep)", patch_browser_build_gn),
]


def main() -> int:
    if not (SRC / "chrome/browser/extensions/extension_service.cc").exists():
        print(f"not a chromium checkout: {SRC}", file=sys.stderr)
        return 1

    failures = 0
    for name, fn in STEPS:
        try:
            result = fn()
        except Failed as exc:
            print(f"  {name:<56} FAILED  {exc}")
            failures += 1
        else:
            print(f"  {name:<56} {result}")

    if failures:
        print(f"\n{failures} step(s) failed; tree is partially patched",
              file=sys.stderr)
        return 1
    print("\nA terminated extension is reloaded (2s, 8s, 30s, 60s, 120s, "
          "then stop).")
    return 0


if __name__ == "__main__":
    sys.exit(main())

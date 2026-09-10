#!/usr/bin/env python3
"""Four one-edge GMS dependencies, none of which touch chrome/android.

Grouped because they are the same size and the same shape, and because two of
them turn out to be nothing at all. Decision 0013, in 0014's order.

| Target | Module | What it actually was |
|---|---|---|
| `chrome/browser/language/android` | `tasks` | **dead** — no Java there imports GMS |
| `components/module_installer/android` | `tasks` | **dead** in the production target; only the junit test imports GMS |
| `chrome/browser/omaha/android` | `base` | one string constant |
| `chrome/browser/webauthn/android` | `tasks` | already unreachable code |

## omaha: a string constant, and a Play Store check that was already wrong

`UpdateStatusProvider` gates its "update available" state on whether the Play
Store is installed, reached through `GooglePlayServicesUtil.GOOGLE_PLAY_STORE_PACKAGE`.

That constant is the string `"com.android.vending"`. Inlining it removes the
entire GMS dependency of this target and changes no behaviour whatsoever.

**The behaviour is left alone deliberately**, even though it is already wrong
for Cobalt: Cobalt is not distributed through the Play Store, so an update
prompt conditioned on the Play Store being installed cannot ever be right. But
how Cobalt updates is a product decision nobody has made, 0013 does not cover
it, and quietly changing update behaviour inside a GMS-removal patch would be
smuggling a decision in under a dependency change. Recorded, not acted on.

## webauthn: the leverage from ExternalAuthUtils, immediately

`CableAuthenticatorModuleProvider.getLinkingInformation()` opens with:

    if (!ExternalAuthUtils.getInstance().canUseFirstPartyGooglePlayServices()) {
        ok = false;
    }
    if (!ok) { ...onHaveLinkingInformation(pointer, null); return; }

`cobalt-gms-externalauth.py` made that method return `false` unconditionally, so
everything after the guard is **already unreachable** — the method already
returns null on every call. This deletes code that cannot run and takes the
`gms.tasks.Task` import with it.

That is the whole argument for having done externalauth first: it converts the
GMS paths downstream into dead code *before* anyone has to reason about what
removing them would break, because the answer is already "nothing, it never
ran".

## The two dead ones

`chrome/browser/language/android` lists `tasks` and nothing under it imports
`com.google.android.gms` at all. `components/module_installer/android` lists it
in both the production library and the junit binary, and only the junit test
imports GMS.

Grep is not proof — a Java target can need a dependency it never imports when a
library it uses exposes those types — so both were removed and their targets
built, the same way `chrome_java`'s five were.

Idempotent; every edit asserts.
"""
import sys
from pathlib import Path

SRC = Path(sys.argv[1] if len(sys.argv) > 1 else "/opt/cobalt/chromium/m140/src")

TASKS_DEP = '    "$google_play_services_package:google_play_services_tasks_java",\n'


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


# ------------------------------------------------------------------ 1. language


def patch_language() -> str:
    return edit(
        "chrome/browser/language/android/BUILD.gn",
        [(TASKS_DEP,
          "    # Cobalt: the tasks AAR was listed and nothing here imports\n"
          "    # com.google.android.gms at all. Verified by removing it and\n"
          "    # building the target. Decision 0013.\n")],
        done_marker="the tasks AAR was listed and nothing here imports")


# ---------------------------------------------------------- 2. module_installer


def patch_module_installer() -> str:
    """Only the junit test imports GMS; the library dep is dead.

    The junit target keeps its own dependency: Cobalt does not build it, and
    editing a test target to remove a dep its own source still imports would
    break it for no shipped benefit.
    """
    # The dep line appears twice in this file -- once in the library, once in
    # the junit binary -- so anchor on the library's neighbours, which are not.
    neighbours = ('    "//base:base_java",\n'
                  '    "//components/crash/android:java",\n')
    return edit(
        "components/module_installer/android/BUILD.gn",
        [(TASKS_DEP + neighbours,
          "    # Cobalt: dead in this library -- only the junit test below\n"
          "    # imports com.google.android.gms. Verified by removing it and\n"
          "    # building module_installer_java. Decision 0013.\n"
          + neighbours)],
        done_marker="dead in this library")


# ---------------------------------------------------------------------- 3. omaha


def patch_omaha_java() -> str:
    return edit(
        "chrome/browser/omaha/android/java/src/org/chromium/chrome/browser/"
        "omaha/UpdateStatusProvider.java",
        [
            ("import com.google.android.gms.common.GooglePlayServicesUtil;\n", ""),
            ('                                && PackageUtils.isPackageInstalled(\n'
             '                                        GooglePlayServicesUtil.GOOGLE_PLAY_STORE_PACKAGE);\n',
             '                                // Cobalt: inlined from\n'
             '                                // GooglePlayServicesUtil.GOOGLE_PLAY_STORE_PACKAGE,\n'
             '                                // which is this exact string. Removing the\n'
             '                                // constant removes this target\'s only GMS\n'
             '                                // dependency and changes nothing (0013).\n'
             '                                //\n'
             '                                // The check itself is left alone even though it\n'
             '                                // is already wrong for Cobalt, which is not\n'
             '                                // distributed through the Play Store. How Cobalt\n'
             '                                // updates is a product decision nobody has made,\n'
             '                                // and changing it inside a dependency patch would\n'
             '                                // be smuggling one in.\n'
             '                                && PackageUtils.isPackageInstalled("com.android.vending");\n'),
        ],
        done_marker="Cobalt: inlined from")


def patch_omaha_build() -> str:
    return edit(
        "chrome/browser/omaha/android/BUILD.gn",
        [('    "$google_play_services_package:google_play_services_base_java",\n',
          "    # Cobalt: the only use was GOOGLE_PLAY_STORE_PACKAGE, a string\n"
          "    # constant, now inlined in UpdateStatusProvider (0013).\n")],
        done_marker="the only use was GOOGLE_PLAY_STORE_PACKAGE")


# ------------------------------------------------------------------- 4. webauthn

WEBAUTHN_OLD = '''        Fido2ApiCall call =
                new Fido2ApiCall(
                        ContextUtils.getApplicationContext(), Fido2ApiCall.FIRST_PARTY_API);
        Parcel args = call.start();
        Fido2ApiCall.ByteArrayResult result = new Fido2ApiCall.ByteArrayResult();
        args.writeStrongBinder(result);
        Task<byte[]> task =
                call.run(
                        Fido2ApiCall.METHOD_GET_LINK_INFO,
                        Fido2ApiCall.TRANSACTION_GET_LINK_INFO,
                        args,
                        result);
        task.addOnSuccessListener(
                        linkInfo -> {
                            CableAuthenticatorModuleProviderJni.get()
                                    .onHaveLinkingInformation(pointer, linkInfo);
                        })
                .addOnFailureListener(
                        exception -> {
                            Log.e(
                                    TAG,
                                    "Call to get linking information from Play Services failed",
                                    exception);
                            CableAuthenticatorModuleProviderJni.get()
                                    .onHaveLinkingInformation(pointer, null);'''


def patch_webauthn() -> str:
    """Delete a block that ExternalAuthUtils already made unreachable."""
    path = SRC / ("chrome/browser/webauthn/android/java/src/org/chromium/chrome/"
                  "browser/webauthn/CableAuthenticatorModuleProvider.java")
    if not path.exists():
        raise Failed("missing CableAuthenticatorModuleProvider.java")
    text = path.read_text(encoding="utf-8")
    if "Unreachable since decision 0013" in text:
        return "already applied"
    if WEBAUTHN_OLD not in text:
        raise Failed("the Fido2ApiCall block changed shape")

    # Everything from the Fido2ApiCall to the end of the method is dead: the
    # guard above it now always fails. Take the whole tail of the method.
    start = text.index(WEBAUTHN_OLD)
    tail = text[start:]
    # The method ends at the first line that closes it at four-space indent.
    end_marker = "\n    }\n"
    end = tail.index(end_marker) + len(end_marker)

    replacement = (
        "        // Unreachable since decision 0013: the guard above calls\n"
        "        // ExternalAuthUtils.canUseFirstPartyGooglePlayServices(), which\n"
        "        // Cobalt makes return false unconditionally, so this method has\n"
        "        // already returned null on every call. The Fido2ApiCall block\n"
        "        // that stood here talked to Play Services over gms.tasks and\n"
        "        // could never run.\n"
        "    }\n"
    )
    text = text[:start] + replacement + text[start + end:]
    # Parcel and Fido2ApiCall were only used by the block just removed, and
    # an unused import is an error under Chromium's Java checks.
    # ContextUtils and Log stay -- both still have callers above.
    for dead in (
        "import com.google.android.gms.tasks.Task;\n",
        "import android.os.Parcel;\n",
        "import org.chromium.components.webauthn.Fido2ApiCall;\n",
    ):
        text = text.replace(dead, "", 1)
    path.write_bytes(text.replace("\r\n", "\n").encode("utf-8"))
    return "patched"


def patch_webauthn_build() -> str:
    return edit(
        "chrome/browser/webauthn/android/BUILD.gn",
        [(TASKS_DEP,
          "    # Cobalt: the only use was the Fido2ApiCall in\n"
          "    # CableAuthenticatorModuleProvider, which ExternalAuthUtils had\n"
          "    # already made unreachable (0013).\n")],
        done_marker="the only use was the Fido2ApiCall")


STEPS = [
    ("chrome/browser/language/android (dead dep)", patch_language),
    ("components/module_installer/android (dead dep)", patch_module_installer),
    ("UpdateStatusProvider.java (inline the constant)", patch_omaha_java),
    ("chrome/browser/omaha/android/BUILD.gn", patch_omaha_build),
    ("CableAuthenticatorModuleProvider.java (dead code)", patch_webauthn),
    ("chrome/browser/webauthn/android/BUILD.gn", patch_webauthn_build),
]


def main() -> int:
    if not (SRC / "chrome/browser/omaha/android/BUILD.gn").exists():
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
    print("\nFour single-edge GMS dependencies gone.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

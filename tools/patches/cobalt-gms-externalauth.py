#!/usr/bin/env python3
"""Make ExternalAuthUtils tell the truth: Play Services is not available.

Third piece of decision 0013, and the highest-leverage one so far -- not
because it removes the most edges (two), but because of what keys off it.

`ExternalAuthUtils.canUseGooglePlayServices()` is *the* question the rest of the
tree asks before doing anything GMS-shaped. Fourteen non-test call sites ask it.
Once it answers `false` unconditionally, every one of those call sites takes the
path upstream already ships for a device without Play Services -- before their
own GMS removals are written. That makes the work that follows safer, because
the behaviour is already the target behaviour and only the dependency is left.

## What changes

Five methods stop consulting Google Play Services:

| Method | Now |
|---|---|
| `canUseGooglePlayServices(errorHandler)` | `false` |
| `canUseGooglePlayServices()` | `false` |
| `canUseFirstPartyGooglePlayServices(...)` | `false` |
| `isGooglePlayServicesMissing(context)` | `true` |
| `checkGooglePlayServicesAvailable` / `isUserRecoverableError` / `describeError` | deleted |

The last three are `protected` hooks meant for subclasses to override. Nothing
in the tree overrides them except one test class, and they cannot survive
anyway: their signatures are `ConnectionResult` codes and their bodies are
`GoogleApiAvailability` calls, so they *are* the dependency.

The error-handler argument stays in the signature. `UserRecoverableErrorHandler`
exists to offer the user a way to fix a recoverable Play Services problem;
there is no such problem to fix and no such user journey, so it is accepted and
ignored rather than removed from fourteen call sites.

## What does not change

`isGoogleSigned` and `isChromeGoogleSigned` are untouched. They look like they
belong to this removal and do not: they go through `mGoogleDelegate`, which is
null in upstream Chromium and exists as a hook for downstream builds. No GMS,
no behaviour change, nothing to do.

## The one behaviour worth stating

`SigninManagerImpl` calls `isGooglePlayServicesMissing()` and will now always
see `true`. That is correct and it is the point -- sign-in is dropped by 0013,
Cobalt has no account, and the honest answer to "is Play Services missing" on
a Cobalt device is yes.

Idempotent; every edit asserts.
"""
import sys
from pathlib import Path

SRC = Path(sys.argv[1] if len(sys.argv) > 1 else "/opt/cobalt/chromium/m140/src")

JAVA = ("components/externalauth/android/java/src/org/chromium/components/"
        "externalauth/ExternalAuthUtils.java")


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


MISSING_OLD = '''    public boolean isGooglePlayServicesMissing(final Context context) {
        final int resultCode = checkGooglePlayServicesAvailable(context);
        return (resultCode == ConnectionResult.SERVICE_MISSING
                || resultCode == ConnectionResult.SERVICE_INVALID);
    }
'''

MISSING_NEW = '''    public boolean isGooglePlayServicesMissing(final Context context) {
        // Cobalt ships no Google Play Services (decision 0013), so it is
        // missing by construction rather than by circumstance.
        return true;
    }
'''

CAN_USE_OLD = '''    public boolean canUseGooglePlayServices(final UserRecoverableErrorHandler errorHandler) {
        Context context = ContextUtils.getApplicationContext();
        final int resultCode = checkGooglePlayServicesAvailable(context);
        if (resultCode == ConnectionResult.SUCCESS) return true;
        // resultCode is some kind of error.
        Log.v(TAG, "Unable to use Google Play Services: %s", describeError(resultCode));
        if (isUserRecoverableError(resultCode)) {
            Runnable errorHandlerTask =
                    new Runnable() {
                        @Override
                        public void run() {
                            errorHandler.handleError(context, resultCode);
                        }
                    };
            PostTask.runOrPostTask(TaskTraits.UI_DEFAULT, errorHandlerTask);
        }
        return false;
    }
'''

CAN_USE_NEW = '''    public boolean canUseGooglePlayServices(final UserRecoverableErrorHandler errorHandler) {
        // Cobalt ships no Google Play Services (decision 0013).
        //
        // The error handler is accepted and ignored. It exists to offer the
        // user a way to repair a *recoverable* Play Services problem -- update
        // it, enable it, sign in -- and none of those is a journey that exists
        // here. Keeping the parameter avoids touching fourteen call sites to
        // delete an argument none of them will miss.
        return false;
    }
'''

FIRST_PARTY_OLD = '''    public boolean canUseFirstPartyGooglePlayServices(
            UserRecoverableErrorHandler userRecoverableErrorHandler) {
        return canUseGooglePlayServices(userRecoverableErrorHandler) && isChromeGoogleSigned();
    }
'''

FIRST_PARTY_NEW = '''    public boolean canUseFirstPartyGooglePlayServices(
            UserRecoverableErrorHandler userRecoverableErrorHandler) {
        // No Play Services at all, so certainly no first-party access to it.
        return false;
    }
'''

HOOKS_OLD = '''    /**
     * Invokes whatever external code is necessary to check if Google Play Services is available and
     * returns the code produced by the attempt. Subclasses can override to force the behavior one
     * way or another, or to change the way that the check is performed.
     *
     * @param context The current context.
     * @return The code produced by calling the external code
     */
    protected int checkGooglePlayServicesAvailable(final Context context) {
        // TODO(crbug.com/41233964): Temporarily allowing disk access until more permanent fix is
        // in.
        try (StrictModeContext ignored = StrictModeContext.allowDiskWrites();
                TraceEvent e = TraceEvent.scoped("checkGooglePlayServicesAvailable")) {
            return ChromiumPlayServicesAvailability.getGooglePlayServicesConnectionResult(context);
        }
    }

    /**
     * Invokes whatever external code is necessary to check if the specified error code produced
     * by {@link #checkGooglePlayServicesAvailable(Context)} represents a user-recoverable error.
     * Subclasses can override to filter error codes as desired.
     * @param errorCode The code to check
     * @return true If the code represents a user-recoverable error
     */
    protected boolean isUserRecoverableError(final int errorCode) {
        return GoogleApiAvailability.getInstance().isUserResolvableError(errorCode);
    }

    /**
     * Invokes whatever external code is necessary to obtain a textual description of an error
     * code produced by {@link #checkGooglePlayServicesAvailable(Context)}.
     * @param errorCode The code to check
     * @return a textual description of the error code
     */
    protected String describeError(final int errorCode) {
        return GoogleApiAvailability.getInstance().getErrorString(errorCode);
    }

'''

HOOKS_NEW = '''    // Removed with decision 0013: checkGooglePlayServicesAvailable,
    // isUserRecoverableError and describeError.
    //
    // They were protected hooks for subclasses to override, but they could not
    // survive the removal in any form -- their signatures traffic in
    // ConnectionResult codes and their bodies are GoogleApiAvailability calls,
    // so they are the dependency rather than users of it. Nothing in the tree
    // overrides them outside a test.

'''

IMPORTS_OLD = (
    "import com.google.android.gms.common.ConnectionResult;\n"
    "import com.google.android.gms.common.GoogleApiAvailability;\n"
)

# These were left holding only their import line once the three hooks went.
# Chromium's Java checks treat an unused import as an error, so they go too.
# Log stays: isSystemBuild still uses it.
DEAD_IMPORTS = [
    "import org.chromium.base.StrictModeContext;\n",
    "import org.chromium.base.TraceEvent;\n",
    "import org.chromium.base.task.PostTask;\n",
    "import org.chromium.base.task.TaskTraits;\n",
    "import org.chromium.gms.ChromiumPlayServicesAvailability;\n",
]


def patch_java() -> str:
    return edit(
        JAVA,
        [
            (MISSING_OLD, MISSING_NEW),
            (CAN_USE_OLD, CAN_USE_NEW),
            (FIRST_PARTY_OLD, FIRST_PARTY_NEW),
            (HOOKS_OLD, HOOKS_NEW),
            (IMPORTS_OLD, ""),
        ] + [(dead, "") for dead in DEAD_IMPORTS],
        done_marker="Cobalt ships no Google Play Services (decision 0013).")


def patch_build_gn() -> str:
    return edit(
        "components/externalauth/android/BUILD.gn",
        [(
            '''  deps = [
    "$google_play_services_package:google_play_services_base_java",
    "$google_play_services_package:google_play_services_basement_java",
    "//base:base_java",
''',
            '''  # Cobalt ships no Google Play Services (decision 0013): ExternalAuthUtils
  # answers "no" unconditionally, so it no longer references any GMS type.
  deps = [
    "//base:base_java",
''',
        ), (
            '    "//third_party/android_deps:chromium_play_services_availability_java",\n',
            "",
        )],
        done_marker="Cobalt ships no Google Play Services (decision 0013)")


STEPS = [
    ("ExternalAuthUtils.java (always unavailable)", patch_java),
    ("components/externalauth/android/BUILD.gn", patch_build_gn),
]


def main() -> int:
    if not (SRC / JAVA).exists():
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
    print("\ncanUseGooglePlayServices() is false everywhere; 14 call sites now "
          "take the no-GMS path.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

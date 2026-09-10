#!/usr/bin/env python3
"""WebOTP asks Play Services whether Play Services is there, and that throws.

WebOTP (`navigator.credentials.get({otp: ...})`) reads a one-time passcode out
of an incoming SMS. Every path to it on Android goes through Play Services --
`SmsRetriever` for the user-consent flow, the browser-code API for the
verification flow -- so on a device without GMS the feature cannot work, and
under decision 0013 Cobalt does not ship it.

That would be a quiet no-op except for how `SmsProviderGms` *checks*:

    GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(...)

`GoogleApiAvailability` does not answer "no". Before it looks for Play Services
it validates the caller's own manifest, and an app that never intended to use
GMS has not got the tag it wants, so the availability check **throws**:

    GooglePlayServicesMissingManifestValueException: A required meta-data tag
    in your app's AndroidManifest.xml does not exist. You must have the
    following declaration within the <application> element:
    <meta-data android:name="com.google.android.gms.version" .../>
      at SmsProviderGms.create(SmsProviderGms.java:97)

This is a fatal exception on the browser startup path -- it comes back through
`flushStartupTasks` and kills the process -- so on Cobalt it is not a missing
feature, it is a browser that does not start.

## What this changes, and what it deliberately does not

`create()` stops asking and answers **false**: the verification backend is not
available. The constructor then also stops building the user-consent receiver,
because that one reaches Play Services too and skipping only the first would
just move the same failure a few lines down.

The result is an `SmsProviderGms` with **both receivers null**, which is a state
the class already fully supports -- every use of them is null-guarded, because
upstream has to cope with a device where the backend genuinely is missing. So
`listen()` listens to nothing and WebOTP never resolves, which is the correct
behaviour for a browser with no route to an SMS.

**The object is still returned, not null.** `create()` is `@CalledByNative` and
C++ stores what it gets without checking; returning null would trade a clear
exception for a null dereference later, in native code, further from the cause.

**The GMS deps in `content/public/android/BUILD.gn` are left alone.** Four other
files there still import Play Services -- `Wrappers`, `SmsVerificationReceiver`,
`SmsUserConsentReceiver` and `AndroidFontLookupImpl` -- so the target genuinely
still needs them, and `check_for_missing_direct_deps` would say so. A target is
the unit, not a file: this project has already broken the build once by removing
a dep because the file it was reading no longer used it. Those edges come out
when the whole of WebOTP does.

Idempotent; every edit asserts.
"""
import sys
from pathlib import Path

SRC = Path(sys.argv[1] if len(sys.argv) > 1 else "/opt/cobalt/chromium/m140/src")

REL = ("content/public/android/java/src/org/chromium/content/browser/sms/"
       "SmsProviderGms.java")

DONE = "Cobalt: WebOTP needs Play Services"

OLD_PROBE = """        boolean isVerificationBackendAvailable =
                GoogleApiAvailability.getInstance()
                                .isGooglePlayServicesAvailable(
                                        ContextUtils.getApplicationContext(),
                                        MIN_GMS_VERSION_NUMBER_WITH_CODE_BROWSER_BACKEND)
                        == ConnectionResult.SUCCESS;
"""

NEW_PROBE = """        // Cobalt: WebOTP needs Play Services and Cobalt has none (0013), so the
        // answer is false without asking. Asking is what broke:
        // GoogleApiAvailability validates the caller's manifest first and
        // throws GooglePlayServicesMissingManifestValueException on an app that
        // never declared the GMS version tag -- fatal, on the startup path.
        boolean isVerificationBackendAvailable = false;
"""

OLD_CONSENT = """        if (mBackend == GmsBackend.AUTO || mBackend == GmsBackend.USER_CONSENT) {
            mUserConsentReceiver = new SmsUserConsentReceiver(this, mContext);
        }
"""

NEW_CONSENT = """        // Cobalt: the user-consent backend is SmsRetriever, which is also Play
        // Services, so it is skipped for the same reason as the verification
        // backend above. Both receivers stay null, which the rest of this class
        // already handles -- every use of them is null-guarded, because a
        // device without the backend is a case upstream has to support.
"""

DEAD_IMPORTS = (
    "import com.google.android.gms.common.ConnectionResult;\n",
    "import com.google.android.gms.common.GoogleApiAvailability;\n",
)

# The minimum GMS version the probe compared against. Its only reader was the
# probe, and Chromium builds errorprone with --warnings-as-errors:
#
#   SmsProviderGms.java:36: warning: [UnusedVariable] The field
#   'MIN_GMS_VERSION_NUMBER_WITH_CODE_BROWSER_BACKEND' is never read.
DEAD_CONSTANT = ("    private static final int "
                 "MIN_GMS_VERSION_NUMBER_WITH_CODE_BROWSER_BACKEND = "
                 "202990000;\n")


def main() -> int:
    path = SRC / REL
    if not path.exists():
        print(f"missing {REL}", file=sys.stderr)
        return 1

    text = path.read_text(encoding="utf-8")
    if DONE in text:
        print("  SmsProviderGms.java (WebOTP)                             "
              "already applied")
        return 0

    for name, old in (("the availability probe", OLD_PROBE),
                      ("the user-consent receiver", OLD_CONSENT)):
        if old not in text:
            print(f"  SmsProviderGms.java: {name} changed shape; refusing to "
                  "guess", file=sys.stderr)
            return 1
        if text.count(old) != 1:
            print(f"  SmsProviderGms.java: {name} appears {text.count(old)} "
                  "times; the anchor is ambiguous", file=sys.stderr)
            return 1

    text = text.replace(OLD_PROBE, NEW_PROBE, 1)
    text = text.replace(OLD_CONSENT, NEW_CONSENT, 1)

    # The imports and the version constant were only read by the probe, and
    # Chromium's Java build treats an unused import or field as an error.
    for dead in DEAD_IMPORTS + (DEAD_CONSTANT,):
        if dead not in text:
            print(f"  SmsProviderGms.java: expected declaration missing: "
                  f"{dead.strip()}", file=sys.stderr)
            return 1
        text = text.replace(dead, "", 1)

    path.write_bytes(text.replace("\r\n", "\n").encode("utf-8"))
    print("  SmsProviderGms.java (WebOTP)                             patched")
    print("\nWebOTP no longer asks Play Services anything; both SMS receivers "
          "stay null.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

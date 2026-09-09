# 0012 — Ship our own password store, with no Google Play Services

**Status:** accepted; framing superseded by [0015](0015-passwords-local-store-and-system-autofill.md)
**Date:** 2026-09-09

> **Note added 2026-09-10.** The finding below stands: without GMS, Cobalt has
> no password manager at all. The proposed answer -- "ship our own store" --
> turned out to overstate the work. Chromium's store is complete and disabled
> on Android by a single GN argument, and the third-party autofill path is
> already plumbed. See [0015](0015-passwords-local-store-and-system-autofill.md).

## Context

Cobalt targets devices without Google Play Services, and does not want to depend
on it. That turns out to disable Chromium's Android password manager outright —
not degrade it.

`chrome/browser/password_manager/android/password_manager_android_util.cc`:

```cpp
bool IsPasswordManagerAvailable(const PrefService* prefs,
                                bool is_internal_backend_present) {
  if (!is_internal_backend_present) {
    return false;
  }
  if (!HasMinGmsVersionForFullUpmSupport()) {
    return false;
  }
  ...
}
```

Two gates, and **an open-source build fails the first one before GMS is even
considered.** `IsInternalBackendPresent()` calls through JNI to
`PasswordManagerBackendSupportHelper.isBackendPresent()`, whose base
implementation is:

```java
public boolean isBackendPresent() {
    return false;
}
```

The only other implementation in the tree is
`PasswordManagerBackendSupportHelperUpstreamImpl`, an empty class whose comment
says *"Downstream provides an actual implementation via ServiceLoader/@ServiceImpl."*
That downstream is Google-internal and closed source. So the answer is `false`
in any build we can make, on any device, GMS or not.

Chrome's Android password manager was migrated to the Unified Password Manager,
which stores credentials in Play Services rather than in Chrome. The local
SQLite store still exists — `login_database.cc` is present — but the wiring that
reaches it on Android is gone, and `MaybeDeleteLoginDatabases()` **deletes it**.
There is a whole `pwm_disabled/` directory implementing the fallback experience:
export your passwords to CSV, and no password manager.

## Decision

Cobalt ships its own password store, backed by Chromium's own
`LoginDatabase` — the same code desktop Chromium uses — and never calls Play
Services.

This is not a workaround for a missing dependency. A browser that cannot save a
password is not a browser people will use, and the alternative on offer is to
hand credentials to a service the project has deliberately excluded.

## Shape of the work

Not yet implemented; recorded now because it is a Stage 7 item with real size,
and because the *reason* is easy to lose:

1. **Restore the built-in backend on Android.** Desktop builds the store from
   `PasswordStoreBuiltInBackend` over `LoginDatabase`. Android's factory picks
   the Android backend instead. The choice must be made to fall back rather than
   to disable.
2. **Stop the deletion.** `MaybeDeleteLoginDatabases()` must not run, or must
   not delete, when the built-in backend is the one in use. Getting this wrong
   destroys user data rather than merely failing.
3. **Re-enable the settings UI.** The password settings surface routes into the
   Play Services UI; it needs to reach the built-in store instead.
4. **Encryption at rest.** Desktop uses OSCrypt, backed by a platform keystore.
   The Android equivalent needs deciding — Android Keystore is the obvious
   candidate, and this is the part that deserves the most care, because getting
   it wrong means plaintext credentials on disk.

Point 4 is the one that decides the schedule. The rest is wiring.

## Consequences

- No Play Services dependency anywhere in the password path.
- Passwords are local to the device and do not sync. That is a real feature loss
  against Chrome, and the honest trade for not depending on Google.
- This diverges from upstream in a subsystem upstream is actively changing, so
  it carries permanent rebase cost — it belongs in the same category as the
  extension work rather than with the small patches.
- Import/export via CSV becomes more important, since it is the only migration
  path in and out.

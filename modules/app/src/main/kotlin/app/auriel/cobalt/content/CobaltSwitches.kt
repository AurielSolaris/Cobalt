package app.auriel.cobalt.content

/**
 * Chromium command-line switches Cobalt always runs with.
 *
 * Each one turns off something Chromium starts on its own that Cobalt has no
 * use for, and each was found by running Cobalt without Google Play Services
 * (tools/build/strip-gms-aar.py): the thing reached for Play Services at
 * startup, which on an ordinary phone means it contacted Google for a feature
 * Cobalt does not have. See docs/gms-removal.md, "What Cobalt uses".
 *
 * Switches rather than patches because Chromium already has the off switch,
 * and a switch costs no rebuild and no rebase.
 */
internal val CobaltSwitches = listOf(
    // Sync. Cobalt has no Google sign-in and no sync, but the sync service
    // starts anyway. Turning it off did not stop the two Firebase InstanceID
    // token requests seen at startup, whose source is still being traced
    // (docs/gms-removal.md); it stays off because sync is not something
    // Cobalt offers.
    "disable-sync",
)

package app.auriel.cobalt.content

import android.app.Application
import org.chromium.base.ApplicationStatus
import org.chromium.base.ContextUtils

/**
 * The `Application`, and the earliest point Chromium can be told about the app.
 *
 * Two of Chromium's globals have to be set before the first Activity exists,
 * and neither can be moved later:
 *
 *  - **[ContextUtils.initApplicationContext]** — everything in Chromium's Java
 *    reads it, including code that runs while the library loads.
 *  - **[ApplicationStatus.initialize]** — this registers an
 *    `ActivityLifecycleCallbacks` on the `Application`, and callbacks only fire
 *    for Activities created *after* they are registered.
 *
 * That second one is why this class exists. Doing it from the first Activity's
 * composition, as [ChromiumStartup] originally did, registers the listener one
 * step too late: the Activity that triggered it has already been created, so
 * Chromium never sees it, and the first thing to ask about it asserts.
 *
 *     java.lang.AssertionError: Found untracked Activity:
 *     app.auriel.cobalt.content.ChromiumPageActivity@c9c2e07
 *     isDestroyed=false isFinishing=false
 *
 * The message is exact and worth reading literally: not "no Activity" but an
 * Activity Chromium has no record of. Chrome does the same thing in the same
 * place, in `SplitCompatApplication.onCreate`.
 *
 * [ChromiumStartup] still calls both, for the case where no `Application` of
 * Cobalt's ran at all — a test runner, or a build configured with a different
 * one. It guards the second call rather than repeating it: `initialize` opens
 * with `assert !isInitialized()` and throws on a second call, which is a thing
 * this got wrong once and saw on screen as
 * `startup failed at ApplicationStatus.initialize`.
 *
 * Everything else stays out of here. An `Application.onCreate` runs before the
 * first frame of every launch, so work put here is latency every user pays;
 * loading the native library and starting the browser process stay where they
 * are, in [ChromiumStartup], on the first screen that needs them.
 */
class CobaltApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ContextUtils.initApplicationContext(this)
        ApplicationStatus.initialize(this)
    }
}

package app.auriel.cobalt.content

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.app.Application
import org.chromium.base.ApplicationStatus
import org.chromium.base.ContextUtils
import org.chromium.base.PathUtils
import org.chromium.base.library_loader.LibraryLoader
import org.chromium.base.library_loader.LibraryProcessType
import org.chromium.content_public.browser.BrowserStartupController
import org.chromium.ui.base.ResourceBundle

/**
 * Starts Chromium's browser process from Cobalt's Gradle app.
 *
 * This is the join. Everything either side of it has been proven separately —
 * Compose draws over the content surface, the `dist_aar` builds and carries
 * `libchrome.so`, Gradle consumes it — and this is the first thing that
 * actually runs Chromium from code Cobalt compiled.
 *
 * It is also the test that settles a question two earlier attempts could not.
 * `libchrome.so`'s JNI registration is generated from `chrome_public_apk`'s
 * Java, which this app does not carry. Building a separate `libcobalt` with
 * `java_targets` pointed at the AAR produced a byte-identical registration, so
 * that was not a fix. jni_zero defaults to `add_stubs_for_missing_jni`, so it
 * may not be a problem either. **Only running it answers that**, and running it
 * is what this does. See docs/shell-integration.md.
 *
 * ## Order matters
 *
 * Chromium's startup is a sequence with real ordering constraints, not a set of
 * independent calls:
 *
 * 1. [ContextUtils.initApplicationContext] — everything below reads it.
 * 2. [PathUtils.setPrivateDataDirectorySuffix] — must happen before anything
 *    asks for a path, and Chromium asks early.
 * 3. [ResourceBundle.setAvailablePakLocales] — which locales this APK has a
 *    `.pak` for. Chromium reads it while the browser process starts and aborts
 *    if nothing was ever registered.
 * 4. [LibraryLoader] — loads `libchrome.so` and runs its JNI registration.
 *    This is where a registration mismatch would surface.
 * 5. [BrowserStartupController.startBrowserProcessesAsync] — the browser
 *    process proper, asynchronously, on the UI thread.
 *
 * ## What this deliberately does not do
 *
 * No `ContentView`, no `WebContents`, no navigation. Startup is one question
 * and rendering is another, and answering them in the same step would leave
 * "it did not work" ambiguous between a JNI problem, a missing asset and a
 * surface problem. [ContentSurface] already covers the third.
 */
object ChromiumStartup {

    /**
     * Chromium's private data directory suffix.
     *
     * Chrome uses "chrome"; this is Cobalt's own so the two can never collide
     * on a device that has both, which is exactly the state this project's own
     * test device is in.
     */
    private const val PRIVATE_DATA_SUFFIX = "cobalt"

    private val _state = MutableStateFlow<State>(State.NotStarted)
    val state: StateFlow<State> = _state.asStateFlow()

    sealed interface State {
        data object NotStarted : State

        /** The native library is loaded; the browser process is coming up. */
        data object Starting : State

        data object Ready : State

        /**
         * Startup failed.
         *
         * [stage] says how far it got, which is the difference between "the
         * library would not load" and "the library loaded and the browser
         * process refused to start" — two very different problems.
         */
        data class Failed(val stage: String, val cause: Throwable?) : State
    }

    /**
     * Which locales this APK actually has a `.pak` for, read from the APK.
     *
     * Chromium will not start without this list. `ResourceBundle` is documented
     * to require it — "clients MUST call either `setAvailablePakLocales` or
     * `setNoAvailableLocalePaks`" — and Chrome's own call site is
     * `SplitCompatApplication`, passing the generated `ProductConfig.LOCALES`.
     * That class is generated per-APK, like `BuildConfig` and `NativeLibraries`,
     * so it cannot arrive in the AAR, and without it startup dies in C++ with a
     * message that names neither the list nor the class:
     *
     *     [FATAL:chrome/browser/chrome_resource_bundle_helper.cc:91]
     *     Check failed: !actual_locale.empty(). Locale could not be found for
     *
     * Reading the asset directory is better than transcribing `ProductConfig`
     * here. The list would otherwise be a second copy of a fact the APK already
     * states, and a copy that silently goes stale the first time the locale set
     * changes — into this same fatal, or worse, into a locale that resolves to
     * a `.pak` that is not there.
     *
     * The gendered variants are excluded. They are a suffix on the base locale
     * (`af.pak`, `af_FEMININE.pak`), and `getLocalePakResourcePath` appends the
     * suffix itself from the `Gender` it is passed; listing them as locales in
     * their own right would offer Chromium `af_NEUTER` as a UI language.
     */
    private fun pakLocales(context: Context): Array<String> {
        val names = context.assets.list("locales").orEmpty()
        val locales = names
            .filter { it.endsWith(".pak") }
            .map { it.removeSuffix(".pak") }
            .filterNot { GENDER_SUFFIXES.any(it::endsWith) }
            .distinct()
            .sorted()
        check(locales.isNotEmpty()) {
            "no locale .pak files in assets/locales; the export step " +
                "(tools/build/export-aar.sh) did not run or the APK dropped them"
        }
        return locales.toTypedArray()
    }

    private val GENDER_SUFFIXES = listOf("_FEMININE", "_MASCULINE", "_NEUTER")

    /**
     * Brings up the browser process. Safe to call more than once; only the
     * first call does anything.
     *
     * Must be called on the UI thread: `BrowserStartupController` requires it,
     * and says so by throwing rather than by misbehaving quietly.
     */
    fun start(context: Context, onReady: () -> Unit = {}) {
        if (_state.value != State.NotStarted) {
            if (_state.value == State.Ready) onReady()
            return
        }
        _state.value = State.Starting

        val app = context.applicationContext

        try {
            ContextUtils.initApplicationContext(app)
        } catch (t: Throwable) {
            _state.value = State.Failed("ContextUtils.initApplicationContext", t)
            return
        }

        try {
            // Chrome's own Application subclass does this; Cobalt's does not
            // subclass it, so nothing had. Without it Chromium's Java asserts
            // the moment anything asks whether an Activity is visible:
            //
            //   java.lang.AssertionError
            //     at ApplicationStatus.hasVisibleActivities
            //     at UmaSessionStats.hasVisibleActivity
            //
            // It has to happen before the browser process starts, because
            // Chrome's post-startup Java asks immediately.
            // Guarded, not unconditional: initialize() opens with
            // `assert !isInitialized()`, so a second call throws rather
            // than doing nothing. CobaltApplication normally gets here
            // first -- it has to, because the listener it registers only
            // sees Activities created after it -- and this remains for
            // the cases where no Application ran, such as a test runner.
            if (!ApplicationStatus.isInitialized()) {
                (app as? Application)?.let { ApplicationStatus.initialize(it) }
            }
        } catch (t: Throwable) {
            _state.value = State.Failed("ApplicationStatus.initialize", t)
            return
        }

        try {
            // Before any Chromium code asks for a path, which several do during
            // library load.
            PathUtils.setPrivateDataDirectorySuffix(PRIVATE_DATA_SUFFIX)
        } catch (t: Throwable) {
            _state.value = State.Failed("PathUtils.setPrivateDataDirectorySuffix", t)
            return
        }

        try {
            ResourceBundle.setAvailablePakLocales(pakLocales(app))
        } catch (t: Throwable) {
            _state.value = State.Failed("ResourceBundle.setAvailablePakLocales", t)
            return
        }

        try {
            // Loads libchrome.so and runs JNI registration. If the AAR's Java
            // and the library's registration disagree, this is where it shows.
            LibraryLoader.getInstance()
                .setLibraryProcessType(LibraryProcessType.PROCESS_BROWSER)
            LibraryLoader.getInstance().ensureInitialized()
        } catch (t: Throwable) {
            _state.value = State.Failed("LibraryLoader.ensureInitialized", t)
            return
        }

        try {
            BrowserStartupController.getInstance().startBrowserProcessesAsync(
                LibraryProcessType.PROCESS_BROWSER,
                /* startGpuProcess= */ false,
                /* startMinimalBrowser= */ false,
                /* singleProcess= */ false,
                // Startup tasks are flushed by the controller rather than
                // deferred to an idle callback. Cobalt has nothing else to do
                // while the browser comes up, and deferring only makes a
                // failure arrive later and further from its cause.
                /* scheduleFlushStartupTasks= */ true,
                object : BrowserStartupController.StartupCallback {
                    override fun onSuccess() {
                        _state.value = State.Ready
                        onReady()
                    }

                    override fun onFailure() {
                        // No throwable: the failure happened in C++ and arrives
                        // as a bare callback. The logcat around it is the only
                        // detail there is.
                        _state.value = State.Failed("startBrowserProcessesAsync", null)
                    }
                },
            )
        } catch (t: Throwable) {
            _state.value = State.Failed("startBrowserProcessesAsync (threw)", t)
        }
    }
}

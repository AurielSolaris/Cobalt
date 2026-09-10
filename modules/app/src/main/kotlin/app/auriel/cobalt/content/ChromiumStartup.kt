package app.auriel.cobalt.content

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.chromium.base.ContextUtils
import org.chromium.base.PathUtils
import org.chromium.base.library_loader.LibraryLoader
import org.chromium.base.library_loader.LibraryProcessType
import org.chromium.content_public.browser.BrowserStartupController

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
 * 3. [LibraryLoader] — loads `libchrome.so` and runs its JNI registration.
 *    This is where a registration mismatch would surface.
 * 4. [BrowserStartupController.startBrowserProcessesAsync] — the browser
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
            // Before any Chromium code asks for a path, which several do during
            // library load.
            PathUtils.setPrivateDataDirectorySuffix(PRIVATE_DATA_SUFFIX)
        } catch (t: Throwable) {
            _state.value = State.Failed("PathUtils.setPrivateDataDirectorySuffix", t)
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

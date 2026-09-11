package app.auriel.cobalt.content

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import app.auriel.cobalt.browser.engine.BrowserEngine
import app.auriel.cobalt.browser.engine.EngineSession
import app.auriel.cobalt.browser.engine.ShellEngine
import app.auriel.cobalt.core.net.Url
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Chromium as the shell's engine. Found by name from `ShellEngines.create`.
 *
 * Starts the browser process, and builds the [ChromiumEngine] once it is
 * running: an engine cannot exist earlier, because creating one goes straight
 * to native.
 */
class ChromiumShellEngine(private val activity: ComponentActivity) : ShellEngine {

    private val _status = MutableStateFlow<ShellEngine.Status>(ShellEngine.Status.Starting)
    override val status: StateFlow<ShellEngine.Status> = _status.asStateFlow()

    private var chromium: ChromiumEngine? = null

    override val engine: BrowserEngine
        get() = checkNotNull(chromium) { "the browser process is not running yet" }

    // Needs an off-the-record Profile; see ChromiumEngine.createSession.
    override val supportsIncognito = false
    override val extensionsUrl = "chrome://extensions"

    init {
        ChromiumStartup.start(activity)
        activity.lifecycleScope.launch {
            ChromiumStartup.state.collect { state ->
                when (state) {
                    ChromiumStartup.State.Ready -> if (chromium == null) {
                        chromium = ChromiumEngine(activity)
                        _status.value = ShellEngine.Status.Ready
                    }
                    is ChromiumStartup.State.Failed -> _status.value =
                        ShellEngine.Status.Failed(
                            "Startup failed at ${state.stage}" +
                                (state.cause?.let { ": $it" } ?: ""),
                        )
                    else -> Unit
                }
            }
        }
    }

    /**
     * Chromium's own pages (`chrome://`, `about:`) pass through as typed; they
     * are how the Extensions section works. Everything else goes through the
     * same normalisation as the document engine, so a bare host becomes https.
     */
    override fun normalize(typed: String): String? {
        val trimmed = typed.trim()
        if (INTERNAL.any { trimmed.startsWith(it, ignoreCase = true) }) return trimmed
        return Url.normalize(trimmed)?.toString()
    }

    @Composable
    override fun Page(session: EngineSession, modifier: Modifier) {
        AndroidView(
            modifier = modifier.fillMaxSize(),
            factory = { context -> (engine as ChromiumEngine).createContentContainer(context) },
        )
    }

    override suspend fun capturePage(session: EngineSession): Bitmap? =
        (engine as ChromiumEngine).capture(session, activity.cacheDir)

    override fun destroy() {
        chromium?.shutdown()
        chromium = null
    }

    private companion object {
        val INTERNAL = listOf("chrome://", "about:")
    }
}

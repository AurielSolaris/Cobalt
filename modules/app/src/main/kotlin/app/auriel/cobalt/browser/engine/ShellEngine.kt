package app.auriel.cobalt.browser.engine

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.auriel.cobalt.core.net.OkHttpPageLoader
import app.auriel.cobalt.core.net.Url
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the shell needs from an engine beyond [BrowserEngine]: a way to start
 * it, the one surface pages draw on, and what it can do.
 *
 * [BrowserEngine] is deliberately free of Android and Compose. This is the
 * layer above it that is not, one per Activity, so that `MainActivity` and
 * `BrowserApp` never name an engine.
 */
interface ShellEngine {

    sealed interface Status {
        data object Starting : Status
        data object Ready : Status
        data class Failed(val message: String) : Status
    }

    val status: StateFlow<Status>

    /** Only valid once [status] is [Status.Ready]. */
    val engine: BrowserEngine

    /** Whether a session can be created with `incognito = true`. */
    val supportsIncognito: Boolean

    /** Where the Extensions section goes, or null if this engine has none. */
    val extensionsUrl: String?

    /**
     * Turns what the user typed into something [EngineSession.loadUrl] takes,
     * or null if it is not an address.
     */
    fun normalize(typed: String): String? = Url.normalize(typed)?.toString()

    /**
     * The page area. Composed once and kept: which session it shows is
     * [BrowserEngine.show]'s business, not recomposition's.
     */
    @Composable
    fun Page(session: EngineSession, modifier: Modifier)

    /**
     * The visible page as a bitmap, if this engine has to produce it itself;
     * null means the window has it, and the shell copies the page area from
     * there.
     *
     * Chromium must: on Android 10+ its GPU process presents through its own
     * `SurfaceControl` layers, so neither a window copy nor a copy of the
     * `SurfaceView` has any pixels of the page (the first attempt saved a
     * blank white image; HWUI logs "Surface doesn't have any previously
     * queued frames").
     */
    suspend fun capturePage(session: EngineSession): Bitmap? = null

    fun destroy()
}

object ShellEngines {
    /**
     * Chromium if this build carries it, the 0.1.0 document engine otherwise.
     *
     * By name, because `content/` is dropped from the compilation when the AAR
     * is absent (`build.gradle.kts`), so nothing outside it can refer to it.
     */
    fun create(activity: ComponentActivity): ShellEngine {
        val chromium = try {
            Class.forName("app.auriel.cobalt.content.ChromiumShellEngine")
        } catch (_: ClassNotFoundException) {
            null
        }
        return chromium
            ?.getConstructor(ComponentActivity::class.java)
            ?.newInstance(activity) as ShellEngine?
            ?: DocumentShellEngine()
    }
}

/** The 0.1.0 pipeline as a [ShellEngine]: ready at once, drawn by Compose. */
class DocumentShellEngine : ShellEngine {
    override val status: StateFlow<ShellEngine.Status> =
        MutableStateFlow(ShellEngine.Status.Ready).asStateFlow()

    override val engine = DocumentEngine(OkHttpPageLoader())

    // It keeps nothing in any tab, so an incognito tab is no different.
    override val supportsIncognito = true
    override val extensionsUrl: String? = null

    @Composable
    override fun Page(session: EngineSession, modifier: Modifier) {
        DocumentPage(session as DocumentSession, modifier)
    }

    override fun destroy() = engine.shutdown()
}

package app.auriel.cobalt.browser.engine

import app.auriel.cobalt.core.net.FetchError
import app.auriel.cobalt.core.net.FetchResult
import app.auriel.cobalt.core.net.PageLoader
import app.auriel.cobalt.core.net.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * [BrowserEngine] over the 0.1.0 document pipeline: OkHttp, a parser, Compose.
 *
 * This exists so the seam has a real implementation rather than an aspirational
 * one. An interface with no implementor is code that looks load-bearing and is
 * not, which is a trap this project has walked into before; writing the
 * Chromium implementation against an interface nothing has ever satisfied would
 * be discovering its mistakes at the worst possible moment.
 *
 * It also keeps 0.1.0 running. The document engine is what draws pages today,
 * and it stays useful after Chromium lands — it is the reader-mode and
 * offline-document path, and it is the only engine that works in a unit test.
 *
 * The parsed [app.auriel.cobalt.core.net.Page] is deliberately **not** exposed
 * through [EngineSession]: it is this engine's vocabulary and Chromium has no
 * equivalent. The shell reaches it through [DocumentSession.page], which is a
 * narrowing the document renderer does knowingly.
 */
class DocumentEngine(
    private val loader: PageLoader,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
) : BrowserEngine {

    override fun createSession(incognito: Boolean): EngineSession =
        DocumentSession(loader, scope)

    override fun shutdown() {
        scope.cancel()
    }
}

/**
 * One tab, backed by the document pipeline.
 *
 * History is a list and an index rather than two stacks, because that is what
 * `canGoForward` needs: a forward stack that is cleared on every navigation
 * cannot answer it after going back.
 */
class DocumentSession(
    private val loader: PageLoader,
    private val scope: CoroutineScope,
) : EngineSession {

    private val _state = MutableStateFlow(SessionState())
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    /** The parsed page, for the document renderer. Null until one loads. */
    val page: StateFlow<app.auriel.cobalt.core.net.Page?> get() = _page.asStateFlow()
    private val _page = MutableStateFlow<app.auriel.cobalt.core.net.Page?>(null)

    private val history = mutableListOf<Url>()
    private var index = -1
    private var inFlight: Job? = null
    private var closed = false

    override fun loadUrl(url: String) {
        val parsed = Url.parse(url)
        if (parsed == null) {
            _state.value = _state.value.copy(
                progress = 1f,
                error = SessionError.BadUrl(url),
            )
            return
        }
        // A new navigation truncates anything ahead of the cursor, which is
        // what makes the forward button correct rather than merely present.
        if (index in history.indices) {
            while (history.size > index + 1) history.removeAt(history.size - 1)
        }
        history.add(parsed)
        index = history.size - 1
        fetch(parsed)
    }

    override fun reload() {
        history.getOrNull(index)?.let(::fetch)
    }

    override fun stop() {
        inFlight?.cancel()
        inFlight = null
        _state.value = _state.value.copy(progress = 1f)
    }

    override fun goBack(): Boolean {
        if (index <= 0) return false
        index--
        fetch(history[index])
        return true
    }

    override fun goForward(): Boolean {
        if (index >= history.size - 1) return false
        index++
        fetch(history[index])
        return true
    }

    override fun close() {
        closed = true
        inFlight?.cancel()
        inFlight = null
        _page.value = null
    }

    private fun fetch(url: Url) {
        if (closed) return
        inFlight?.cancel()
        _state.value = SessionState(
            url = url.toString(),
            title = _state.value.title.takeIf { _state.value.url == url.toString() },
            // The document pipeline has no incremental progress to report: a
            // page is fetched and then parsed. Rather than invent a fake ramp,
            // this sits at "loading" until it is done. A progress bar that lies
            // is worse than one that only knows two positions.
            progress = 0f,
            canGoBack = index > 0,
            canGoForward = index < history.size - 1,
            error = null,
            // OkHttp refuses a certificate it cannot validate, so an https
            // page that loads at all was delivered securely.
            security = if (url.isSecure) Security.Secure else Security.NotSecure,
        )
        inFlight = scope.launch {
            when (val result = loader.load(url)) {
                is FetchResult.Success -> {
                    if (closed) return@launch
                    _page.value = result.page
                    // No title here on purpose. A Page is raw text plus a
                    // content type; the title only exists once the document is
                    // parsed, which happens in the renderer. Guessing one from
                    // the URL would put a wrong title in the tab switcher, and
                    // leaving it null lets the shell fall back to the host.
                    _state.value = _state.value.copy(progress = 1f, error = null)
                }
                is FetchResult.Failure -> {
                    if (closed) return@launch
                    _page.value = null
                    _state.value = _state.value.copy(
                        progress = 1f,
                        error = result.error.toSessionError(),
                    )
                }
            }
        }
    }
}

/**
 * Narrows a [FetchError] to the distinctions the shell draws differently.
 *
 * Several errors collapse into one case on purpose. "Host not found",
 * "unreachable" and "timeout" are three ways of saying the page did not arrive,
 * and a browser that renders three different screens for them is showing the
 * user its internals. The specific text is carried in `detail` for anyone who
 * wants it.
 */
private fun FetchError.toSessionError(): SessionError = when (this) {
    is FetchError.InvalidUrl -> SessionError.BadUrl(typed)
    is FetchError.HostNotFound -> SessionError.Unreachable("host not found: $host")
    is FetchError.Unreachable -> SessionError.Unreachable(detail)
    is FetchError.SecureConnectionFailed -> SessionError.Unreachable(detail)
    is FetchError.Timeout -> SessionError.Unreachable("timed out after ${seconds}s")
    is FetchError.HttpStatus -> SessionError.HttpStatus(code)
    is FetchError.UnsupportedContent -> SessionError.Other("unsupported: $contentType")
    is FetchError.Unknown -> SessionError.Other(detail)
}

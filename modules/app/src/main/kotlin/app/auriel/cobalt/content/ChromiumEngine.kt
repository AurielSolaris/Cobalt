package app.auriel.cobalt.content

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import android.view.ViewGroup
import android.widget.FrameLayout
import app.auriel.cobalt.browser.engine.BrowserEngine
import app.auriel.cobalt.browser.engine.CertificateSummary
import app.auriel.cobalt.browser.engine.Security
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.chromium.components.page_info.CertificateChainHelper
import org.chromium.components.security_state.ConnectionSecurityLevel
import org.chromium.components.security_state.SecurityStateModel
import app.auriel.cobalt.browser.engine.EngineSession
import app.auriel.cobalt.browser.engine.SessionError
import app.auriel.cobalt.browser.engine.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.chromium.components.embedder_support.view.ContentView
import org.chromium.components.embedder_support.view.ContentViewRenderView
import org.chromium.content_public.browser.LoadUrlParams
import org.chromium.content_public.browser.Visibility
import org.chromium.content_public.browser.WebContents
import org.chromium.content_public.browser.WebContentsObserver
import org.chromium.chrome.browser.content.WebContentsFactory
import org.chromium.chrome.browser.profiles.ProfileManager
import org.chromium.ui.base.ActivityWindowAndroid
import org.chromium.ui.base.IntentRequestTracker
import org.chromium.ui.base.ViewAndroidDelegate

/**
 * [BrowserEngine] backed by Chromium, for the shell that actually ships.
 *
 * The other implementation, `DocumentEngine`, fetches with OkHttp and draws
 * with Compose. This one hands the page to Chromium and draws nothing: a
 * `WebContents` composites itself onto a `SurfaceView` through the GPU process,
 * and the only thing Cobalt's interface does is decide where that surface sits.
 *
 * ## The three objects, and why there are three
 *
 * They are easy to confuse because two of them are Views and one is invisible.
 *
 *  - **`WebContents` is the tab.** It owns the navigation history and the
 *    renderer process. It outlives any View: a background tab has a
 *    `WebContents` and no View at all, which is the whole reason a tab switcher
 *    can exist without keeping thirty surfaces alive.
 *  - **`ContentViewRenderView` is the surface.** It holds the `SurfaceView` the
 *    GPU process composites into, and it is told which `WebContents` is
 *    currently on screen — one render view, many possible tabs.
 *  - **`ContentView` is the input surface.** Touches, key events and
 *    accessibility. It is a normal View in the hierarchy and it draws nothing.
 *
 * Cobalt owns its own tab model rather than porting Chrome's `TabModel`, which
 * lives in `chrome/android` — see `docs/shell-integration.md`. A Cobalt tab is
 * a [ChromiumSession] and a Kotlin data class, and this is the half of it that
 * knows about Chromium.
 *
 * ## Requires a started browser process
 *
 * Every call here assumes [ChromiumStartup] has reached `Ready`. That is not
 * defensive programming left undone: `WebContentsFactory` goes straight to
 * native, and calling it early is a crash in C++ rather than an exception here,
 * so a null check would not buy anything. The shell starts the browser process
 * before it builds an engine.
 */
class ChromiumEngine(activity: Activity) : BrowserEngine {

    /**
     * Binds Chromium to the Activity: it is how native reaches the window for
     * anything that needs one — permission prompts, intents, the display's
     * refresh rate and rotation.
     *
     * An `ActivityWindowAndroid` rather than a bare `WindowAndroid` because
     * Chromium behaves differently when it knows it has an Activity, and Cobalt
     * always does.
     */
    private val window: ActivityWindowAndroid =
        ActivityWindowAndroid(
            activity,
            /* listenToActivityState= */ true,
            IntentRequestTracker.createFromActivity(activity),
            /* insetObserver= */ null,
            /* trackOcclusion= */ false,
        )

    /**
     * The surface, created once and reused.
     *
     * One render view is shared by every tab, and switching tabs is
     * `setCurrentWebContents`. The alternative — a surface per tab — would mean
     * a `SurfaceView` per tab, and a `SurfaceView` is a hardware compositing
     * layer: on the 4GB, 2-core device Cobalt targets, thirty of them is not a
     * tab switcher, it is an out-of-memory.
     */
    private val renderView: ContentViewRenderView =
        ContentViewRenderView(activity).apply { onNativeLibraryLoaded(window) }

    private var destroyed = false

    /**
     * The visible page, read back from Chromium's compositor.
     *
     * `writeContentBitmapToDiskAsync` is the only readback `content_public`
     * offers outside tests, and it goes through a file. The file is temporary
     * and deleted here; the shell decides where the image is kept.
     */
    suspend fun capture(session: EngineSession, scratchDir: File): Bitmap? {
        val view = (session as ChromiumSession).hostView ?: return null
        if (!view.isReady) return null
        val file = File(scratchDir, "capture-${System.nanoTime()}.png")
        val path = suspendCancellableCoroutine { continuation ->
            view.writeContentBitmapToDiskAsync(
                renderView.width,
                renderView.height,
                file.path,
            ) { written -> continuation.resume(written) }
        }
        return try {
            if (path.isNullOrEmpty()) null else BitmapFactory.decodeFile(path)
        } finally {
            file.delete()
        }
    }

    /** The session attached to [renderView], if any. At most one ever is. */
    private var shown: ChromiumSession? = null

    /**
     * The View to put on screen, with the page's own surface behind it.
     *
     * Compose hosts this through an `AndroidView`. It is deliberately a plain
     * `FrameLayout` and not a Compose type: everything inside it is drawn by
     * Chromium or by Android's input system, and wrapping it in anything
     * cleverer would only add a layer that has nothing to do.
     */
    fun createContentContainer(context: Context): FrameLayout {
        val container = FrameLayout(context)
        (renderView.parent as? ViewGroup)?.removeView(renderView)
        container.addView(
            renderView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        return container
    }

    override fun createSession(incognito: Boolean): EngineSession {
        check(!destroyed) { "engine is destroyed" }
        // Incognito is not wired up yet: it needs an off-the-record Profile and
        // a second WebContents family, which decision 0002 gives its own
        // surface. Failing loudly is better than silently handing back a
        // session that records history.
        require(!incognito) { "incognito needs an off-the-record profile (0002)" }

        // Hidden until show(): a tab opened in the background should not
        // compete with the one on screen for the renderer's priority.
        val webContents = WebContentsFactory.createWebContents(
            ProfileManager.getLastUsedRegularProfile(),
            /* initiallyHidden= */ true,
            /* initializeRenderer= */ true,
        )
        return ChromiumSession(webContents, renderView, window, onClosed = ::forget)
    }

    override fun show(session: EngineSession) {
        check(!destroyed) { "engine is destroyed" }
        require(session is ChromiumSession) { "not this engine's session: $session" }
        if (session === shown) return
        shown?.detach()
        session.attach()
        shown = session
    }

    private fun forget(session: ChromiumSession) {
        if (session === shown) shown = null
    }

    override fun shutdown() {
        if (destroyed) return
        destroyed = true
        shown = null
        renderView.destroy()
        window.destroy()
    }
}

/** One `TYPE=value` of an RFC 2253 distinguished name. */
private val RDN = Regex("""(?:^|,)\s*([A-Za-z]+)=((?:\\.|[^,])*)""")

/** One tab: a `WebContents`, its input view, and the state the shell draws. */
private class ChromiumSession(
    private val webContents: WebContents,
    private val renderView: ContentViewRenderView,
    window: ActivityWindowAndroid,
    private val onClosed: (ChromiumSession) -> Unit,
) : EngineSession {

    private var attached = false
    private var closed = false

    /** Null before the renderer has a view, which is briefly true of a new tab. */
    val hostView get() = webContents.renderWidgetHostView

    private val _state = MutableStateFlow(SessionState())
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    /**
     * Chromium reports each part of a page's identity separately, and the shell
     * needs them together.
     *
     * So nothing here builds a partial state: every callback republishes the
     * whole thing from the `WebContents`, which is the one place all of it is
     * true at the same instant. Assembling a [SessionState] field by field as
     * the callbacks arrive is how an address bar ends up showing one page's
     * title beside another page's URL.
     */
    private val observer = object : WebContentsObserver(webContents) {
        override fun loadProgressChanged(progress: Float) = publish(progress)

        /**
         * Gives Chromium's own desktop pages a phone viewport; see
         * [WEBUI_MOBILE_SCRIPT].
         *
         * In the shell rather than a Chromium patch because it is shell
         * behaviour and needs no rebuild of Chromium. It runs as soon as the
         * document element exists, before first paint.
         */
        override fun primaryMainDocumentElementAvailable() {
            if (webContents.visibleUrl?.scheme != "chrome") return
            webContents.evaluateJavaScript(WEBUI_MOBILE_SCRIPT, null)
        }
        override fun titleWasSet(title: String?) = publish()
        override fun didChangeVisibleSecurityState() = publish()
        override fun didFirstVisuallyNonEmptyPaint() = publish()

        override fun didStartNavigationInPrimaryMainFrame(
            handle: org.chromium.content_public.browser.NavigationHandle,
        ) {
            // Clears the previous failure. A navigation that has begun has not
            // failed yet, and leaving the old error on screen while a new page
            // loads is worse than showing nothing.
            _state.value = _state.value.copy(error = null)
        }

        override fun didFinishNavigationInPrimaryMainFrame(
            handle: org.chromium.content_public.browser.NavigationHandle,
        ) {
            val error = when {
                !handle.isInPrimaryMainFrame || handle.hasCommitted() -> null
                // Chromium has hundreds of net error codes and the shell draws
                // three outcomes, so the mapping is deliberately coarse rather
                // than a table that pretends to be exhaustive.
                handle.isErrorPage -> SessionError.Unreachable(handle.url?.spec)
                else -> null
            }
            publish(error = error)
        }
    }

    private val contentView: ContentView =
        ContentView.createContentView(renderView.context, webContents)

    init {
        // setDelegates is what makes a WebContents a *displayed* WebContents:
        // until it has a view delegate it has no size, no window, and nothing to
        // composite into, and it will happily navigate while rendering nowhere.
        webContents.setDelegates(
            /* productVersion= */ "",
            ViewAndroidDelegate.createBasicDelegate(contentView),
            contentView,
            window,
            WebContents.createDefaultInternalsHolder(),
        )
        publish()
    }

    /**
     * Puts this tab on the shared surface. Only [ChromiumEngine.show] calls it,
     * and it detaches the previous tab first.
     *
     * The `ContentView` moves with the tab rather than staying in the render
     * view, because it is where input goes: two of them stacked would send a
     * touch to whichever happened to be on top, not to the tab on screen.
     */
    fun attach() {
        check(!closed) { "session is closed" }
        if (attached) return
        attached = true
        renderView.addView(
            contentView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        renderView.setCurrentWebContents(webContents)
        webContents.updateWebContentsVisibility(Visibility.VISIBLE)
        contentView.requestFocus()
    }

    /** Takes this tab off the surface. It keeps loading; it is not drawn. */
    fun detach() {
        if (!attached) return
        attached = false
        webContents.updateWebContentsVisibility(Visibility.HIDDEN)
        renderView.removeView(contentView)
    }

    private fun publish(
        progress: Float = _state.value.progress,
        error: SessionError? = _state.value.error,
    ) {
        val controller = webContents.navigationController
        _state.value = SessionState(
            url = webContents.visibleUrl?.spec?.takeIf { it.isNotEmpty() },
            title = webContents.title?.takeIf { it.isNotEmpty() },
            progress = progress,
            canGoBack = controller?.canGoBack() ?: false,
            canGoForward = controller?.canGoForward() ?: false,
            error = error,
            security = security(),
        )
    }

    private fun security(): Security {
        val url = webContents.visibleUrl ?: return Security.None
        if (url.isEmpty) return Security.None
        if (url.scheme == "chrome" || url.scheme == "about") return Security.Internal
        return when (SecurityStateModel.getSecurityLevelForWebContents(webContents)) {
            ConnectionSecurityLevel.SECURE -> Security.Secure
            ConnectionSecurityLevel.DANGEROUS -> Security.Dangerous
            // WARNING is Chromium's "not secure" for http and mixed content,
            // and NONE with an http URL means the same thing to a person.
            else -> Security.NotSecure
        }
    }

    override fun certificate(): CertificateSummary? {
        val chain = CertificateChainHelper.getCertificateChain(webContents) ?: return null
        val leafDer = chain.firstOrNull() ?: return null
        val leaf = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(leafDer)) as X509Certificate
        return CertificateSummary(
            issuedTo = nameOf(leaf.subjectX500Principal.name),
            issuedBy = nameOf(leaf.issuerX500Principal.name),
            validFrom = leaf.notBefore,
            validUntil = leaf.notAfter,
            sha256 = MessageDigest.getInstance("SHA-256").digest(leafDer)
                .joinToString(":") { "%02X".format(it) },
            chainLength = chain.size,
        )
    }

    /**
     * The common name from an RFC 2253 name (`CN=example.org,O=Example,C=US`),
     * else the organisation, else all of it. By hand because `javax.naming`
     * is not on Android; escaped commas (`\,`) are honoured.
     */
    private fun nameOf(x500: String): String {
        val parts = RDN.findAll(x500).associate {
            it.groupValues[1].uppercase() to it.groupValues[2].replace("\\", "")
        }
        return parts["CN"] ?: parts["O"] ?: x500
    }

    override fun loadUrl(url: String) {
        webContents.navigationController?.loadUrl(LoadUrlParams(url))
    }

    override fun reload() {
        webContents.navigationController?.reload(/* checkForRepost= */ true)
    }

    override fun stop() {
        webContents.stop()
    }

    override fun goBack(): Boolean {
        val controller = webContents.navigationController ?: return false
        if (!controller.canGoBack()) return false
        controller.goBack()
        return true
    }

    override fun goForward(): Boolean {
        val controller = webContents.navigationController ?: return false
        if (!controller.canGoForward()) return false
        controller.goForward()
        return true
    }

    override fun close() {
        if (closed) return
        closed = true
        observer.observe(null)
        // Deliberately not detach(): that would tell a WebContents about to be
        // destroyed that it is hidden, which is work for nothing. The surface
        // keeps pointing at it until the next show(), and that is harmless only
        // because the shell always shows another tab after closing one —
        // TabModel's invariant is that some tab is always active.
        if (attached) renderView.removeView(contentView)
        attached = false
        onClosed(this)
        // A WebContents is a renderer process. Dropping the reference and
        // waiting for a garbage collector to notice is not closing a tab.
        webContents.destroy()
    }
}

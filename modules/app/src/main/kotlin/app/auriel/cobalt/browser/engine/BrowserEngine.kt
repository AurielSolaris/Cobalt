package app.auriel.cobalt.browser.engine

import kotlinx.coroutines.flow.StateFlow

/**
 * The seam between Cobalt's interface and whatever renders the page.
 *
 * Cobalt has two engines and will have them both for a while:
 *
 *  - the **document engine** of 0.1.0, which fetches with OkHttp, parses, and
 *    draws with Compose. It is a real browser for a small subset of the web and
 *    it is what runs today.
 *  - **Chromium**, which is the browser Cobalt ships. On Android it is reached
 *    through `WebContents`, hosted in the `SurfaceView` that
 *    `content/ContentSurface.kt` already stands in for.
 *
 * The point of this interface is that the shell should not know which one it is
 * talking to. Every screen, the tab switcher, the address bar and the bottom bar
 * are built against these types, so swapping the engine underneath is a matter
 * of constructing a different [BrowserEngine] rather than rewriting the
 * interface around it.
 *
 * That matters more here than the usual argument for abstraction. Chromium
 * cannot compile Compose (its `kotlinc` has no compiler-plugin support), so
 * Cobalt's interface is built in this Gradle project and Chromium arrives as an
 * AAR — see `docs/shell-integration.md`. The two halves are compiled by
 * different toolchains and can only meet at an interface like this one.
 *
 * Package note: this is `app.auriel.cobalt.browser.engine`, not
 * `app.auriel.cobalt.engine`. That one belongs to `:modules:engine`, which holds
 * the HTML parser and the *JavaScript* engine seam — `JsEngine`, `NoopEngine` —
 * a different meaning of the word. Two Gradle modules sharing a package is legal
 * and reads as a mistake, so this one is its own.
 *
 * It sits under `:modules:app` because the shell is the only consumer and the
 * Chromium-backed implementation will be Android-only. Moving it into
 * `:modules:engine` would mean adding `kotlinx-coroutines-core` there, which is
 * not currently declared — worth doing when a second consumer exists, not before.
 *
 * **Deliberately not modelled here:** anything that is Chromium's vocabulary
 * rather than a browser's. No `WebContents`, no `NavigationController`, no
 * mojo. If a concept only makes sense to one engine, it does not belong in the
 * seam; it belongs behind it.
 */
interface BrowserEngine {
    /** Creates a session. One session is one tab. */
    fun createSession(incognito: Boolean = false): EngineSession

    /**
     * Puts [session] on screen, taking whichever session was there off it.
     *
     * A session is created off screen, and the shell calls this when a tab
     * becomes the active one. For Chromium this is real work — one surface is
     * shared by every tab, and a hidden `WebContents` lets its renderer be
     * deprioritised. The document engine draws through Compose, where the shell
     * already chooses what to draw, so it has nothing to do.
     */
    fun show(session: EngineSession) {}

    /**
     * Recreates a session from what [EngineSession.saveState] returned before
     * the app last closed, off screen and **not loaded**: nothing is fetched
     * until the session is first shown, so restoring thirty tabs costs thirty
     * lists of addresses, not thirty renderer processes.
     *
     * Null when the bytes cannot be read (another engine's, or a format this
     * build no longer understands); the shell then opens the saved address in
     * a fresh session instead.
     */
    fun restoreSession(saved: ByteArray): EngineSession? = null

    /**
     * Where a page's request for a new tab goes: a link with `target=_blank`,
     * a middle click, `window.open` without an opener. The shell opens a tab
     * in its own model; an engine that cannot ask simply never calls this.
     */
    fun setNewTabHandler(handler: (url: String) -> Unit) {}

    /**
     * Releases everything the engine holds.
     *
     * Chromium's browser process outlives any single Activity, so an
     * implementation may legitimately do nothing here.
     */
    fun shutdown() {}
}

/**
 * One tab's worth of engine.
 *
 * A session owns navigation state and the content being displayed. It does not
 * own where it is displayed: the shell decides that, which is what lets a tab
 * exist while it is not on screen — the ordinary case in a tab switcher, and
 * the case Chromium is built around.
 */
interface EngineSession {
    /**
     * Everything the interface needs to draw, as one observable value.
     *
     * A single [StateFlow] rather than separate callbacks for url, title and
     * progress, because those change together and a shell that reads them
     * separately will eventually draw a title from one page beside the URL of
     * another.
     */
    val state: StateFlow<SessionState>

    /** Navigates to [url]. Implementations normalise; the shell does not. */
    fun loadUrl(url: String)

    fun reload()
    fun stop()

    /** @return true if there was somewhere to go. */
    fun goBack(): Boolean

    fun goForward(): Boolean

    /**
     * The certificate the page was served with, for the security popup; null
     * when there is none (http, internal pages) or the engine cannot say.
     */
    fun certificate(): CertificateSummary? = null

    /**
     * This tab's history (back and forward, and where it is in them) as bytes
     * only this engine reads back, through [BrowserEngine.restoreSession].
     * Null if the engine cannot, or the session is closed.
     */
    fun saveState(): ByteArray? = null

    /**
     * Deletes what the site on screen has stored on the phone (cookies,
     * local storage, databases, its own caches) and reloads it, which signs
     * you out of it. [onDone] runs once the data is gone.
     *
     * @return false if this engine keeps nothing to clear, and [onDone] will
     *     not run.
     */
    fun clearSiteData(onDone: () -> Unit): Boolean = false

    /**
     * Releases this session.
     *
     * After this the session is dead and must not be used. Closing a tab in the
     * shell must call it: for Chromium a session is a renderer process, and
     * leaking one is not a Kotlin-level leak that a garbage collector will
     * eventually notice.
     */
    fun close()
}

/**
 * What the shell draws.
 *
 * Deliberately flat and free of engine types so it can be constructed in a test
 * or a preview without an engine at all.
 */
data class SessionState(
    /** The committed URL, or null before the first navigation. */
    val url: String? = null,
    /** The page title, or null if it has none yet. */
    val title: String? = null,
    /** 0f..1f. Loading is `progress < 1f`, so there is one source of truth. */
    val progress: Float = 1f,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    /**
     * Set when the last navigation failed.
     *
     * A failure is state rather than an event because it survives rotation and
     * has to be redrawn; delivering it as a one-shot callback loses it.
     */
    val error: SessionError? = null,
    /** How the page was delivered, as the engine judges it. */
    val security: Security = Security.None,
) {
    val isLoading: Boolean get() = progress < 1f
}

/**
 * The address bar's security state.
 *
 * The engine's verdict, not a guess from the URL: `https://` with a bad
 * certificate or mixed content is not [Secure], and only the engine knows.
 */
enum class Security {
    /** Nothing loaded yet. */
    None,
    /** The browser's own page (`chrome://`), never sent over a network. */
    Internal,
    /**
     * A file on the phone, or a bundled extension's page showing one (the
     * pdf.js viewer). No network was involved, so "not secure" would be a lie.
     */
    Local,
    /** Valid certificate, nothing insecure on the page. */
    Secure,
    /** Plain http, or https with insecure content mixed in. */
    NotSecure,
    /** A certificate error or a page flagged as harmful. */
    Dangerous,
}

/** The parts of a certificate a person can check, already decoded. */
data class CertificateSummary(
    /** Who it was issued to: the subject's common name, or organisation. */
    val issuedTo: String,
    /** Who issued it. */
    val issuedBy: String,
    val validFrom: java.util.Date,
    val validUntil: java.util.Date,
    /** SHA-256 of the leaf certificate, colon-separated hex. */
    val sha256: String,
    /** Certificates in the chain, leaf included. */
    val chainLength: Int,
)

/**
 * Why a navigation failed, in terms the interface can act on.
 *
 * Not an error *code*: Chromium has hundreds and the document engine has a
 * handful, and no useful mapping exists between them. These are the distinctions
 * the shell actually draws differently.
 */
sealed interface SessionError {
    /** The address could not be understood. */
    data class BadUrl(val typed: String) : SessionError

    /** The host could not be reached — DNS, connection, timeout. */
    data class Unreachable(val detail: String?) : SessionError

    /** The server answered, unhappily. */
    data class HttpStatus(val code: Int) : SessionError

    /** Refused by something in the browser — a content blocker, a policy. */
    data class Blocked(val detail: String?) : SessionError

    /** Anything else, carried through rather than swallowed. */
    data class Other(val detail: String?) : SessionError
}

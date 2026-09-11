package app.auriel.cobalt.browser.tabs

import app.auriel.cobalt.browser.engine.BrowserEngine
import app.auriel.cobalt.browser.engine.EngineSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One tab: an engine session and the little the shell knows beyond it. */
class BrowserTab(
    val id: Long,
    val incognito: Boolean,
    val session: EngineSession,
)

/** The tab list and which tab is on screen, as one value. */
data class TabList(
    val tabs: List<BrowserTab>,
    val activeId: Long,
) {
    val active: BrowserTab get() = tabs.first { it.id == activeId }

    val normalCount: Int get() = tabs.count { !it.incognito }
    val incognitoCount: Int get() = tabs.count { it.incognito }
}

/**
 * Cobalt's tab model: create, close and switch, over any [BrowserEngine].
 *
 * Chrome's `TabModel` lives in `chrome/android`, which Cobalt does not take —
 * see `docs/shell-integration.md`. This is the replacement, and it is small on
 * purpose: a list of sessions and an active id. Titles, URLs and progress are
 * not copied in here; they live on each session's [EngineSession.state], where
 * they are always current, and a second copy is one that can go stale.
 *
 * **Invariant: there is always an active tab.** Closing the last one opens a
 * fresh one rather than leaving a browser with nowhere to type, and the engine
 * is told which session to show after every change. `ChromiumEngine` depends on
 * this — closing the tab on screen leaves its surface pointing nowhere until
 * the next [BrowserEngine.show].
 *
 * Not thread-safe; every call is on the main thread, where Chromium requires
 * its own calls to be anyway.
 */
class TabModel(private val engine: BrowserEngine) {

    private var nextId = 1L
    private var destroyed = false

    private val _state: MutableStateFlow<TabList>
    val state: StateFlow<TabList>

    init {
        val first = create(incognito = false)
        _state = MutableStateFlow(TabList(listOf(first), first.id))
        state = _state.asStateFlow()
        engine.show(first.session)
    }

    /** Opens a tab after the active one and makes it active. */
    fun newTab(incognito: Boolean = false, url: String? = null): BrowserTab {
        checkAlive()
        val tab = create(incognito)
        url?.let(tab.session::loadUrl)
        val current = _state.value
        // After the active tab, not at the end: a tab opened from a page
        // belongs beside it, and that is where the switcher should show it.
        val at = current.tabs.indexOfFirst { it.id == current.activeId } + 1
        val tabs = current.tabs.toMutableList().apply { add(at, tab) }
        commit(TabList(tabs, tab.id))
        return tab
    }

    fun select(id: Long) {
        checkAlive()
        val current = _state.value
        if (current.activeId == id || current.tabs.none { it.id == id }) return
        commit(current.copy(activeId = id))
    }

    fun close(id: Long) {
        checkAlive()
        val current = _state.value
        val index = current.tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        val remaining = current.tabs.filterNot { it.id == id }

        val next = when {
            remaining.isEmpty() -> create(incognito = false).let { TabList(listOf(it), it.id) }
            current.activeId != id -> current.copy(tabs = remaining)
            // The neighbour to the left, which is where the eye is.
            else -> TabList(remaining, remaining[(index - 1).coerceIn(remaining.indices)].id)
        }
        // Show the successor before destroying the closed session, so the
        // surface is never pointing at a destroyed WebContents.
        commit(next)
        current.tabs[index].session.close()
    }

    /** Closes every tab, or every incognito tab. */
    fun closeAll(incognitoOnly: Boolean = false) {
        checkAlive()
        val current = _state.value
        val closing = if (incognitoOnly) current.tabs.filter { it.incognito } else current.tabs
        if (closing.isEmpty()) return
        val kept = current.tabs - closing.toSet()

        val next = when {
            kept.isEmpty() -> create(incognito = false).let { TabList(listOf(it), it.id) }
            kept.any { it.id == current.activeId } -> current.copy(tabs = kept)
            else -> TabList(kept, kept.first().id)
        }
        commit(next)
        closing.forEach { it.session.close() }
    }

    /**
     * Closes every session. The model is unusable afterwards.
     *
     * Must run before the engine shuts down: a session is a renderer process,
     * and the engine owns the surface it draws into.
     */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        _state.value.tabs.forEach { it.session.close() }
    }

    private fun create(incognito: Boolean) =
        BrowserTab(nextId++, incognito, engine.createSession(incognito))

    private fun commit(next: TabList) {
        val previousActive = _state.value.activeId
        _state.value = next
        if (next.activeId != previousActive) engine.show(next.active.session)
    }

    private fun checkAlive() = check(!destroyed) { "tab model is destroyed" }
}

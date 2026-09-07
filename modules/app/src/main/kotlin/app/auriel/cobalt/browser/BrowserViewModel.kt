package app.auriel.cobalt.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.auriel.cobalt.core.net.FetchError
import app.auriel.cobalt.core.net.FetchResult
import app.auriel.cobalt.core.net.OkHttpPageLoader
import app.auriel.cobalt.core.net.PageLoader
import app.auriel.cobalt.core.net.Url
import app.auriel.cobalt.core.net.resolveUrl
import app.auriel.cobalt.engine.html.Document
import app.auriel.cobalt.engine.html.HtmlParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a tab's content area is showing. */
sealed interface Content {

    /** The tab is new and has not been navigated yet — it shows the home page. */
    data object Home : Content

    /** A fetch is in flight. */
    data class Loading(val url: Url) : Content

    /** A parsed document, ready to render. */
    data class Loaded(val url: Url, val document: Document) : Content

    /** The fetch failed, with a reason the user can act on. */
    data class Failed(val url: Url?, val error: FetchError) : Content
}

/**
 * One tab.
 *
 * [addressText] is what is in the text field, which is not the same as the
 * current page: it changes as the user types and is reset to the page URL when
 * a navigation completes.
 *
 * There is no history stack here. Back, forward, and session restore arrive in
 * Stage 7 on top of Chromium's navigation controller, and building a second one
 * now would mean building it twice.
 */
data class Tab(
    val id: Long,
    val incognito: Boolean = false,
    val addressText: String = "",
    val content: Content = Content.Home,
    val title: String? = null,
) {
    val isLoading: Boolean get() = content is Content.Loading

    val url: Url? get() = when (content) {
        is Content.Loading -> content.url
        is Content.Loaded -> content.url
        is Content.Failed -> content.url
        Content.Home -> null
    }

    /** What the tab calls itself in the switcher. */
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: url?.host
            ?: if (incognito) "New incognito tab" else "New tab"

    val displaySubtitle: String get() = url?.host ?: "cobalt://home"
}

/**
 * Which section is on screen.
 *
 * The first four are the bottom bar. [Bookmarks] and [Settings] are reached from
 * the menu in the address bar instead: bookmarks are a property of a site, so
 * they belong beside the address that identifies one, not in the same row as the
 * tab switcher. That frees the fourth bar slot for extensions, which is the
 * feature Cobalt exists to bring back.
 */
enum class Section {
    Home,
    Extensions,
    Tabs,
    Downloads,
    Bookmarks,
    Settings,
}

data class BrowserState(
    val tabs: List<Tab> = listOf(Tab(id = 1L)),
    val activeTabId: Long = 1L,
    val section: Section = Section.Home,
) {
    val activeTab: Tab get() = tabs.firstOrNull { it.id == activeTabId } ?: tabs.first()

    val normalTabCount: Int get() = tabs.count { !it.incognito }
    val incognitoTabCount: Int get() = tabs.count { it.incognito }
}

/**
 * Drives navigation and the tab list.
 *
 * Tabs here are a list of documents in memory and nothing more — no process per
 * tab, no persistence, no eviction. That is the honest shape for 0.1.0: the real
 * model arrives with Chromium's in Stage 7, and this exists so the shell around
 * it is not designed for a single page and then rebuilt.
 */
class BrowserViewModel(
    private val loader: PageLoader = OkHttpPageLoader(),
) : ViewModel() {

    private val _state = MutableStateFlow(BrowserState())
    val state: StateFlow<BrowserState> = _state.asStateFlow()

    private val navigations = HashMap<Long, Job>()
    private var nextTabId = 2L

    // --- Sections -----------------------------------------------------------

    fun onSectionSelected(section: Section) {
        _state.update { it.copy(section = section) }
    }

    // --- Tabs ---------------------------------------------------------------

    fun onNewTab(incognito: Boolean = false) {
        val tab = Tab(id = nextTabId++, incognito = incognito)
        _state.update {
            it.copy(tabs = it.tabs + tab, activeTabId = tab.id, section = Section.Home)
        }
    }

    fun onTabSelected(id: Long) {
        _state.update { it.copy(activeTabId = id, section = Section.Home) }
    }

    fun onCloseTab(id: Long) {
        navigations.remove(id)?.cancel()

        _state.update { state ->
            val remaining = state.tabs.filterNot { it.id == id }
            if (remaining.isEmpty()) {
                // Closing the last tab leaves a fresh one rather than an empty
                // browser with nowhere to type.
                val replacement = Tab(id = nextTabId++)
                return@update state.copy(tabs = listOf(replacement), activeTabId = replacement.id)
            }

            val activeId = if (state.activeTabId == id) {
                // Prefer the neighbour to the left, which is where the eye is.
                val closedIndex = state.tabs.indexOfFirst { it.id == id }
                remaining[(closedIndex - 1).coerceIn(remaining.indices)].id
            } else {
                state.activeTabId
            }

            state.copy(tabs = remaining, activeTabId = activeId)
        }
    }

    fun onCloseAllTabs(incognitoOnly: Boolean = false) {
        _state.update { state ->
            val kept = if (incognitoOnly) state.tabs.filterNot { it.incognito } else emptyList()
            for (tab in state.tabs - kept.toSet()) navigations.remove(tab.id)?.cancel()

            if (kept.isEmpty()) {
                val replacement = Tab(id = nextTabId++)
                state.copy(tabs = listOf(replacement), activeTabId = replacement.id)
            } else {
                state.copy(tabs = kept, activeTabId = kept.first().id)
            }
        }
    }

    // --- Navigation ---------------------------------------------------------

    fun onAddressChanged(text: String) {
        updateActive { it.copy(addressText = text) }
    }

    /** Navigate the active tab to whatever is in its address bar. */
    fun onGo() {
        navigateTo(_state.value.activeTab.addressText)
    }

    /** Navigate the active tab to a typed or intent-supplied address. */
    fun navigateTo(typed: String) {
        val tabId = _state.value.activeTabId
        val url = Url.normalize(typed)

        if (url == null) {
            navigations.remove(tabId)?.cancel()
            updateActive {
                it.copy(content = Content.Failed(null, FetchError.InvalidUrl(typed.trim())), title = null)
            }
            _state.update { it.copy(section = Section.Home) }
            return
        }

        load(tabId, url)
    }

    /** Follow a link from the rendered page, resolved against the tab's URL. */
    fun onLinkClicked(href: String) {
        val tab = _state.value.activeTab
        val base = tab.url
        val target = if (base != null) resolveUrl(base, href) else Url.normalize(href)

        // A mailto: or javascript: link. Declining silently is the honest
        // behaviour for 0.1.0: there is no handler to give it to, and an error
        // screen would imply the page was broken when it is not.
        if (target != null) load(tab.id, target)
    }

    fun onReload() {
        val tab = _state.value.activeTab
        tab.url?.let { load(tab.id, it) }
    }

    fun onStop() {
        val tabId = _state.value.activeTabId
        navigations.remove(tabId)?.cancel()
        updateActive { tab ->
            val url = tab.url
            if (tab.content is Content.Loading && url != null) {
                tab.copy(content = Content.Failed(url, FetchError.Unknown("Loading was stopped.")))
            } else {
                tab
            }
        }
    }

    private fun load(tabId: Long, url: Url) {
        navigations.remove(tabId)?.cancel()

        updateTab(tabId) {
            it.copy(addressText = url.displayForm(), content = Content.Loading(url), title = null)
        }
        _state.update { it.copy(section = Section.Home) }

        navigations[tabId] = viewModelScope.launch {
            when (val result = loader.load(url)) {
                is FetchResult.Success -> {
                    val page = result.page
                    val document = if (page.contentType.isHtml) {
                        HtmlParser.parse(page.text)
                    } else {
                        // Plain text is wrapped rather than parsed, so a
                        // text/plain response containing markup displays that
                        // markup instead of rendering it.
                        HtmlParser.parse("<pre>${escapeForPre(page.text)}</pre>")
                    }

                    updateTab(tabId) {
                        it.copy(
                            addressText = page.finalUrl.displayForm(),
                            content = Content.Loaded(page.finalUrl, document),
                            title = document.title,
                        )
                    }
                }

                is FetchResult.Failure -> updateTab(tabId) {
                    it.copy(content = Content.Failed(url, result.error), title = null)
                }
            }
            navigations.remove(tabId)
        }
    }

    private fun updateActive(transform: (Tab) -> Tab) {
        updateTab(_state.value.activeTabId, transform)
    }

    private fun updateTab(id: Long, transform: (Tab) -> Tab) {
        _state.update { state ->
            state.copy(tabs = state.tabs.map { if (it.id == id) transform(it) else it })
        }
    }

    private fun escapeForPre(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

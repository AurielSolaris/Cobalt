package app.auriel.cobalt.browser

import android.graphics.Bitmap
import app.auriel.cobalt.browser.engine.EngineSession
import app.auriel.cobalt.browser.engine.SessionState
import app.auriel.cobalt.browser.engine.ShellEngine
import app.auriel.cobalt.browser.tabs.TabModel
import app.auriel.cobalt.core.net.FetchError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * One tab, as the screens see it.
 *
 * [page] is the engine's own state, passed through untouched. [addressText]
 * is what the address field shows: what the user is typing if they are, the
 * page's URL otherwise.
 */
data class Tab(
    val id: Long,
    val incognito: Boolean = false,
    val addressText: String = "",
    val page: SessionState = SessionState(),
    /** Set when the last thing typed was not an address; cleared by the next navigation. */
    val invalidAddress: FetchError.InvalidUrl? = null,
    /** A small picture of the page as it last looked on screen, for the switcher. */
    val thumbnail: Bitmap? = null,
) {
    val isLoading: Boolean get() = page.isLoading

    /** False for a tab that has never been navigated, which shows the home page. */
    val hasPage: Boolean get() = page.url != null

    /** What the tab calls itself in the switcher. */
    val displayTitle: String
        get() = page.title?.takeIf { it.isNotBlank() }
            ?: page.url?.let(::hostOf)
            ?: if (incognito) "New incognito tab" else "New tab"

    val displaySubtitle: String get() = page.url?.let(::hostOf) ?: "cobalt://home"
}

/** "https://example.org/a" → "example.org"; "chrome://extensions" → "extensions". */
private fun hostOf(url: String): String =
    url.substringAfter("://").substringBefore('/').ifEmpty { url }

/**
 * Which section is on screen.
 *
 * The first four are the bottom bar. [Bookmarks] and [Settings] are reached from
 * the address bar's menu: bookmarks are a property of a site, so they belong
 * beside the address that identifies one.
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
    val tabs: List<Tab>,
    val activeTabId: Long,
    val section: Section = Section.Home,
    val incognitoAvailable: Boolean = true,
) {
    val activeTab: Tab get() = tabs.first { it.id == activeTabId }

    val normalTabCount: Int get() = tabs.count { !it.incognito }
    val incognitoTabCount: Int get() = tabs.count { it.incognito }

    /** Whether Back has somewhere to go inside the browser. */
    val canHandleBack: Boolean get() = section != Section.Home || activeTab.page.canGoBack
}

/**
 * Drives the screens from a [TabModel] over whichever engine this build has.
 *
 * Owned by the Activity rather than a `ViewModel`: every session belongs to an
 * engine bound to this Activity's window, and a `ViewModel` outliving the
 * Activity would hold renderer processes for a window that no longer exists.
 * `MainActivity` handles configuration changes itself, so rotation does not
 * recreate it.
 */
class BrowserController(
    private val shell: ShellEngine,
    private val scope: CoroutineScope,
) {
    private val tabs = TabModel(shell.engine)
    private val section = MutableStateFlow(Section.Home)

    /** Per tab: text being typed, and the last address that failed to parse. */
    private val edits = MutableStateFlow<Map<Long, String>>(emptyMap())
    private val invalid = MutableStateFlow<Map<Long, FetchError.InvalidUrl>>(emptyMap())
    private val thumbnails = MutableStateFlow<Map<Long, Bitmap>>(emptyMap())

    /**
     * Every session's state, re-subscribed whenever the tab list changes, so
     * a title arriving in a background tab reaches the switcher.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val pages = tabs.state.flatMapLatest { list ->
        combine(list.tabs.map { it.session.state }) { it.toList() }
    }

    val state: StateFlow<BrowserState> =
        combine(pages, section, edits, invalid, thumbnails) { _, _, _, _, _ -> snapshot() }
            .stateIn(scope, SharingStarted.Eagerly, snapshot())

    private fun snapshot(): BrowserState {
        val list = tabs.state.value
        return BrowserState(
            tabs = list.tabs.map { tab ->
                val page = tab.session.state.value
                Tab(
                    id = tab.id,
                    incognito = tab.incognito,
                    addressText = edits.value[tab.id] ?: page.url.orEmpty(),
                    page = page,
                    invalidAddress = invalid.value[tab.id],
                    thumbnail = thumbnails.value[tab.id],
                )
            },
            activeTabId = list.activeId,
            section = section.value,
            incognitoAvailable = shell.supportsIncognito,
        )
    }

    private val active get() = tabs.state.value.active

    /** The engine session behind a tab, for the page surface. */
    fun session(id: Long): EngineSession = tabs.state.value.tabs.first { it.id == id }.session

    // --- Sections -----------------------------------------------------------

    fun onSectionSelected(target: Section) {
        val extensions = shell.extensionsUrl
        if (target == Section.Extensions && extensions != null) {
            // Extensions are a page Chromium serves, so the section is a tab:
            // the one already showing it, or a new one.
            val existing = tabs.state.value.tabs.firstOrNull {
                it.session.state.value.url?.startsWith(extensions) == true
            }
            if (existing != null) tabs.select(existing.id) else tabs.newTab(url = extensions)
            section.value = Section.Home
            return
        }
        section.value = target
    }

    /**
     * The home page. It is what a tab shows before it has a page, so Home is a
     * fresh tab rather than a navigation away from the current one; the page
     * you were reading stays where it was. A blank tab already on screen is
     * reused, so repeated taps do not pile up empty tabs.
     */
    fun onHome() {
        section.value = Section.Home
        if (active.session.state.value.url == null) return
        val blank = tabs.state.value.tabs.firstOrNull { it.session.state.value.url == null && !it.incognito }
        if (blank != null) tabs.select(blank.id) else tabs.newTab()
    }

    // --- Tabs ---------------------------------------------------------------

    fun onNewTab(incognito: Boolean = false) {
        if (incognito && !shell.supportsIncognito) return
        tabs.newTab(incognito)
        section.value = Section.Home
    }

    fun onTabSelected(id: Long) {
        tabs.select(id)
        section.value = Section.Home
    }

    fun onCloseTab(id: Long) {
        tabs.close(id)
        forget(id)
    }

    fun onCloseAllTabs(incognitoOnly: Boolean = false) {
        val before = tabs.state.value.tabs.map { it.id }
        tabs.closeAll(incognitoOnly)
        val after = tabs.state.value.tabs.map { it.id }.toSet()
        before.filterNot(after::contains).forEach(::forget)
    }

    private fun forget(id: Long) {
        edits.update { it - id }
        invalid.update { it - id }
        thumbnails.update { it - id }
    }

    /**
     * Refreshes the active tab's picture for the switcher.
     *
     * Called when a sheet opens, because that is the last moment the tab is
     * certainly on screen before the user may leave it: every tab switch goes
     * through one of the two sheets, and a hidden `WebContents` has nothing
     * to read back. A tab that is never on screen keeps no picture and the
     * switcher draws its placeholder instead.
     */
    fun captureThumbnail() {
        val tab = active
        if (tab.session.state.value.url == null || section.value != Section.Home) return
        scope.launch {
            val full = shell.capturePage(tab.session) ?: return@launch
            val small = withContext(Dispatchers.Default) { thumbnailOf(full) }
            full.recycle()
            thumbnails.update { it + (tab.id to small) }
        }
    }

    /**
     * The top of the page, small. A card in the switcher is about 170dp wide;
     * [THUMB_WIDTH] px covers that at this project's target density, and at
     * roughly 90 KB a tab thirty tabs cost less than one full-screen bitmap.
     */
    private fun thumbnailOf(full: Bitmap): Bitmap {
        val scale = THUMB_WIDTH.toFloat() / full.width
        val height = (full.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(full, THUMB_WIDTH, height, true)
        val cropped = Bitmap.createBitmap(
            scaled, 0, 0, THUMB_WIDTH, minOf(height, (THUMB_WIDTH * THUMB_ASPECT).toInt()),
        )
        if (cropped !== scaled) scaled.recycle()
        return cropped
    }

    private companion object {
        const val THUMB_WIDTH = 216
        const val THUMB_ASPECT = 1.25f
    }

    // --- Navigation ---------------------------------------------------------

    fun onAddressChanged(text: String) {
        val id = active.id
        edits.update { it + (id to text) }
    }

    /** Navigate the active tab to whatever is in its address field. */
    fun onGo() {
        edits.value[active.id]?.let(::navigateTo)
    }

    /** Navigate the active tab to a typed or intent-supplied address. */
    fun navigateTo(typed: String) {
        val id = active.id
        section.value = Section.Home
        val url = shell.normalize(typed)
        if (url == null) {
            invalid.update { it + (id to FetchError.InvalidUrl(typed.trim())) }
            return
        }
        edits.update { it - id }
        invalid.update { it - id }
        active.session.loadUrl(url)
    }

    fun onReload() = active.session.reload()
    fun onStop() = active.session.stop()
    fun onForward() { active.session.goForward() }

    /** @return true if Back was used up inside the browser. */
    fun onBack(): Boolean = when {
        section.value != Section.Home -> {
            section.value = Section.Home
            true
        }
        else -> active.session.goBack()
    }

    fun destroy() = tabs.destroy()
}

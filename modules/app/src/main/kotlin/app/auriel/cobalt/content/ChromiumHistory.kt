package app.auriel.cobalt.content

import app.auriel.cobalt.browser.engine.HistoryEntry
import app.auriel.cobalt.browser.engine.HistoryPage
import app.auriel.cobalt.browser.engine.HistorySource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.chromium.chrome.browser.browsing_data.BrowsingDataBridge
import org.chromium.chrome.browser.browsing_data.BrowsingDataType
import org.chromium.chrome.browser.browsing_data.TimePeriod
import org.chromium.chrome.browser.history.BrowsingHistoryBridge
import org.chromium.chrome.browser.history.HistoryItem
import org.chromium.chrome.browser.history.HistoryProvider
import org.chromium.chrome.browser.profiles.ProfileManager

/**
 * Chromium's history, as the shell's [HistorySource].
 *
 * Through `BrowsingHistoryBridge`, the provider behind Chrome's own History
 * page: paged queries over the HistoryService, newest first, one item per
 * address per day. The regular Profile only; incognito keeps no history.
 */
internal class ChromiumHistory : HistorySource {

    private val profile = ProfileManager.getLastUsedRegularProfile()
    private val bridge = BrowsingHistoryBridge(profile)
    private val byId = LinkedHashMap<String, HistoryItem>()

    private val _page = MutableStateFlow(HistoryPage())
    override val page: StateFlow<HistoryPage> = _page.asStateFlow()

    /** Whether the next result appends (a continuation) or replaces. */
    private var appending = false

    init {
        bridge.setObserver(object : HistoryProvider.BrowsingHistoryObserver {
            override fun onQueryHistoryComplete(items: List<HistoryItem>, hasMorePotentialMatches: Boolean) {
                if (!appending) byId.clear()
                for (item in items) byId[idOf(item)] = item
                // Loading everything: keep asking until there is no more, or
                // the cap is reached.
                if (fetchingAll && hasMorePotentialMatches && byId.size < ALL_CAP) {
                    appending = true
                    bridge.queryHistoryContinuation()
                    return
                }
                fetchingAll = false
                _page.value = _page.value.copy(
                    items = byId.values.map(::toEntry),
                    hasMore = hasMorePotentialMatches,
                    loading = false,
                )
            }

            // Something else deleted history (clearing browsing data); the
            // list on screen is stale.
            override fun onHistoryDeleted() = query(_page.value.query)

            override fun hasOtherFormsOfBrowsingData(hasOtherForms: Boolean) = Unit

            override fun onQueryAppsComplete(items: List<String>) = Unit
        })
    }

    fun destroy() = bridge.destroy()

    override fun query(text: String) {
        appending = false
        _page.value = _page.value.copy(query = text, loading = true)
        bridge.queryHistory(text, /* appId= */ null)
    }

    /** Set while [loadAll] is paging through; each result asks for the next. */
    private var fetchingAll = false

    override fun loadAll() {
        val current = _page.value
        if (fetchingAll || (current.query.isEmpty() && !current.hasMore && !current.loading)) return
        fetchingAll = true
        appending = false
        _page.value = current.copy(query = "", loading = true)
        bridge.queryHistory("", /* appId= */ null)
    }

    override fun loadMore() {
        val current = _page.value
        if (!current.hasMore || current.loading) return
        appending = true
        _page.value = current.copy(loading = true)
        bridge.queryHistoryContinuation()
    }

    override fun remove(id: String) {
        val item = byId.remove(id) ?: return
        _page.value = _page.value.copy(items = byId.values.map(::toEntry))
        bridge.markItemForRemoval(item)
        bridge.removeItems()
    }

    override fun clearAll(onDone: () -> Unit) {
        BrowsingDataBridge.getForProfile(profile).clearBrowsingData(
            {
                byId.clear()
                _page.value = HistoryPage(query = _page.value.query)
                onDone()
            },
            intArrayOf(BrowsingDataType.HISTORY),
            TimePeriod.ALL_TIME,
        )
    }

    private fun idOf(item: HistoryItem) = "${item.stableId}"

    private companion object {
        /**
         * Entries loaded for searching. Chrome's history keeps 90 days; this
         * covers heavy use of that and stays a few megabytes.
         */
        const val ALL_CAP = 10_000
    }

    private fun toEntry(item: HistoryItem) = HistoryEntry(
        id = idOf(item),
        url = item.url.spec,
        title = item.title.orEmpty(),
        visitedAtMs = item.timestamp,
    )
}

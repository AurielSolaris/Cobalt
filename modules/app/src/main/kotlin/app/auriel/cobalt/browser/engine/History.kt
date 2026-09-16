package app.auriel.cobalt.browser.engine

import kotlinx.coroutines.flow.StateFlow

/**
 * Browsing history, as the shell sees it.
 *
 * Engine-neutral like [DownloadsSource]. Chromium records history itself, for
 * every tab that is not incognito, and Cobalt only reads and deletes it; see
 * `content/ChromiumHistory.kt`. Nothing is loaded until [query] is called,
 * because the list can be long and is only needed while the page is open.
 */
interface HistorySource {
    val page: StateFlow<HistoryPage>

    /** Replaces the list with what matches [text]; empty means everything. Newest first. */
    fun query(text: String)

    /** Appends the next page of the current query, if [HistoryPage.hasMore]. */
    fun loadMore()

    /**
     * Loads every entry, up to a cap, for searching in the shell: the engine's
     * own search matches word prefixes only, and fuzzy and regular-expression
     * search need the whole list.
     */
    fun loadAll()

    /** Deletes every visit to this entry's address. */
    fun remove(id: String)

    /** Deletes all history. [onDone] runs once it is gone. */
    fun clearAll(onDone: () -> Unit)
}

data class HistoryPage(
    val query: String = "",
    val items: List<HistoryEntry> = emptyList(),
    val hasMore: Boolean = false,
    val loading: Boolean = false,
)

data class HistoryEntry(
    val id: String,
    val url: String,
    val title: String,
    /** The most recent visit, in milliseconds since the epoch. */
    val visitedAtMs: Long,
)

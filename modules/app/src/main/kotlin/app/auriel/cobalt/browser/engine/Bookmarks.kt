package app.auriel.cobalt.browser.engine

import kotlinx.coroutines.flow.StateFlow

/**
 * Bookmarks, as the shell sees them.
 *
 * Engine-neutral like [DownloadsSource]. Chromium's implementation keeps them
 * in Chromium's own bookmark store (the `Bookmarks` file in the profile, the
 * one Chrome uses), so they are written to disk by Chromium and survive
 * everything the profile does; see `content/ChromiumBookmarks.kt`.
 */
interface BookmarksSource {
    /** Newest first. Empty until the store has loaded. */
    val items: StateFlow<List<BookmarkEntry>>

    fun add(url: String, title: String)

    fun remove(id: String)
}

data class BookmarkEntry(
    val id: String,
    val url: String,
    val title: String,
)

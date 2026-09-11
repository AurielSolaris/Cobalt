package app.auriel.cobalt.content

import app.auriel.cobalt.browser.engine.BookmarkEntry
import app.auriel.cobalt.browser.engine.BookmarksSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.chromium.chrome.browser.bookmarks.BookmarkModel
import org.chromium.chrome.browser.bookmarks.BookmarkModelObserver
import org.chromium.chrome.browser.profiles.ProfileManager
import org.chromium.components.bookmarks.BookmarkId
import org.chromium.components.bookmarks.BookmarkItem
import org.chromium.url.GURL

/**
 * Chromium's bookmark store, as the shell's [BookmarksSource].
 *
 * The same `BookmarkModel` Chrome's bookmark manager drives. Chromium owns the
 * file and writes it; the shell only lists, adds and deletes.
 *
 * The list is flat on purpose: every bookmark in every folder, newest first.
 * New ones go in "Mobile bookmarks", where Chrome on Android puts them, so a
 * profile that later meets Chrome's folder UI finds them where it expects.
 */
internal class ChromiumBookmarks : BookmarksSource {

    private val model = run {
        // "Partner bookmarks" are ones a phone maker preloads through a
        // content provider. Chrome registers where to read them during its own
        // start-up, which Cobalt does not run, and the model will not finish
        // loading without it: `assert sPartnerBookmarkIteratorSupplier.hasValue()`
        // in BookmarkBridge, measured on device. Cobalt takes none, so the
        // provider hands over nothing; a null iterator is the documented "no
        // partner bookmarks" (PartnerBookmarksReader), and what Chromium's
        // default AppHooks returns too.
        BookmarkModel.setPartnerBookmarkIteratorProvider { callback -> callback.onResult(null) }
        BookmarkModel.getForProfile(ProfileManager.getLastUsedRegularProfile())
    }
    private val ids = HashMap<String, BookmarkId>()

    private val _items = MutableStateFlow<List<BookmarkEntry>>(emptyList())
    override val items: StateFlow<List<BookmarkEntry>> = _items.asStateFlow()

    private val observer = object : BookmarkModelObserver() {
        override fun bookmarkModelChanged() = publish()
    }

    init {
        model.addObserver(observer)
        // The store loads from disk asynchronously; this runs at once if it
        // already has.
        model.finishLoadingBookmarkModel(::publish)
    }

    fun destroy() = model.removeObserver(observer)

    private fun publish() {
        if (!model.isBookmarkModelLoaded) return
        val found = ArrayList<BookmarkItem>()
        listOfNotNull(model.mobileFolderId, model.otherFolderId, model.desktopFolderId)
            .forEach { collect(it, found) }
        ids.clear()
        _items.value = found
            .sortedByDescending { it.dateAdded }
            .map { item ->
                val key = item.id.toString()
                ids[key] = item.id
                BookmarkEntry(key, item.url.spec, item.title.orEmpty())
            }
    }

    private fun collect(folder: BookmarkId, into: MutableList<BookmarkItem>) {
        for (child in model.getChildIds(folder)) {
            val item = model.getBookmarkById(child) ?: continue
            if (item.isFolder) collect(child, into) else into.add(item)
        }
    }

    override fun add(url: String, title: String) {
        if (!model.isBookmarkModelLoaded) return
        val folder = model.mobileFolderId ?: return
        model.addBookmark(folder, 0, title.ifBlank { url }, GURL(url))
    }

    override fun remove(id: String) {
        ids[id]?.let(model::deleteBookmark)
    }
}

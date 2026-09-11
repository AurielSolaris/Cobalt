package app.auriel.cobalt.content

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import app.auriel.cobalt.browser.engine.DownloadEntry
import app.auriel.cobalt.browser.engine.DownloadState
import app.auriel.cobalt.browser.engine.DownloadsSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.chromium.chrome.browser.download.items.OfflineContentAggregatorFactory
import org.chromium.chrome.browser.preferences.Pref
import org.chromium.chrome.browser.profiles.ProfileManager
import org.chromium.components.offline_items_collection.ContentId
import org.chromium.components.offline_items_collection.LaunchLocation
import org.chromium.components.offline_items_collection.OfflineContentProvider
import org.chromium.components.offline_items_collection.OfflineItem
import org.chromium.components.offline_items_collection.OfflineItemState
import org.chromium.components.offline_items_collection.OpenParams
import org.chromium.components.offline_items_collection.UpdateDelta
import org.chromium.components.user_prefs.UserPrefs
import java.io.File

/**
 * Chromium's download system, as the shell's [DownloadsSource].
 *
 * Chromium does the downloading: cookies and logins, redirects, resumption,
 * Safe Browsing, writing into the phone's Downloads. The shell only lists what
 * it is doing, through [OfflineContentProvider], the interface Chrome's own
 * Downloads page reads.
 *
 * ## Why downloads silently vanished before this
 *
 * Chrome asks where to save the first download on Android
 * (`DownloadPromptStatus::SHOW_INITIAL`). That prompt is Chrome UI: a Java
 * dialog shown through a modal-dialog manager that only `ChromeActivity` has.
 * Cobalt's window has none, so the dialog never appeared, never answered, and
 * the download waited in target determination forever: no file, no error, no
 * log line. Measured on device with a plain file link.
 *
 * The fix is Chromium's own setting, not a patch: `DONT_SHOW`, the value Chrome
 * stores when you tick "don't ask again". Files go to the default Downloads
 * folder. A save-location choice can come back later as Cobalt UI.
 */
internal class ChromiumDownloads(private val context: Context) : DownloadsSource {

    private val provider: OfflineContentProvider = OfflineContentAggregatorFactory.get()
    private val known = LinkedHashMap<String, OfflineItem>()

    private val _items = MutableStateFlow<List<DownloadEntry>>(emptyList())
    override val items: StateFlow<List<DownloadEntry>> = _items.asStateFlow()

    private val observer = object : OfflineContentProvider.Observer {
        override fun onItemsAdded(items: List<OfflineItem>) {
            items.forEach(::track)
            publish()
        }

        override fun onItemRemoved(id: ContentId) {
            known.remove(key(id))
            publish()
        }

        override fun onItemUpdated(item: OfflineItem, updateDelta: UpdateDelta?) {
            track(item)
            publish()
        }
    }

    init {
        val profile = ProfileManager.getLastUsedRegularProfile()
        UserPrefs.get(profile).setInteger(Pref.PROMPT_FOR_DOWNLOAD_ANDROID, DONT_SHOW)
        provider.addObserver(observer)
        provider.getAllItems { all ->
            all.forEach(::track)
            publish()
        }
    }

    fun destroy() = provider.removeObserver(observer)

    /** Only real downloads: not offline pages, not incognito, not suggestions. */
    private fun track(item: OfflineItem) {
        if (item.isOffTheRecord || item.isTransient || item.isSuggested) return
        known[key(item.id)] = item
    }

    private fun publish() {
        _items.value = known.values
            .map(::toEntry)
            .sortedByDescending { it.createdAtMs }
    }

    private fun toEntry(item: OfflineItem): DownloadEntry {
        val progress = item.progress
        val percent = if (progress == null || progress.isIndeterminate) null else progress.percentage
        return DownloadEntry(
            id = key(item.id),
            fileName = item.title?.takeIf { it.isNotBlank() }
                ?: item.filePath?.substringAfterLast('/')
                ?: "download",
            sourceHost = (item.originalUrl ?: item.url)?.takeIf { !it.isEmpty }?.host,
            mimeType = item.mimeType,
            state = when (item.state) {
                OfflineItemState.IN_PROGRESS -> DownloadState.InProgress
                OfflineItemState.PENDING -> DownloadState.Pending
                OfflineItemState.PAUSED, OfflineItemState.INTERRUPTED ->
                    if (item.isResumable) DownloadState.Paused else DownloadState.Failed
                OfflineItemState.COMPLETE -> DownloadState.Complete
                OfflineItemState.CANCELLED -> DownloadState.Cancelled
                else -> DownloadState.Failed
            },
            receivedBytes = item.receivedBytes,
            totalBytes = if (item.totalSizeBytes > 0) item.totalSizeBytes else -1,
            percent = percent,
            timeRemainingMs = item.timeRemainingMs,
            createdAtMs = item.creationTimeMs,
            canResume = item.isResumable,
            isDangerous = item.isDangerous,
        )
    }

    private fun idOf(key: String): ContentId? = known[key]?.id

    override fun pause(id: String) { idOf(id)?.let(provider::pauseDownload) }
    override fun resume(id: String) { idOf(id)?.let(provider::resumeDownload) }
    override fun cancel(id: String) { idOf(id)?.let(provider::cancelDownload) }
    override fun remove(id: String) { idOf(id)?.let(provider::removeItem) }

    /**
     * Opens the file with whatever app handles its type.
     *
     * Through a content URI, because Cobalt has no FileProvider and Android
     * will not hand another app a raw path. Chromium stores public downloads
     * through MediaStore, so the file already has one: either the path is the
     * URI, or MediaStore finds it by path. Chromium's own openItem is the last
     * resort; it goes through Chrome's FileProvider, which Cobalt's manifest
     * does not declare.
     */
    override fun open(id: String): Boolean {
        val item = known[id] ?: return false
        val uri = contentUriFor(item.filePath) ?: run {
            provider.openItem(OpenParams(LaunchLocation.DOWNLOAD_HOME), item.id)
            return true
        }
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, item.mimeType?.takeIf { it.isNotBlank() } ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(Intent.createChooser(view, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    override fun contentUri(id: String): Uri? = contentUriFor(known[id]?.filePath)

    private fun contentUriFor(path: String?): Uri? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("content://")) return Uri.parse(path)
        val file = File(path)
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        @Suppress("DEPRECATION")
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DATA} = ?",
            arrayOf(file.absolutePath),
            null,
        )?.use { c -> if (c.moveToFirst()) return ContentUris.withAppendedId(collection, c.getLong(0)) }
        return null
    }

    private companion object {
        /** `DownloadPromptStatus::DONT_SHOW` (chrome/browser/download/download_prompt_status.h). */
        const val DONT_SHOW = 2

        fun key(id: ContentId) = "${id.namespace}:${id.id}"
    }
}

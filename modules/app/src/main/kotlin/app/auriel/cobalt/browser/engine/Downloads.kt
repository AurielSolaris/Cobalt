package app.auriel.cobalt.browser.engine

import kotlinx.coroutines.flow.StateFlow

/**
 * Downloads, as the shell sees them.
 *
 * Engine-neutral on purpose, like [SessionState]: the Downloads screen draws
 * these and never an engine's own item type. Chromium's implementation reads
 * its download system through the same interface Chrome's own Downloads page
 * uses; see `content/ChromiumDownloads.kt`.
 */
interface DownloadsSource {
    /** Newest first. */
    val items: StateFlow<List<DownloadEntry>>

    fun pause(id: String)
    fun resume(id: String)
    fun cancel(id: String)

    /**
     * **Deletes the file** and forgets the entry. Chromium's implementation
     * calls `DeleteFile` then `Remove`; the UI must confirm before calling it.
     */
    fun remove(id: String)

    /** Opens a finished download in whatever app handles it; false if nothing could. */
    fun open(id: String): Boolean

    /** A readable URI for a finished download, for opening it inside Cobalt. */
    fun contentUri(id: String): android.net.Uri?
}

enum class DownloadState { Pending, InProgress, Paused, Complete, Failed, Cancelled }

data class DownloadEntry(
    val id: String,
    val fileName: String,
    /** Where it came from, for the subtitle. */
    val sourceHost: String?,
    val mimeType: String?,
    val state: DownloadState,
    val receivedBytes: Long,
    /** -1 when the server did not say. */
    val totalBytes: Long,
    /** 0..100, or null when the size is unknown. */
    val percent: Int?,
    /** Milliseconds, or -1 when unknown. */
    val timeRemainingMs: Long,
    val createdAtMs: Long,
    val canResume: Boolean,
    /** Flagged by Safe Browsing or by file type; the UI says so before opening. */
    val isDangerous: Boolean,
) {
    val isActive: Boolean get() = state == DownloadState.InProgress || state == DownloadState.Pending
}

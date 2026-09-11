package app.auriel.cobalt.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.auriel.cobalt.browser.engine.DownloadEntry
import app.auriel.cobalt.browser.engine.DownloadState
import kotlin.math.ln
import kotlin.math.pow

/** Everything the Downloads screen can ask for. */
class DownloadActions(
    val onPause: (String) -> Unit,
    val onResume: (String) -> Unit,
    val onCancel: (String) -> Unit,
    val onRemove: (String) -> Unit,
    val onOpen: (String) -> Unit,
)

/**
 * The Downloads screen.
 *
 * What is happening now at the top (newest first overall), each row saying in
 * words what state it is in rather than relying on an icon, and the one action
 * that matters for that state within reach.
 *
 * Deleting is destructive, and says so: a red button, and a confirmation that
 * names the file and says it cannot be undone. It really deletes -- Chromium's
 * RemoveItem calls DeleteFile, then forgets the entry -- so the screen must
 * never suggest the file survives.
 */
@Composable
fun DownloadsScreen(downloads: List<DownloadEntry>?, actions: DownloadActions) {
    val colors = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Downloads", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            Spacer(Modifier.width(10.dp))
            if (downloads != null) {
                Text(downloads.size.toString(), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }

        when {
            downloads == null -> Empty(
                "Downloads need the Chromium engine",
                "This build runs on the 0.1.0 document engine, which only loads pages.",
            )
            downloads.isEmpty() -> Empty(
                "Nothing downloaded yet",
                "Files you download are saved to your phone's Downloads folder and listed here.",
            )
            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                items(downloads, key = { it.id }) { entry ->
                    DownloadRow(entry, actions)
                    HorizontalDivider(color = colors.outlineVariant, modifier = Modifier.padding(start = 64.dp))
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(entry: DownloadEntry, actions: DownloadActions) {
    val colors = MaterialTheme.colorScheme
    val openable = entry.state == DownloadState.Complete
    var confirming by remember { mutableStateOf(false) }

    if (confirming) {
        ConfirmDelete(entry, onConfirm = {
            confirming = false
            actions.onRemove(entry.id)
        }, onDismiss = { confirming = false })
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (openable) Modifier.clickable { actions.onOpen(entry.id) } else Modifier)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).background(colors.surfaceVariant, MaterialTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (entry.isDangerous) Icons.Outlined.Warning else iconFor(entry.mimeType, entry.fileName),
                contentDescription = null,
                tint = if (entry.isDangerous) colors.error else colors.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.fileName,
                style = MaterialTheme.typography.bodyLarge,
                color = if (entry.state == DownloadState.Cancelled) colors.onSurfaceVariant else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                statusLine(entry),
                style = MaterialTheme.typography.bodySmall,
                color = when (entry.state) {
                    DownloadState.Failed -> colors.error
                    else -> colors.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.isActive || entry.state == DownloadState.Paused) {
                Spacer(Modifier.height(6.dp))
                val percent = entry.percent
                if (percent == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp), color = colors.primary, trackColor = colors.surfaceVariant)
                } else {
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = if (entry.state == DownloadState.Paused) colors.onSurfaceVariant else colors.primary,
                        trackColor = colors.surfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.width(4.dp))

        when (entry.state) {
            DownloadState.InProgress, DownloadState.Pending -> {
                Action(Icons.Filled.Pause, "Pause") { actions.onPause(entry.id) }
                Action(Icons.Filled.Close, "Cancel") { actions.onCancel(entry.id) }
            }
            DownloadState.Paused -> {
                Action(Icons.Filled.PlayArrow, "Resume") { actions.onResume(entry.id) }
                Action(Icons.Filled.Close, "Cancel") { actions.onCancel(entry.id) }
            }
            DownloadState.Failed -> {
                if (entry.canResume) Action(Icons.Filled.PlayArrow, "Retry") { actions.onResume(entry.id) }
                DeleteAction("Remove") { confirming = true }
            }
            DownloadState.Complete -> DeleteAction("Delete") { confirming = true }
            DownloadState.Cancelled -> DeleteAction("Remove") { confirming = true }
        }
    }
}

/** Red, because it deletes; see [ConfirmDelete]. */
@Composable
private fun DeleteAction(label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Outlined.Delete, contentDescription = label, tint = MaterialTheme.colorScheme.error)
    }
}

/**
 * The confirmation before anything is deleted. A finished download is a file
 * on the phone, and deleting it cannot be undone; an unfinished one only has a
 * partial file, so it is "removed", but still asked.
 */
@Composable
private fun ConfirmDelete(entry: DownloadEntry, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val finished = entry.state == DownloadState.Complete
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = colors.error) },
        title = { Text(if (finished) "Delete this file?" else "Remove this download?") },
        text = {
            Text(
                if (finished) {
                    "${entry.fileName} will be deleted from your phone's Downloads folder. This cannot be undone."
                } else {
                    "${entry.fileName} will be removed from the list, along with anything it had downloaded so far."
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(if (finished) "Delete" else "Remove", color = colors.error, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Action(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Empty(headline: String, detail: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.Download, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(12.dp))
        Text(headline, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** The status in words: where from, how much, and what is happening. */
internal fun statusLine(e: DownloadEntry): String {
    val from = e.sourceHost?.let { "$it · " } ?: ""
    val size = if (e.totalBytes > 0) formatBytes(e.totalBytes) else null
    return when (e.state) {
        DownloadState.Pending -> "${from}Waiting…"
        DownloadState.InProgress -> buildString {
            append(from)
            append(formatBytes(e.receivedBytes))
            if (size != null) append(" of $size")
            if (e.timeRemainingMs > 0) append(" · ${formatDuration(e.timeRemainingMs)} left")
        }
        DownloadState.Paused -> "${from}Paused · ${formatBytes(e.receivedBytes)}${size?.let { " of $it" } ?: ""}"
        DownloadState.Complete -> "$from${size ?: formatBytes(e.receivedBytes)}" + if (e.isDangerous) " · may be harmful" else ""
        DownloadState.Failed -> "${from}Failed" + if (e.canResume) " · can resume" else ""
        DownloadState.Cancelled -> "${from}Cancelled"
    }
}

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(1, units.size)
    val value = bytes / 1024.0.pow(exp)
    return if (value >= 100) "%.0f %s".format(value, units[exp - 1]) else "%.1f %s".format(value, units[exp - 1])
}

internal fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60} min"
        else -> "${s / 3600} h ${(s % 3600) / 60} min"
    }
}

private fun iconFor(mime: String?, name: String): ImageVector {
    val m = mime.orEmpty()
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        m.startsWith("image/") -> Icons.Outlined.Image
        m.startsWith("video/") -> Icons.Outlined.Movie
        m.startsWith("audio/") -> Icons.Outlined.Audiotrack
        m == "application/pdf" || ext == "pdf" -> Icons.Outlined.PictureAsPdf
        m == "application/vnd.android.package-archive" || ext == "apk" -> Icons.Outlined.Android
        ext in setOf("zip", "7z", "rar", "tar", "gz", "xz", "bz2") -> Icons.Outlined.FolderZip
        m.startsWith("text/") || ext in setOf("doc", "docx", "odt", "txt", "md") -> Icons.Outlined.Description
        else -> Icons.Outlined.InsertDriveFile
    }
}

/**
 * The notice above the toolbar: a download just started, or just finished.
 * One line, one action, gone after a few seconds; it never covers the page.
 */
@Composable
fun DownloadNotice(entry: DownloadEntry, onView: () -> Unit, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val done = entry.state == DownloadState.Complete
    Column(Modifier.fillMaxWidth().background(colors.surfaceContainerHigh)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Download, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                (if (done) "Downloaded " else "Downloading ") + entry.fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = if (done) onOpen else onView) { Text(if (done) "Open" else "View") }
            IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
        }
        val percent = entry.percent
        if (!done && percent != null) {
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = colors.primary,
                trackColor = colors.surfaceContainerHigh,
            )
        }
    }
}

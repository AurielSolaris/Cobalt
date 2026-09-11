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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.auriel.cobalt.browser.engine.BookmarkEntry

/** Everything the Bookmarks screen can ask for. */
class BookmarkActions(
    val onOpen: (url: String) -> Unit,
    val onRemove: (id: String) -> Unit,
)

/**
 * The Bookmarks screen: every bookmark, newest first, one tap to open it in
 * the tab on screen. Adding is the Bookmark tile in the ⋮ menu, on the page
 * itself; this screen is for finding and removing.
 */
@Composable
fun BookmarksScreen(bookmarks: List<BookmarkEntry>?, actions: BookmarkActions) {
    val colors = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Bookmarks", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            Spacer(Modifier.width(10.dp))
            if (bookmarks != null) {
                Text(bookmarks.size.toString(), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }

        when {
            bookmarks == null -> Empty(
                "Bookmarks need the Chromium engine",
                "This build runs on the 0.1.0 document engine, which keeps no bookmarks.",
            )
            bookmarks.isEmpty() -> Empty(
                "No bookmarks yet",
                "On any page, open ⋮ and tap Bookmark. It is listed here and kept on this phone.",
            )
            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                items(bookmarks, key = { it.id }) { entry ->
                    BookmarkRow(entry, actions)
                    HorizontalDivider(color = colors.outlineVariant, modifier = Modifier.padding(start = 64.dp))
                }
            }
        }
    }
}

@Composable
private fun BookmarkRow(entry: BookmarkEntry, actions: BookmarkActions) {
    val colors = MaterialTheme.colorScheme
    val host = entry.url.substringAfter("://").substringBefore('/').removePrefix("www.")
    var confirming by remember { mutableStateOf(false) }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = colors.error) },
            title = { Text("Remove this bookmark?") },
            text = { Text("${entry.title.ifBlank { host }} will be removed from your bookmarks.") },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    actions.onRemove(entry.id)
                }) { Text("Remove", color = colors.error, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { actions.onOpen(entry.url) }
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The site's initial: no favicons yet, and a letter tells sites apart
        // better than thirty identical stars.
        Box(
            Modifier.size(36.dp).background(colors.surfaceVariant, MaterialTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                host.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = colors.primary,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.title.ifBlank { host },
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                host,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { confirming = true }) {
            Icon(Icons.Outlined.Delete, contentDescription = "Remove", tint = colors.error)
        }
    }
}

@Composable
private fun Empty(headline: String, detail: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.StarBorder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(12.dp))
        Text(headline, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

package app.auriel.cobalt.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.auriel.cobalt.browser.engine.HistoryEntry
import app.auriel.cobalt.browser.engine.HistoryPage
import app.auriel.cobalt.browser.search.FuzzyMatch
import app.auriel.cobalt.ui.theme.JetBrainsMono
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** Everything the History screen can ask for. */
class HistoryActions(
    val onOpen: (url: String) -> Unit,
    val onSearch: (text: String) -> Unit,
    val onLoadMore: () -> Unit,
    val onRemove: (id: String) -> Unit,
    val onClearAll: (onDone: () -> Unit) -> Unit,
)

/**
 * The History screen: every page visited outside incognito, newest first,
 * under a heading per day, with a search box at the top. Search is fuzzy,
 * or a regular expression with the .* toggle, over the whole history
 * ([FuzzyMatch]); results are ranked, best first.
 *
 * Removing one entry is a plain ✕, as in every browser: it forgets that you
 * went there, and nothing else. Clearing all of it is red and asks first.
 */
@Composable
fun HistoryScreen(history: HistoryPage?, actions: HistoryActions, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var confirmClear by remember { mutableStateOf(false) }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = colors.error) },
            title = { Text("Clear all history?") },
            text = { Text("Every page you have visited will be removed from this list. Bookmarks, downloads and sign-ins are not affected. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    actions.onClearAll {}
                }) { Text("Clear", color = colors.error, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The browser's toolbar is not drawn over a section, so the way
            // back is here, where Settings keeps it too.
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.onSurfaceVariant)
            }
            Text("History", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            Spacer(Modifier.weight(1f))
            if (!history?.items.isNullOrEmpty()) {
                TextButton(onClick = { confirmClear = true }) {
                    Text("Clear history", color = colors.error)
                }
            }
        }

        if (history == null) {
            Empty("History needs the Chromium engine", "This build runs on the 0.1.0 document engine, which keeps no history.")
            return
        }

        var text by remember { mutableStateOf("") }
        var regex by remember { mutableStateOf(false) }
        SearchField(text, { text = it }, regex, { regex = !regex })
        // Searching needs the whole list; asked for once, when a search starts.
        LaunchedEffect(text.isNotEmpty()) { if (text.isNotEmpty()) actions.onSearch(text) }

        val query = remember(text, regex) { if (text.isBlank()) null else FuzzyMatch.compile(text, regex) }
        val results = remember(history.items, query) {
            query?.let { q ->
                history.items
                    .mapNotNull { e -> q.score(e.title + " " + e.url)?.let { e to it } }
                    .sortedWith(compareByDescending<Pair<HistoryEntry, Int>> { it.second }.thenByDescending { it.first.visitedAtMs })
                    .map { it.first }
            }
        }

        when {
            history.items.isEmpty() && history.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.primary)
            }
            query is FuzzyMatch.Query.Invalid -> Empty("Not a valid pattern", query.reason)
            results != null && results.isEmpty() && !history.loading -> Empty(
                "Nothing matches “$text”",
                if (regex) "Matched as a regular expression against titles and addresses, ignoring case."
                else "Every word must appear in order in the title or address, letters may be spread out. " +
                    "Tap .* to search with a regular expression.",
            )
            results != null -> SearchResults(results, history.loading, actions)
            history.items.isEmpty() -> Empty(
                "No history yet",
                "Pages you visit are listed here. Incognito tabs are never recorded.",
            )
            else -> HistoryList(history, actions)
        }
    }
}

@Composable
private fun HistoryList(history: HistoryPage, actions: HistoryActions) {
    val colors = MaterialTheme.colorScheme
    val listState = rememberLazyListState()

    // The next page, when the end of this one comes into view.
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 5
        }
    }
    LaunchedEffect(nearEnd, history.hasMore, history.items.size) {
        if (nearEnd && history.hasMore && !history.loading) actions.onLoadMore()
    }

    val days = remember(history.items) { history.items.groupBy { dayLabel(it.visitedAtMs) }.toList() }
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }

    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 12.dp)) {
        for ((day, entries) in days) {
            item(key = "day-$day") {
                Text(
                    day,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
                )
            }
            items(entries, key = { it.id }) { entry ->
                HistoryRow(entry, time.format(Date(entry.visitedAtMs)), actions)
                HorizontalDivider(color = colors.outlineVariant, modifier = Modifier.padding(start = 64.dp))
            }
        }
        if (history.loading) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = colors.primary, strokeWidth = 2.dp)
                }
            }
        }
    }
}

/** Search results: best match first, so no day headings; each row carries its date. */
@Composable
private fun SearchResults(results: List<HistoryEntry>, loading: Boolean, actions: HistoryActions) {
    val colors = MaterialTheme.colorScheme
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    LazyColumn(contentPadding = PaddingValues(bottom = 12.dp)) {
        item(key = "count") {
            Text(
                "${results.size} found" + if (loading) " so far…" else "",
                style = MaterialTheme.typography.labelLarge,
                color = colors.primary,
                modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp),
            )
        }
        items(results, key = { it.id }) { entry ->
            HistoryRow(entry, format.format(Date(entry.visitedAtMs)), actions)
            HorizontalDivider(color = colors.outlineVariant, modifier = Modifier.padding(start = 64.dp))
        }
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, time: String, actions: HistoryActions) {
    val colors = MaterialTheme.colorScheme
    val host = entry.url.substringAfter("://").substringBefore('/').removePrefix("www.")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { actions.onOpen(entry.url) }
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).background(colors.surfaceVariant, MaterialTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            Text(host.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.titleMedium, color = colors.primary)
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
                "$host · $time",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { actions.onRemove(entry.id) }) {
            Icon(Icons.Filled.Close, contentDescription = "Remove from history", tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SearchField(text: String, onText: (String) -> Unit, regex: Boolean, onToggleRegex: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .height(42.dp)
            .background(colors.surfaceVariant, MaterialTheme.shapes.medium)
            .border(1.dp, colors.outlineVariant, MaterialTheme.shapes.medium)
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) {
                Text(
                    if (regex) "Regular expression" else "Search history",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            BasicTextField(
                value = text,
                onValueChange = onText,
                singleLine = true,
                textStyle = TextStyle(
                    color = colors.onSurface,
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                    fontFamily = if (regex) JetBrainsMono else null,
                ),
                cursorBrush = SolidColor(colors.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (text.isNotEmpty()) {
            IconButton(onClick = { onText("") }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
        }
        // Regular expression on or off. Says so in its label and its colour,
        // never colour alone (0007).
        Box(
            Modifier
                .padding(start = 2.dp)
                .background(if (regex) colors.primary else colors.surfaceVariant, MaterialTheme.shapes.small)
                .border(1.dp, if (regex) colors.primary else colors.outlineVariant, MaterialTheme.shapes.small)
                .clickable(onClickLabel = if (regex) "Search fuzzily" else "Search with a regular expression", onClick = onToggleRegex)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                ".*",
                style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMono),
                color = if (regex) colors.onPrimary else colors.onSurfaceVariant,
            )
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
        Icon(Icons.Outlined.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(12.dp))
        Text(headline, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** "Today", "Yesterday", or the date. */
private fun dayLabel(ms: Long): String {
    val day = Calendar.getInstance().apply { timeInMillis = ms }
    val today = Calendar.getInstance()
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (same(day, today)) return "Today"
    if (same(day, today.apply { add(Calendar.DAY_OF_YEAR, -1) })) return "Yesterday"
    return DateFormat.getDateInstance(DateFormat.FULL).format(Date(ms))
}

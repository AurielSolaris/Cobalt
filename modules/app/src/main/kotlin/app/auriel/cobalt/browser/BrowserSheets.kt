package app.auriel.cobalt.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** Everything the options sheet can do. */
class MenuActions(
    val canGoForward: Boolean,
    val hasPage: Boolean,
    /** Null when the engine keeps no bookmarks: the tile is not shown. */
    val bookmarked: Boolean?,
    val onToggleBookmark: () -> Unit,
    val onNewTab: () -> Unit,
    val onForward: () -> Unit,
    val onReload: () -> Unit,
    val onScreenshot: () -> Unit,
    val onShare: () -> Unit,
    val onHome: () -> Unit,
    val onExtensions: () -> Unit,
    val onDownloads: () -> Unit,
    val onBookmarks: () -> Unit,
    val onSettings: () -> Unit,
)

/**
 * The sheets rise from the toolbar they were opened from, so the hand that
 * tapped ⋮ or the tab count is already where the sheet's controls are. Boxy
 * top corners, as everywhere in the shell (decision 0002).
 */
private val SheetShape = RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)

/**
 * The ⋮ sheet.
 *
 * Page actions as a row of tiles at the top, the part nearest the thumb when
 * the sheet opens; places as a list beneath. Each entry closes the sheet
 * before it acts, so a screenshot never contains the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuSheet(actions: MenuActions, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun run(action: () -> Unit): () -> Unit = {
        onDismiss()
        action()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = SheetShape,
        containerColor = colors.surfaceContainerLow,
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            // Two rows of three: six tiles in one row cut their labels short
            // on a phone-width screen.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Tile("New tab", Icons.Filled.Add, Modifier.weight(1f), onClick = run(actions.onNewTab))
                Tile(
                    "Forward", Icons.AutoMirrored.Filled.ArrowForward, Modifier.weight(1f),
                    enabled = actions.canGoForward, onClick = run(actions.onForward),
                )
                Tile(
                    "Reload", Icons.Filled.Refresh, Modifier.weight(1f),
                    enabled = actions.hasPage, onClick = run(actions.onReload),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                actions.bookmarked?.let { bookmarked ->
                    // Filled and in the accent when the page is bookmarked, and
                    // the label says so too: meaning is never colour alone (0007).
                    Tile(
                        if (bookmarked) "Saved" else "Bookmark",
                        if (bookmarked) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        Modifier.weight(1f),
                        enabled = actions.hasPage,
                        tint = if (bookmarked) colors.primary else colors.onSurface,
                        onClick = run(actions.onToggleBookmark),
                    )
                }
                Tile(
                    "Screenshot", Icons.Outlined.PhotoCamera, Modifier.weight(1f),
                    enabled = actions.hasPage, onClick = run(actions.onScreenshot),
                )
                Tile(
                    "Share", Icons.Outlined.Share, Modifier.weight(1f),
                    enabled = actions.hasPage, onClick = run(actions.onShare),
                )
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = colors.outlineVariant)
            Spacer(Modifier.height(4.dp))

            Entry("Home", Icons.Outlined.Home, run(actions.onHome))
            Entry("Extensions", Icons.Outlined.Extension, run(actions.onExtensions))
            Entry("Downloads", Icons.Outlined.Download, run(actions.onDownloads))
            Entry("Bookmarks", Icons.Outlined.BookmarkBorder, run(actions.onBookmarks))
            Entry("Settings", Icons.Outlined.Settings, run(actions.onSettings))
        }
    }
}

/**
 * The tab switcher, as a sheet over the page rather than a screen instead of
 * it: the page you came from stays visible above, which is the answer to
 * "where was I" that a full-screen switcher throws away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabsSheet(
    state: BrowserState,
    onDismiss: () -> Unit,
    onTabSelected: (Long) -> Unit,
    onCloseTab: (Long) -> Unit,
    onNewTab: (Boolean) -> Unit,
    onCloseAll: (Boolean) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = SheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        TabsScreen(
            tabs = state.tabs,
            activeTabId = state.activeTabId,
            onTabSelected = {
                onTabSelected(it)
                onDismiss()
            },
            onCloseTab = onCloseTab,
            onNewTab = {
                onNewTab(it)
                onDismiss()
            },
            onCloseAll = onCloseAll,
            incognitoAvailable = state.incognitoAvailable,
            modifier = Modifier.fillMaxHeight(0.75f).navigationBarsPadding(),
        )
    }
}

@Composable
private fun Tile(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .height(72.dp)
            .background(colors.surfaceVariant, MaterialTheme.shapes.medium)
            .border(1.dp, colors.outlineVariant, MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun Entry(label: String, icon: ImageVector, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
    }
}

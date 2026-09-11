package app.auriel.cobalt.browser

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The tab switcher: two columns of cards, each with a picture of its page.
 *
 * 0.1.0 used a plain list on the argument that titles are what identify a tab
 * and do not survive cropping. That held while there was nothing to show; with
 * a real renderer, the page's own look is how people actually find a tab, and
 * the title is kept on each card for when it is not. Pictures come from
 * [BrowserController.captureThumbnail]; a tab with none yet shows its icon.
 */
@Composable
fun TabsScreen(
    tabs: List<Tab>,
    activeTabId: Long,
    onTabSelected: (Long) -> Unit,
    onCloseTab: (Long) -> Unit,
    onNewTab: (Boolean) -> Unit,
    onCloseAll: (Boolean) -> Unit,
    incognitoAvailable: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val incognitoCount = tabs.count { it.incognito }

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Tabs",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = tabs.size.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (tabs.size > 1) {
                TextButton(onClick = { onCloseAll(false) }) {
                    Text("Close all", color = colors.onSurfaceVariant)
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(tabs, key = { it.id }) { tab ->
                TabCard(
                    tab = tab,
                    isActive = tab.id == activeTabId,
                    onSelect = { onTabSelected(tab.id) },
                    onClose = { onCloseTab(tab.id) },
                )
            }

            if (incognitoCount > 0) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    TextButton(
                        onClick = { onCloseAll(true) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Close $incognitoCount incognito tab${if (incognitoCount == 1) "" else "s"}")
                    }
                }
            }
        }

        NewTabActions(onNewTab = onNewTab, incognitoAvailable = incognitoAvailable)
    }
}

@Composable
private fun TabCard(
    tab: Tab,
    isActive: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceVariant)
            .border(if (isActive) 2.dp else 1.dp, if (isActive) colors.primary else colors.outlineVariant, shape)
            .clickable(onClick = onSelect),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (tab.incognito) Icons.Outlined.VisibilityOff else Icons.Outlined.Language,
                contentDescription = null,
                tint = if (tab.incognito) colors.primary else colors.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = tab.displayTitle,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close ${tab.displayTitle}",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.8f)
                .background(colors.surfaceContainerLowest),
            contentAlignment = Alignment.Center,
        ) {
            val thumbnail = tab.thumbnail
            if (thumbnail != null) {
                val image = remember(thumbnail) { thumbnail.asImageBitmap() }
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // No picture yet: a tab opened in the background, or one that
                // has never been navigated. Its address stands in.
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (tab.incognito) Icons.Outlined.VisibilityOff else Icons.Outlined.Language,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = tab.displaySubtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * New tab, and new incognito tab, as two equally reachable buttons.
 *
 * Incognito is normally buried in an overflow menu, which quietly makes the
 * private option the inconvenient one. Putting both on the same row costs a
 * button and removes that nudge.
 *
 * When the engine cannot do incognito yet, the button stays, dimmed, rather
 * than vanishing: its place in the layout is a promise, and the label says
 * when it is kept.
 */
@Composable
private fun NewTabActions(onNewTab: (Boolean) -> Unit, incognitoAvailable: Boolean) {
    val colors = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ActionButton(
            text = "New tab",
            icon = Icons.Filled.Add,
            containerColor = colors.primary,
            contentColor = colors.onPrimary,
            modifier = Modifier.weight(1f),
            onClick = { onNewTab(false) },
        )
        ActionButton(
            text = if (incognitoAvailable) "Incognito" else "Incognito — soon",
            icon = Icons.Outlined.VisibilityOff,
            containerColor = colors.surfaceVariant,
            contentColor = colors.onSurfaceVariant,
            modifier = Modifier
                .weight(1f)
                .alpha(if (incognitoAvailable) 1f else 0.45f),
            enabled = incognitoAvailable,
            onClick = { onNewTab(true) },
        )
    }
}

@Composable
private fun ActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .height(46.dp)
            .background(containerColor, MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
        )
    }
}

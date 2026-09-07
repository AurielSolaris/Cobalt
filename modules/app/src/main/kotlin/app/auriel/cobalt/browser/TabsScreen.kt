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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The tab switcher.
 *
 * A list rather than a grid: on a phone the useful thing about a tab is its
 * title, and titles do not survive being cropped into a thumbnail. Grid previews
 * also mean rendering every tab off-screen, which is a cost Stage 7 can decide
 * to pay once there is a real renderer behind it.
 */
@Composable
fun TabsScreen(
    tabs: List<Tab>,
    activeTabId: Long,
    onTabSelected: (Long) -> Unit,
    onCloseTab: (Long) -> Unit,
    onNewTab: (Boolean) -> Unit,
    onCloseAll: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val incognitoCount = tabs.count { it.incognito }

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 10.dp),
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

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(tabs, key = { it.id }) { tab ->
                TabRow(
                    tab = tab,
                    isActive = tab.id == activeTabId,
                    onSelect = { onTabSelected(tab.id) },
                    onClose = { onCloseTab(tab.id) },
                )
            }

            if (incognitoCount > 0) {
                item {
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = { onCloseAll(true) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Close $incognitoCount incognito tab${if (incognitoCount == 1) "" else "s"}")
                    }
                }
            }
        }

        NewTabActions(onNewTab = onNewTab)
    }
}

@Composable
private fun TabRow(
    tab: Tab,
    isActive: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val borderColor = if (isActive) colors.primary else colors.outlineVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceVariant, MaterialTheme.shapes.medium)
            .border(if (isActive) 1.5.dp else 1.dp, borderColor, MaterialTheme.shapes.medium)
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(colors.surfaceContainerLowest, MaterialTheme.shapes.small),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (tab.incognito) Icons.Outlined.VisibilityOff else Icons.Outlined.Language,
                contentDescription = null,
                tint = if (tab.incognito) colors.primary else colors.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = tab.displayTitle,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = tab.displaySubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close ${tab.displayTitle}",
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * New tab, and new incognito tab, as two equally reachable buttons.
 *
 * Incognito is normally buried in an overflow menu, which quietly makes the
 * private option the inconvenient one. Putting both on the same row costs a
 * button and removes that nudge.
 */
@Composable
private fun NewTabActions(onNewTab: (Boolean) -> Unit) {
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
            text = "Incognito",
            icon = Icons.Outlined.VisibilityOff,
            containerColor = colors.surfaceVariant,
            contentColor = colors.onSurfaceVariant,
            modifier = Modifier.weight(1f),
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
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .height(46.dp)
            .background(containerColor, MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
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

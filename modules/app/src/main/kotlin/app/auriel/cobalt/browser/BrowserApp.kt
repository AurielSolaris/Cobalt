package app.auriel.cobalt.browser

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.auriel.cobalt.ui.theme.CobaltTheme

/**
 * The browser shell.
 *
 * Four sections on a bottom bar, the page above them. The bar stays put across
 * sections so the reach distance to a new tab never changes — on a phone the
 * bottom of the screen is the only part that is always within a thumb's reach.
 *
 * Unlike the renderer, this layer is not scheduled for deletion. The shell is
 * the part of Cobalt we own outright; Stage 6 replaces what draws inside the
 * content area, not the chrome around it.
 */
@Composable
fun BrowserApp(
    state: BrowserState,
    onAddressChanged: (String) -> Unit,
    onGo: () -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    onLinkClicked: (String) -> Unit,
    onSectionSelected: (Section) -> Unit,
    onNewTab: (Boolean) -> Unit,
    onTabSelected: (Long) -> Unit,
    onCloseTab: (Long) -> Unit,
    onCloseAllTabs: (Boolean) -> Unit,
) {
    val tab = state.activeTab

    // Incognito recolours the whole shell, not just a badge on it: being wrong
    // about which mode you are in is an asymmetric mistake.
    CobaltTheme(incognito = tab.incognito && state.section == Section.Home) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.systemBarsPadding()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when (state.section) {
                        Section.Home -> PageSection(
                            tab = tab,
                            onAddressChanged = onAddressChanged,
                            onGo = onGo,
                            onReload = onReload,
                            onStop = onStop,
                            onLinkClicked = onLinkClicked,
                            onSectionSelected = onSectionSelected,
                        )

                        Section.Tabs -> TabsScreen(
                            tabs = state.tabs,
                            activeTabId = state.activeTabId,
                            onTabSelected = onTabSelected,
                            onCloseTab = onCloseTab,
                            onNewTab = onNewTab,
                            onCloseAll = onCloseAllTabs,
                        )

                        Section.Extensions -> ComingLater(
                            title = "Extensions",
                            detail = "Extensions are the reason Cobalt exists, and they are the " +
                                "one thing it cannot fake. They arrive in Stage 8, running on " +
                                "Chromium's own extension system.",
                        )

                        Section.Bookmarks -> ComingLater(
                            title = "Bookmarks",
                            detail = "Bookmarks arrive in Stage 7, on top of Chromium's own store. " +
                                "Cobalt would rather show nothing than a list that forgets itself.",
                        )

                        Section.Downloads -> ComingLater(
                            title = "Downloads",
                            detail = "Downloads arrive in Stage 7. This build fetches pages only.",
                        )

                        Section.Settings -> ComingLater(
                            title = "Settings",
                            detail = "There is nothing configurable yet that would not be a " +
                                "setting for code due to be deleted in Stage 6.",
                        )
                    }
                }

                BottomBar(
                    section = state.section,
                    tabCount = state.tabs.size,
                    onSectionSelected = onSectionSelected,
                )
            }
        }
    }
}

/** The page: address bar, progress, content. */
@Composable
private fun PageSection(
    tab: Tab,
    onAddressChanged: (String) -> Unit,
    onGo: () -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    onLinkClicked: (String) -> Unit,
    onSectionSelected: (Section) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        AddressBar(
            text = tab.addressText,
            isLoading = tab.isLoading,
            incognito = tab.incognito,
            onTextChanged = onAddressChanged,
            onGo = onGo,
            onReload = onReload,
            onStop = onStop,
            onOpenBookmarks = { onSectionSelected(Section.Bookmarks) },
            onOpenSettings = { onSectionSelected(Section.Settings) },
        )

        if (tab.isLoading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        } else {
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }

        PageContent(tab = tab, onLinkClicked = onLinkClicked)
    }
}

@Composable
private fun BottomBar(
    section: Section,
    tabCount: Int,
    onSectionSelected: (Section) -> Unit,
) {
    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        modifier = Modifier.height(64.dp),
    ) {
        BarItem(
            target = Section.Home,
            current = section,
            selectedIcon = Icons.Filled.Home,
            unselectedIcon = Icons.Outlined.Home,
            label = "Home",
            onSelected = onSectionSelected,
        )
        BarItem(
            target = Section.Extensions,
            current = section,
            selectedIcon = Icons.Filled.Extension,
            unselectedIcon = Icons.Outlined.Extension,
            label = "Extensions",
            onSelected = onSectionSelected,
        )
        NavigationBarItem(
            selected = section == Section.Tabs,
            onClick = { onSectionSelected(Section.Tabs) },
            icon = { TabCountIcon(count = tabCount, selected = section == Section.Tabs) },
            label = { Text("Tabs", style = MaterialTheme.typography.labelSmall) },
            colors = barItemColors(),
        )
        BarItem(
            target = Section.Downloads,
            current = section,
            selectedIcon = Icons.Outlined.Download,
            unselectedIcon = Icons.Outlined.Download,
            label = "Downloads",
            onSelected = onSectionSelected,
        )
    }
}

@Composable
private fun RowScope.BarItem(
    target: Section,
    current: Section,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    label: String,
    onSelected: (Section) -> Unit,
) {
    val selected = target == current

    NavigationBarItem(
        selected = selected,
        onClick = { onSelected(target) },
        icon = {
            Icon(
                imageVector = if (selected) selectedIcon else unselectedIcon,
                contentDescription = label,
            )
        },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = barItemColors(),
    )
}

@Composable
private fun barItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.primary,
    selectedTextColor = MaterialTheme.colorScheme.primary,
    // A pill behind the selected icon is exactly the bubbly shape this interface
    // avoids; the accent colour carries the selection instead.
    indicatorColor = Color.Transparent,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

/** The tab-count square, which is the switcher's icon in every browser. */
@Composable
private fun TabCountIcon(count: Int, selected: Boolean) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

    Box(modifier = Modifier.height(22.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 1.5.dp.toPx()
            val side = size.height
            val left = (size.width - side) / 2f
            drawRect(
                color = color,
                topLeft = Offset(left + stroke / 2, stroke / 2),
                size = Size(side - stroke, side - stroke),
                style = Stroke(width = stroke),
            )
        }
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

@Composable
private fun ComingLater(title: String, detail: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.width(0.dp))
    }
}

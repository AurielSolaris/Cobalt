package app.auriel.cobalt.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.auriel.cobalt.ui.theme.CobaltTheme

/** Which sheet is up, if any. Interface state, so it lives here and not in the controller. */
private enum class Sheet { Menu, Tabs }

/**
 * The browser shell.
 *
 * The page, and one toolbar under it: address, tab count, ⋮. Nothing is drawn
 * above the page and nothing floats over it (decision 0002, amended). The tab
 * switcher and the options menu are sheets that rise from that toolbar, so
 * everything is within a thumb's reach and is only on screen while in use.
 * While the keyboard is up the toolbar rides on top of it.
 *
 * The page is [page], supplied by the engine and composed exactly once. The
 * home page and the placeholder sections are drawn *over* it rather than
 * instead of it: Chromium's surface survives, and a tab switch is
 * `BrowserEngine.show`, not a teardown and rebuild of the compositor view.
 */
@Composable
fun BrowserApp(
    state: BrowserState,
    page: @Composable (Modifier) -> Unit,
    onAddressChanged: (String) -> Unit,
    onGo: () -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    onForward: () -> Unit,
    onHome: () -> Unit,
    onScreenshot: (Rect) -> Unit,
    onSectionSelected: (Section) -> Unit,
    onNewTab: (Boolean) -> Unit,
    onTabSelected: (Long) -> Unit,
    onCloseTab: (Long) -> Unit,
    onCloseAllTabs: (Boolean) -> Unit,
    onSheetOpening: () -> Unit,
) {
    val tab = state.activeTab
    var sheet by remember { mutableStateOf<Sheet?>(null) }

    // Where the page is, for the screenshot. Remembered, not state: nothing
    // redraws when it changes, and a plain local would reset on recomposition
    // while layout, which is what sets it, need not run again.
    val pageBounds = remember { arrayOf(Rect.Zero) }

    // Incognito recolours the whole shell, not just a badge on it: being wrong
    // about which mode you are in is an asymmetric mistake.
    CobaltTheme(incognito = tab.incognito) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.safeDrawingPadding()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onGloballyPositioned { pageBounds[0] = it.boundsInWindow() }
                ) {
                    page(Modifier.fillMaxSize())

                    when {
                        state.section != Section.Home -> Covering { SectionScreen(state.section) }

                        tab.invalidAddress != null -> Covering {
                            CenteredMessage(
                                headline = tab.invalidAddress.headline,
                                detail = tab.invalidAddress.detail,
                            )
                        }

                        !tab.hasPage -> Covering { HomeContent(incognito = tab.incognito) }
                    }
                }

                if (tab.isLoading && state.section == Section.Home) {
                    LinearProgressIndicator(
                        progress = { tab.page.progress },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                } else {
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }

                AddressBar(
                    text = tab.addressText,
                    isLoading = tab.isLoading,
                    incognito = tab.incognito,
                    tabCount = state.tabs.size,
                    tabsOpen = sheet == Sheet.Tabs,
                    onTextChanged = onAddressChanged,
                    onGo = onGo,
                    onReload = onReload,
                    onStop = onStop,
                    onTabs = {
                        onSheetOpening()
                        sheet = Sheet.Tabs
                    },
                    onMenu = {
                        onSheetOpening()
                        sheet = Sheet.Menu
                    },
                )
            }

            when (sheet) {
                Sheet.Menu -> MenuSheet(
                    actions = MenuActions(
                        canGoForward = tab.page.canGoForward,
                        hasPage = tab.hasPage && state.section == Section.Home,
                        onNewTab = { onNewTab(false) },
                        onForward = onForward,
                        onReload = onReload,
                        onScreenshot = { onScreenshot(pageBounds[0]) },
                        onHome = onHome,
                        onExtensions = { onSectionSelected(Section.Extensions) },
                        onDownloads = { onSectionSelected(Section.Downloads) },
                        onBookmarks = { onSectionSelected(Section.Bookmarks) },
                        onSettings = { onSectionSelected(Section.Settings) },
                    ),
                    onDismiss = { sheet = null },
                )

                Sheet.Tabs -> TabsSheet(
                    state = state,
                    onDismiss = { sheet = null },
                    onTabSelected = onTabSelected,
                    onCloseTab = onCloseTab,
                    onNewTab = onNewTab,
                    onCloseAll = onCloseAllTabs,
                )

                null -> Unit
            }
        }
    }
}

/**
 * An opaque layer over the page that takes every touch.
 *
 * Painting over Chromium's surface is not enough: without a pointer handler a
 * touch falls through this layer to the page underneath, and the user taps a
 * link they cannot see.
 */
@Composable
private fun Covering(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent()
                }
            }
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            content()
        }
    }
}

/** The places that are not pages yet. Back, or any tab, returns to the page. */
@Composable
private fun SectionScreen(section: Section) {
    when (section) {
        Section.Home, Section.Tabs -> Unit

        // Reached only on the document engine: with Chromium, Extensions opens
        // chrome://extensions in a tab instead.
        Section.Extensions -> ComingLater(
            title = "Extensions",
            detail = "Extensions run on Chromium's own extension system, which this " +
                "build does not carry.",
        )

        Section.Bookmarks -> ComingLater(
            title = "Bookmarks",
            detail = "Bookmarks arrive on top of Chromium's own store. " +
                "Cobalt would rather show nothing than a list that forgets itself.",
        )

        Section.Downloads -> ComingLater(
            title = "Downloads",
            detail = "Downloads are the next part of the shell to be built.",
        )

        Section.Settings -> ComingLater(
            title = "Settings",
            detail = "Settings are the next part of the shell to be built.",
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
    }
}

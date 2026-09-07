package app.auriel.cobalt.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.auriel.cobalt.render.DocumentRenderer

/**
 * The address bar.
 *
 * A single row: a scheme indicator, the address, a reload-or-stop action, and
 * the overflow menu.
 */
@Composable
fun AddressBar(
    text: String,
    isLoading: Boolean,
    incognito: Boolean,
    onTextChanged: (String) -> Unit,
    onGo: () -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    /**
     * Committing an address gives the field up.
     *
     * Without this the caret keeps blinking over the loaded page's URL and the
     * keyboard stays up, so the first tap on the page dismisses a keyboard
     * instead of following a link.
     */
    fun commit() {
        keyboard?.hide()
        focusManager.clearFocus(force = true)
        onGo()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(42.dp)
                .background(colors.surfaceVariant, MaterialTheme.shapes.medium)
                .border(1.dp, colors.outlineVariant, MaterialTheme.shapes.medium)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SchemeIndicator(text = text, incognito = incognito)

            Spacer(Modifier.width(8.dp))

            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) {
                    Text(
                        text = "Search or enter address",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChanged,
                    singleLine = true,
                    textStyle = LocalTextStyle.current.merge(
                        TextStyle(
                            color = colors.onSurface,
                            fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                        )
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                        autoCorrectEnabled = false,
                    ),
                    keyboardActions = KeyboardActions(onGo = { commit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        IconButton(onClick = if (isLoading) onStop else onReload) {
            Icon(
                imageVector = if (isLoading) Icons.Filled.Close else Icons.Filled.Refresh,
                contentDescription = if (isLoading) "Stop" else "Reload",
                tint = colors.onSurfaceVariant,
            )
        }

        OverflowMenu(
            onOpenBookmarks = onOpenBookmarks,
            onOpenSettings = onOpenSettings,
        )
    }
}

/**
 * The menu at the top right.
 *
 * Bookmarks live here rather than on the bottom bar. A bookmark is a property of
 * the page you are looking at, so the control belongs next to the address that
 * identifies it — and saving one is a per-page action, not a place you navigate
 * to. That leaves the bar for the four things that are places: home, extensions,
 * tabs, and downloads.
 */
@Composable
private fun OverflowMenu(
    onOpenBookmarks: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "More",
                tint = colors.onSurfaceVariant,
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.surfaceContainerHigh,
            shape = MaterialTheme.shapes.medium,
        ) {
            DropdownMenuItem(
                text = { Text("Bookmarks") },
                leadingIcon = { Icon(Icons.Outlined.BookmarkBorder, contentDescription = null) },
                onClick = {
                    expanded = false
                    onOpenBookmarks()
                },
            )
            DropdownMenuItem(
                text = { Text("Settings") },
                leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                onClick = {
                    expanded = false
                    onOpenSettings()
                },
            )
        }
    }
}

/**
 * The padlock, or the absence of one.
 *
 * A browser that shows the same icon for http and https is teaching its user
 * that the distinction does not matter. The warning triangle is deliberately not
 * subtle.
 */
@Composable
private fun SchemeIndicator(text: String, incognito: Boolean) {
    val colors = MaterialTheme.colorScheme
    val isCleartext = text.startsWith("http://", ignoreCase = true)

    val (icon, tint, description) = when {
        incognito -> Triple(Icons.Outlined.VisibilityOff, colors.primary, "Incognito")
        isCleartext -> Triple(Icons.Outlined.Warning, colors.error, "Not secure")
        text.isEmpty() -> Triple(Icons.Outlined.Search, colors.onSurfaceVariant, "Search")
        else -> Triple(Icons.Outlined.Lock, colors.onSurfaceVariant, "Secure")
    }

    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = tint,
        modifier = Modifier.size(16.dp),
    )
}

/** The content area: home page, loading, document, or error. */
@Composable
fun PageContent(
    tab: Tab,
    onLinkClicked: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        when (val content = tab.content) {
            Content.Home -> HomeContent(incognito = tab.incognito)

            is Content.Loading -> LoadingMessage(host = content.url.host)

            is Content.Loaded -> DocumentRenderer(
                document = content.document,
                onLinkClick = onLinkClicked,
                modifier = Modifier.fillMaxSize(),
            )

            is Content.Failed -> CenteredMessage(
                headline = content.error.headline,
                detail = content.error.detail,
                headlineColor = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun LoadingMessage(host: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(26.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = host,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun CenteredMessage(
    headline: String,
    detail: String,
    headlineColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = headline,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = headlineColor,
            textAlign = TextAlign.Center,
        )
        if (detail.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

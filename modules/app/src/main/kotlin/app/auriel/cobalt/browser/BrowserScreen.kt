package app.auriel.cobalt.browser

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.auriel.cobalt.browser.engine.CertificateSummary
import app.auriel.cobalt.browser.engine.Security
import app.auriel.cobalt.ui.theme.JetBrainsMono
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The browser's only toolbar: one row at the bottom of the screen.
 *
 * The address (with reload or stop inside it), the tab count, and ⋮. That is
 * all that stays on screen, by choice: less competing with the page, and all
 * of it under the thumb. Back is the system gesture; everything else is one
 * tap away in the menu sheet.
 */
@Composable
fun AddressBar(
    text: String,
    pageUrl: String?,
    isLoading: Boolean,
    incognito: Boolean,
    security: Security,
    certificate: () -> CertificateSummary?,
    tabCount: Int,
    tabsOpen: Boolean,
    onTextChanged: (String) -> Unit,
    onGo: () -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    onTabs: () -> Unit,
    onMenu: () -> Unit,
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
            .background(colors.surfaceContainerLow)
            .padding(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .background(colors.surfaceVariant, MaterialTheme.shapes.medium)
                .border(1.dp, colors.outlineVariant, MaterialTheme.shapes.medium)
                .padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SecurityIndicator(
                security = security,
                incognito = incognito,
                typing = text != pageUrl,
                pageUrl = pageUrl,
                certificate = certificate,
            )

            Spacer(Modifier.width(4.dp))

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

            // Inside the field: it acts on the address, and it costs the row
            // no width of its own.
            if (text.isNotEmpty()) {
                IconButton(onClick = if (isLoading) onStop else onReload, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = if (isLoading) Icons.Filled.Close else Icons.Filled.Refresh,
                        contentDescription = if (isLoading) "Stop" else "Reload",
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        IconButton(onClick = onTabs) {
            TabCountIcon(count = tabCount, selected = tabsOpen)
        }

        IconButton(onClick = onMenu) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "Menu",
                tint = colors.onSurfaceVariant,
            )
        }
    }
}

/** The tab-count square, which is the switcher's icon in every browser. */
@Composable
private fun TabCountIcon(count: Int, selected: Boolean) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

    Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 1.5.dp.toPx()
            drawRect(
                color = color,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
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

/**
 * The padlock, or the absence of one, and what it means when tapped.
 *
 * The state is the engine's verdict ([Security]), not a guess from the URL:
 * `https://` with a certificate error is not secure, and only the engine
 * knows. A browser that shows the same icon for http and https is teaching its
 * user that the distinction does not matter, so the warning triangle is
 * deliberately not subtle, and the popup says it in words too. A theme may
 * recolor all of this, so meaning is never carried by color alone (0007).
 */
@Composable
private fun SecurityIndicator(
    security: Security,
    incognito: Boolean,
    typing: Boolean,
    pageUrl: String?,
    certificate: () -> CertificateSummary?,
) {
    val colors = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }

    val (icon, tint, description) = when {
        // While typing, the icon describes the field, not the page behind it.
        typing || security == Security.None -> Triple(Icons.Outlined.Search, colors.onSurfaceVariant, "Search")
        security == Security.Dangerous -> Triple(Icons.Outlined.Warning, colors.error, "Dangerous")
        security == Security.NotSecure -> Triple(Icons.Outlined.Warning, colors.error, "Not secure")
        incognito -> Triple(Icons.Outlined.VisibilityOff, colors.primary, "Incognito")
        security == Security.Internal -> Triple(Icons.Outlined.Info, colors.onSurfaceVariant, "Cobalt page")
        else -> Triple(Icons.Outlined.Lock, colors.onSurfaceVariant, "Secure")
    }
    val tappable = !typing && security != Security.None

    Box {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier
                .size(28.dp)
                .clip(MaterialTheme.shapes.small)
                .then(if (tappable) Modifier.clickable { open = true } else Modifier)
                .padding(6.dp),
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = colors.surfaceContainerHigh,
            shape = MaterialTheme.shapes.medium,
        ) {
            // Read when opened, not while drawing the toolbar: it decodes a
            // certificate, and nobody needs that until they ask.
            val cert = remember(open) { if (open && security == Security.Secure) certificate() else null }
            SecurityDetails(security, pageUrl, cert)
        }
    }
}

@Composable
private fun SecurityDetails(security: Security, pageUrl: String?, cert: CertificateSummary?) {
    val colors = MaterialTheme.colorScheme
    val host = pageUrl?.substringAfter("://")?.substringBefore('/')
    val (headline, explanation) = when (security) {
        Security.Secure -> "Connection is secure" to
            "Information you send or receive is private between you and this site. " +
            "Its certificate was issued by an authority your phone trusts."
        Security.NotSecure -> "Connection is not secure" to
            "Anything you send or see here can be read or changed on its way. " +
            "Do not enter passwords or card details."
        Security.Dangerous -> "This site is not safe" to
            "Its certificate is not valid, or it has been reported as harmful. " +
            "Someone may be pretending to be this site."
        Security.Internal -> "This is a Cobalt page" to
            "It is part of the browser and is not sent over any network."
        Security.None -> "" to ""
    }

    Column(Modifier.width(300.dp).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            headline,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (security == Security.Secure || security == Security.Internal) colors.onSurface else colors.error,
        )
        if (host != null) {
            Text(host, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Text(explanation, style = MaterialTheme.typography.bodySmall, color = colors.onSurface)

        if (cert != null) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = colors.outlineVariant)
            Spacer(Modifier.height(10.dp))
            Text("Certificate", style = MaterialTheme.typography.labelLarge, color = colors.primary)
            Spacer(Modifier.height(6.dp))
            val date = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }
            CertLine("Issued to", cert.issuedTo)
            CertLine("Issued by", cert.issuedBy)
            CertLine("Valid", "${date.format(cert.validFrom)} to ${date.format(cert.validUntil)}")
            CertLine("Chain", "${cert.chainLength} certificate${if (cert.chainLength == 1) "" else "s"}")
            Spacer(Modifier.height(6.dp))
            Text("SHA-256", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            Text(
                cert.sha256,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                color = colors.onSurface,
            )
        }
    }
}

@Composable
private fun CertLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
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

package app.auriel.cobalt.browser.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.auriel.cobalt.R
import app.auriel.cobalt.ui.theme.JetBrainsMono

/** What only the running app knows, handed down from the Activity. */
data class AboutInfo(
    val engineName: String,
    val creditsUrl: String?,
)

private const val SOURCE_URL = "https://github.com/AurielSolaris/Cobalt"

/**
 * About Cobalt: which build this is, what it is made of, and where its source
 * and licences are. Plain facts; anything a bug report needs is here, and the
 * version line is monospaced so it can be read back exactly.
 */
@Composable
internal fun AboutScreen(info: AboutInfo, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val version = remember {
        // The int-flags overload: the typed one is API 33, Cobalt's floor is 29.
        @Suppress("DEPRECATION")
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        "${pkg.versionName} (${pkg.longVersionCode})"
    }

    Screen("About", onBack = onBack) {
        Column(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(88.dp).clip(CircleShape).background(colorResource(R.color.icon_background)),
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(R.mipmap.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(132.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = colors.onSurface,
            )
            Text(
                version,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono),
                color = colors.onSurfaceVariant,
            )
        }

        Label("Built from")
        Fact("Engine", info.engineName)
        Fact("Interface", "Cobalt's own, in Kotlin and Compose")
        Fact("Extensions", "Manifest V2 and V3; uBlock Origin included")
        Fact("Target", "4 GB of RAM and 2 CPU cores")

        Label("Licence")
        Text(
            "Cobalt is free software under the GNU General Public License, " +
                "version 3. It builds on Kiwi Browser's work (BSD 3-Clause) and " +
                "on Chromium, whose components each carry their own licences.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurface,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(8.dp))

        HorizontalDivider(color = colors.outlineVariant, modifier = Modifier.padding(top = 12.dp))
        Link("Source code", SOURCE_URL.removePrefix("https://")) { onOpen(SOURCE_URL) }
        info.creditsUrl?.let { url -> Link("Open-source licences", "Every third-party licence in this build") { onOpen(url) } }
        Link("Report a problem", "Issues on GitHub") { onOpen("$SOURCE_URL/issues") }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Link(title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

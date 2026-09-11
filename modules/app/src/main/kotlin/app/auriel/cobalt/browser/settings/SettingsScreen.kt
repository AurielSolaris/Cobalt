package app.auriel.cobalt.browser.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.auriel.cobalt.ui.theme.Palette
import app.auriel.cobalt.ui.theme.ThemeMode
import app.auriel.cobalt.ui.theme.ThemeStore
import app.auriel.cobalt.ui.theme.ThemeText
import app.auriel.cobalt.ui.theme.activePalette

/** Where in Settings the user is. Local: nothing outside Settings needs to know. */
private sealed interface Page {
    data object Root : Page
    data object Theme : Page
    data class Edit(val palette: Palette, val isNew: Boolean) : Page
    data object About : Page
}

/**
 * Settings: Theme, and About. Other settings arrive with the features they
 * configure rather than as switches for code that is not there.
 */
@Composable
fun SettingsScreen(about: AboutInfo, onClose: () -> Unit, onOpen: (String) -> Unit) {
    var page by remember { mutableStateOf<Page>(Page.Root) }

    BackHandler(enabled = page != Page.Root) {
        page = when (page) {
            is Page.Edit -> Page.Theme
            else -> Page.Root
        }
    }
    // Leaving Settings for a link (source, licences) returns to the page, not
    // to Settings: the link opens in a new tab and that tab is what you see.
    val open: (String) -> Unit = { url ->
        page = Page.Root
        onOpen(url)
    }

    when (val p = page) {
        Page.Root -> Screen("Settings", onBack = onClose) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { page = Page.Theme }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text("Theme", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    val s by ThemeStore.settings.collectAsState()
                    Text(
                        "${s.light.name} by day, ${s.dark.name} by night",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { page = Page.About }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text("About Cobalt", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(about.engineName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Page.About -> AboutScreen(about, onBack = { page = Page.Root }, onOpen = open)

        Page.Theme -> ThemeScreen(
            onBack = { page = Page.Root },
            onEdit = { palette, isNew -> page = Page.Edit(palette, isNew) },
        )

        is Page.Edit -> ThemeEditor(
            initial = p.palette,
            isNew = p.isNew,
            onDone = { page = Page.Theme },
        )
    }
}

/**
 * Choosing themes.
 *
 * The mode first (follow the phone, or pin one), then a row of themes for day
 * and a row for night: any theme can go in either slot. Custom themes appear
 * in both rows beside the presets, and "New theme" starts from whichever
 * theme is showing now, which is usually what a person wants to adjust.
 */
@Composable
private fun ThemeScreen(onBack: () -> Unit, onEdit: (Palette, Boolean) -> Unit) {
    val settings by ThemeStore.settings.collectAsState()
    val current = activePalette()
    val context = LocalContext.current
    var importing by remember { mutableStateOf(false) }

    Screen("Theme", onBack = onBack) {
        Label("Appearance")
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((mode, label) in listOf(ThemeMode.System to "Follow phone", ThemeMode.Light to "Light", ThemeMode.Dark to "Dark")) {
                Choice(label, selected = settings.mode == mode, modifier = Modifier.weight(1f)) {
                    ThemeStore.setMode(mode)
                }
            }
        }

        Label("Light mode theme")
        ThemeRow(settings.all, selectedId = settings.lightId, onPick = ThemeStore::setLight, onEdit = { onEdit(it, false) })

        Label("Dark mode theme")
        ThemeRow(settings.all, selectedId = settings.darkId, onPick = ThemeStore::setDark, onEdit = { onEdit(it, false) })

        Label("Your themes")
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("New theme", icon = Icons.Filled.Add, modifier = Modifier.weight(1f)) {
                onEdit(current.copy(id = ThemeStore.newCustomId(), name = "${current.name} (mine)"), true)
            }
            Choice("Paste theme", icon = Icons.Outlined.ContentPaste, modifier = Modifier.weight(1f)) {
                importing = true
            }
        }
        Text(
            "Custom themes are made here by choosing colors. Tap a custom theme's " +
                "name to edit it. Pasting accepts the text a Cobalt theme is shared as.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }

    if (importing) {
        ImportDialog(
            initial = clipboardText(context),
            onDismiss = { importing = false },
            onImported = {
                importing = false
                onEdit(it, true)
            },
        )
    }
}

@Composable
private fun ThemeRow(
    themes: List<Palette>,
    selectedId: String,
    onPick: (String) -> Unit,
    onEdit: (Palette) -> Unit,
) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (theme in themes) {
            ThemeSwatch(
                palette = theme,
                selected = theme.id == selectedId,
                onClick = { onPick(theme.id) },
                onEdit = if (theme.isCustom) ({ onEdit(theme) }) else null,
            )
        }
    }
}

/**
 * A theme drawn in its own colors: a strip of toolbar, a line of text, the
 * accent. Small, but it is the theme itself rather than a description of it.
 */
@Composable
internal fun ThemeSwatch(palette: Palette, selected: Boolean, onClick: () -> Unit, onEdit: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.width(112.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(palette.bgPrimary)
                .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant, MaterialTheme.shapes.medium)
                .clickable(onClick = onClick),
        ) {
            Column(Modifier.weight(1f).padding(8.dp)) {
                Box(Modifier.width(56.dp).height(6.dp).background(palette.fg))
                Spacer(Modifier.height(5.dp))
                Box(Modifier.width(40.dp).height(5.dp).background(palette.fgMuted))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.width(28.dp).height(10.dp).background(palette.accent))
            }
            Row(
                Modifier.fillMaxWidth().height(20.dp).background(palette.bgSecondary).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).height(10.dp).background(palette.surface).border(1.dp, palette.border))
                Spacer(Modifier.width(4.dp))
                Box(Modifier.size(8.dp).border(1.dp, palette.fgMuted))
            }
        }
        Text(
            text = palette.name,
            style = MaterialTheme.typography.labelMedium,
            color = if (onEdit != null) colors.primary else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(top = 4.dp)
                .then(if (onEdit != null) Modifier.clickable(onClick = onEdit) else Modifier),
        )
    }
}

@Composable
private fun ImportDialog(initial: String, onDismiss: () -> Unit, onImported: (Palette) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste a theme") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    minLines = 6,
                    maxLines = 10,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = app.auriel.cobalt.ui.theme.JetBrainsMono),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (val r = ThemeText.parse(text, ThemeStore.newCustomId())) {
                    is ThemeText.Result.Ok -> onImported(r.palette)
                    is ThemeText.Result.Error -> error = r.message
                }
            }) { Text("Open in editor") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun clipboardText(context: Context): String {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return ""
    val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
    return if ("--" in text) text else ""
}

/** Hands a theme's text to the share sheet, and to the clipboard. */
internal fun shareTheme(context: Context, palette: Palette) {
    val text = ThemeText.format(palette)
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText(palette.name, text))
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "Share ${palette.name}"))
}

// --- Building blocks shared by the settings pages ---------------------------

@Composable
internal fun Screen(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            content()
        }
    }
}

@Composable
internal fun Label(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
internal fun Choice(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .height(42.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) colors.primary.copy(alpha = 0.16f) else colors.surfaceVariant)
            .border(1.dp, if (selected) colors.primary else colors.outlineVariant, MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = colors.onSurface, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) colors.primary else colors.onSurface, maxLines = 1)
    }
}

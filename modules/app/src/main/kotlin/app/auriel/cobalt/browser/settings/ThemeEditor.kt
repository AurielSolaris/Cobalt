package app.auriel.cobalt.browser.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.auriel.cobalt.ui.theme.CobaltTheme
import app.auriel.cobalt.ui.theme.JetBrainsMono
import app.auriel.cobalt.ui.theme.Palette
import app.auriel.cobalt.ui.theme.ThemeStore
import app.auriel.cobalt.ui.theme.ThemeText
import app.auriel.cobalt.ui.theme.contrastChecks
import app.auriel.cobalt.ui.theme.fixContrast

/** One editable token: its label, what it is for, and how to read and write it. */
private class Token(
    val label: String,
    val hint: String,
    val get: (Palette) -> Color,
    val set: (Palette, Color) -> Palette,
)

private val TOKENS = listOf(
    Token("Background", "Behind the page and everything else", { it.bgPrimary }, { p, c -> p.copy(bgPrimary = c) }),
    Token("Toolbar", "The toolbar and sheets", { it.bgSecondary }, { p, c -> p.copy(bgSecondary = c) }),
    Token("Surface", "Fields and cards", { it.surface }, { p, c -> p.copy(surface = c) }),
    Token("Border", "Dividers and outlines", { it.border }, { p, c -> p.copy(border = c) }),
    Token("Text", "Primary text and icons", { it.fg }, { p, c -> p.copy(fg = c) }),
    Token("Secondary text", "Hints and inactive icons", { it.fgMuted }, { p, c -> p.copy(fgMuted = c) }),
    Token("Accent", "Everything you can tap", { it.accent }, { p, c -> p.copy(accent = c) }),
    Token("Accent, second stop", "The other end of the accent gradient", { it.accentEnd }, { p, c -> p.copy(accentEnd = c) }),
    Token("Success", "Secure, done", { it.success }, { p, c -> p.copy(success = c) }),
    Token("Warning", "Needs attention", { it.warning }, { p, c -> p.copy(warning = c) }),
    Token("Danger", "Insecure, failed", { it.danger }, { p, c -> p.copy(danger = c) }),
)

/**
 * The theme editor: colors in, theme out, on the phone.
 *
 * This is the primary way a theme is made (0007). Every token is a swatch you
 * can tap for a picker and a hex field you can type into; the preview at the
 * top is drawn with the theme being edited, so there is no save-and-look loop.
 */
@Composable
internal fun ThemeEditor(initial: Palette, isNew: Boolean, onDone: () -> Unit) {
    var draft by remember { mutableStateOf(initial) }
    var picking by remember { mutableStateOf<Token?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Screen(if (isNew) "New theme" else "Edit theme", onBack = onDone) {
        Box(Modifier.padding(horizontal = 16.dp)) { Preview(draft) }

        Label("Name")
        OutlinedTextField(
            value = draft.name,
            onValueChange = { draft = draft.copy(name = it.take(40)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )

        Label("Kind")
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("Dark theme", selected = draft.isDark, modifier = Modifier.weight(1f)) { draft = draft.copy(isDark = true) }
            Choice("Light theme", selected = !draft.isDark, modifier = Modifier.weight(1f)) { draft = draft.copy(isDark = false) }
        }

        Label("Colors")
        for (token in TOKENS) {
            TokenRow(token, draft, onChange = { draft = it }, onPick = { picking = token })
        }

        val failing = draft.contrastChecks().filterNot { it.passes }
        if (failing.isNotEmpty()) {
            Label("Readability")
            for (check in failing) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${check.label}: %.1f:1, below %.1f:1".format(check.ratio, check.required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    // Warn, never block (0007): the theme still saves as it is.
                    TextButton(onClick = { draft = fix(draft, check.label) }) { Text("Fix") }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Choice("Save", selected = true, modifier = Modifier.weight(1f)) {
                ThemeStore.save(draft)
                onDone()
            }
            Choice("Share", modifier = Modifier.weight(1f)) { shareTheme(context, draft) }
            if (!isNew) Choice("Delete", modifier = Modifier.weight(1f)) { confirmDelete = true }
        }
    }

    picking?.let { token ->
        ColorPickerDialog(
            title = token.label,
            initial = token.get(draft),
            suggestions = listOf(draft.bgPrimary, draft.fg, draft.accent, draft.accentEnd, draft.success, draft.warning, draft.danger),
            onDismiss = { picking = null },
            onPicked = {
                draft = token.set(draft, it)
                picking = null
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${draft.name}?") },
            text = { Text("Where it is in use, the default theme takes its place.") },
            confirmButton = {
                TextButton(onClick = {
                    ThemeStore.delete(draft.id)
                    onDone()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Nudges the side of a failing pair that is the "ink", never the ground. */
private fun fix(p: Palette, label: String): Palette = when (label) {
    "Text on background" -> p.copy(fg = fixContrast(p.fg, p.bgPrimary, 4.5))
    "Text on toolbar" -> p.copy(fg = fixContrast(p.fg, p.bgSecondary, 4.5))
    "Secondary text" -> p.copy(fgMuted = fixContrast(p.fgMuted, p.bgPrimary, 3.0))
    "Accent on background" -> p.copy(accent = fixContrast(p.accent, p.bgPrimary, 3.0))
    else -> p
}

@Composable
private fun TokenRow(token: Token, draft: Palette, onChange: (Palette) -> Unit, onPick: () -> Unit) {
    val color = token.get(draft)
    // The field keeps what is being typed, even when it is not a color yet;
    // the theme only changes when it becomes one.
    var text by remember(color) { mutableStateOf(ThemeText.hex(color)) }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(color)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                .clickable(onClick = onPick),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(token.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(token.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(
            value = text,
            onValueChange = { typed ->
                text = typed.take(22)
                ThemeText.parseColor(typed)?.let { onChange(token.set(draft, it)) }
            },
            singleLine = true,
            isError = ThemeText.parseColor(text) == null,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            modifier = Modifier.width(118.dp),
        )
    }
}

/** The theme being edited, drawn with itself: a page, a card, the toolbar. */
@Composable
private fun Preview(p: Palette) {
    CobaltTheme(palette = p) {
        val c = MaterialTheme.colorScheme
        Column(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .border(1.dp, c.outlineVariant, MaterialTheme.shapes.medium)
                .background(c.background),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(p.name.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleLarge, color = c.onSurface)
                Spacer(Modifier.height(4.dp))
                Text("Body text reads like this.", style = MaterialTheme.typography.bodyMedium, color = c.onSurface)
                Text("Secondary text, quieter.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clip(MaterialTheme.shapes.medium).background(c.primary).padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text("Button", style = MaterialTheme.typography.labelLarge, color = c.onPrimary)
                    }
                    Text("Link", style = MaterialTheme.typography.bodyMedium, color = c.primary)
                    Box(Modifier.size(10.dp).background(p.success))
                    Box(Modifier.size(10.dp).background(p.warning))
                    Box(Modifier.size(10.dp).background(p.danger))
                }
            }
            Row(
                Modifier.fillMaxWidth().background(c.surfaceContainerLow).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.weight(1f).height(28.dp).clip(MaterialTheme.shapes.medium)
                        .background(c.surfaceVariant).border(1.dp, c.outlineVariant, MaterialTheme.shapes.medium)
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) { Text("example.org", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant) }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(16.dp).border(1.5.dp, c.onSurfaceVariant))
                Spacer(Modifier.width(6.dp))
            }
        }
    }
}

/**
 * A color picker that needs no color theory: hue, then how vivid, then how
 * bright, with the result and its hex code always visible, and the theme's
 * own colors one tap away.
 */
@Composable
private fun ColorPickerDialog(
    title: String,
    initial: Color,
    suggestions: List<Color>,
    onDismiss: () -> Unit,
    onPicked: (Color) -> Unit,
) {
    val hsv = remember(initial) { FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    val color = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))

    fun take(c: Color) {
        val out = FloatArray(3)
        android.graphics.Color.colorToHSV(c.toArgb(), out)
        hue = out[0]; sat = out[1]; value = out[2]
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(48.dp).clip(MaterialTheme.shapes.medium).background(color)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(ThemeText.hex(color), style = MaterialTheme.typography.bodyLarge.copy(fontFamily = JetBrainsMono))
                }
                Spacer(Modifier.height(12.dp))
                Text("Hue", style = MaterialTheme.typography.labelMedium)
                Slider(value = hue, onValueChange = { hue = it }, valueRange = 0f..360f)
                Text("Vividness", style = MaterialTheme.typography.labelMedium)
                Slider(value = sat, onValueChange = { sat = it })
                Text("Brightness", style = MaterialTheme.typography.labelMedium)
                Slider(value = value, onValueChange = { value = it })
                Spacer(Modifier.height(4.dp))
                // Nine at most; sized so all of them fit the dialog's width.
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (s in (suggestions + listOf(Color.White, Color.Black)).distinct()) {
                        Box(
                            Modifier.size(21.dp).clip(MaterialTheme.shapes.small).background(s)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                                .clickable { take(s) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPicked(color) }) { Text("Use") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

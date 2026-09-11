package app.auriel.cobalt.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/**
 * The app's theme: the active [Palette] from [ThemeStore], as Material colors.
 *
 * Which palette is active follows the phone's light/dark setting unless the
 * user has pinned one (see [ThemeSettings.active]). Incognito replaces it with
 * the derived incognito palette of the dark theme, whatever the phone says.
 *
 * @param palette overrides the stored choice; the theme editor uses it to
 *   preview a theme that is not saved yet
 */
@Composable
fun CobaltTheme(
    incognito: Boolean = false,
    palette: Palette? = null,
    content: @Composable () -> Unit,
) {
    val settings by ThemeStore.settings.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val chosen = palette ?: if (incognito) settings.dark.incognito() else settings.active(systemDark)
    val colors = remember(chosen) { chosen.toColorScheme() }

    MaterialTheme(
        colorScheme = colors,
        shapes = CobaltShapes,
        typography = CobaltTypography,
        content = content,
    )
}

/** The palette the rest of the app would draw with right now. */
@Composable
fun activePalette(): Palette {
    val settings by ThemeStore.settings.collectAsState()
    return settings.active(isSystemInDarkTheme())
}

/**
 * Eleven tokens into Material's roles.
 *
 * The shell uses a small set of them, consistently: `background` behind the
 * page, `surfaceContainerLow` for the toolbar and sheets, `surfaceVariant` for
 * fields and cards, `onSurface`/`onSurfaceVariant` for text, `primary` for
 * anything interactive, `outlineVariant` for dividers. Everything else is
 * filled so that a Material component nobody has themed by hand still lands
 * inside the palette rather than on Material's purple defaults.
 */
fun Palette.toColorScheme(): ColorScheme {
    val onAccent = if (contrast(Color.Black, accent) >= contrast(Color.White, accent)) Color.Black else Color.White
    val lowest = lerp(bgPrimary, if (isDark) Color.Black else Color.White, 0.35f)
    val outline = lerp(fgMuted, bgPrimary, 0.35f)
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = surface,
        onPrimaryContainer = accent,
        inversePrimary = accent,
        secondary = lerp(accent, fgMuted, 0.3f),
        onSecondary = onAccent,
        secondaryContainer = surface,
        onSecondaryContainer = fg,
        tertiary = accentEnd,
        onTertiary = onAccent,
        tertiaryContainer = surface,
        onTertiaryContainer = accentEnd,
        background = bgPrimary,
        onBackground = fg,
        surface = bgPrimary,
        onSurface = fg,
        surfaceVariant = surface,
        onSurfaceVariant = fgMuted,
        surfaceTint = accent,
        inverseSurface = fg,
        inverseOnSurface = bgPrimary,
        surfaceBright = surface,
        surfaceDim = lowest,
        surfaceContainerLowest = lowest,
        surfaceContainerLow = bgSecondary,
        surfaceContainer = bgSecondary,
        surfaceContainerHigh = lerp(bgSecondary, surface, 0.6f),
        surfaceContainerHighest = surface,
        outline = outline,
        outlineVariant = border,
        error = danger,
        onError = if (contrast(Color.Black, danger) >= contrast(Color.White, danger)) Color.Black else Color.White,
        errorContainer = surface,
        onErrorContainer = danger,
        scrim = Color(0xCC000000),
    )
}

/**
 * Boxy, not bubbly.
 *
 * Material 3's defaults run from 4dp to 28dp, which reads as consumer-soft.
 * Cobalt is a tool, and its corners are close to square: enough radius to keep
 * an edge from looking accidental, not enough to round anything off.
 */
private val CobaltShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(1.dp),
    medium = RoundedCornerShape(2.dp),
    large = RoundedCornerShape(2.dp),
    extraLarge = RoundedCornerShape(3.dp),
)

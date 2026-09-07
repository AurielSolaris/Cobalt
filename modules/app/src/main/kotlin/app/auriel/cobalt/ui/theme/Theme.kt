package app.auriel.cobalt.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * One Dark.
 *
 * Cobalt will ship a set of themes eventually; this is the one it starts with,
 * and it is defined as named palette entries rather than scattered literals so
 * that adding the second theme is a matter of supplying another set of them.
 *
 * The values are Atom's One Dark, unchanged. Using an established palette rather
 * than inventing one means the syntax colours in a rendered code block already
 * agree with the interface around them.
 */
object OneDark {
    val Background = Color(0xFF282C34)
    val BackgroundDeep = Color(0xFF21252B)
    val BackgroundDeepest = Color(0xFF1B1F23)
    val Surface = Color(0xFF2C313A)
    val SurfaceRaised = Color(0xFF323842)
    val Border = Color(0xFF3E4451)

    val Foreground = Color(0xFFABB2BF)
    val ForegroundBright = Color(0xFFD7DAE0)
    val Muted = Color(0xFF5C6370)

    /** The accent. Everything interactive is this colour or a shade of it. */
    val Blue = Color(0xFF61AFEF)
    val BlueDim = Color(0xFF4B8BC4)
    val BlueDeep = Color(0xFF2C5F8A)
    val Green = Color(0xFF98C379)
    val Red = Color(0xFFE06C75)
    val Yellow = Color(0xFFE5C07B)
    val Purple = Color(0xFFC678DD)
    val Cyan = Color(0xFF56B6C2)
    val Orange = Color(0xFFD19A66)
}

/**
 * The light counterpart, so the app is not unusable in daylight before the theme
 * picker exists. One Light, matched to the palette above.
 */
private object OneLight {
    val Background = Color(0xFFFAFAFA)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceRaised = Color(0xFFF0F0F1)
    val Border = Color(0xFFD4D4D6)

    val Foreground = Color(0xFF383A42)
    val Muted = Color(0xFF9598A6)

    val Blue = Color(0xFF4078F2)
    val Red = Color(0xFFE45649)
    val Green = Color(0xFF50A14F)
}

private val DarkColors = darkColorScheme(
    primary = OneDark.Blue,
    onPrimary = OneDark.BackgroundDeepest,
    primaryContainer = OneDark.SurfaceRaised,
    onPrimaryContainer = OneDark.Blue,
    secondary = OneDark.BlueDim,
    onSecondary = OneDark.BackgroundDeepest,
    tertiary = OneDark.Cyan,
    onTertiary = OneDark.BackgroundDeepest,
    background = OneDark.Background,
    onBackground = OneDark.Foreground,
    surface = OneDark.Background,
    onSurface = OneDark.ForegroundBright,
    surfaceVariant = OneDark.Surface,
    onSurfaceVariant = OneDark.Foreground,
    surfaceContainer = OneDark.BackgroundDeep,
    surfaceContainerHigh = OneDark.SurfaceRaised,
    surfaceContainerHighest = OneDark.SurfaceRaised,
    surfaceContainerLow = OneDark.BackgroundDeep,
    surfaceContainerLowest = OneDark.BackgroundDeepest,
    outline = OneDark.Muted,
    outlineVariant = OneDark.Border,
    error = OneDark.Red,
    onError = OneDark.BackgroundDeepest,
    errorContainer = OneDark.Surface,
    onErrorContainer = OneDark.Red,
    scrim = Color(0xCC000000),
)

private val LightColors = lightColorScheme(
    primary = OneLight.Blue,
    onPrimary = Color.White,
    secondary = OneLight.Blue,
    background = OneLight.Background,
    onBackground = OneLight.Foreground,
    surface = OneLight.Surface,
    onSurface = OneLight.Foreground,
    surfaceVariant = OneLight.SurfaceRaised,
    onSurfaceVariant = OneLight.Foreground,
    surfaceContainer = OneLight.SurfaceRaised,
    surfaceContainerHigh = OneLight.SurfaceRaised,
    outline = OneLight.Muted,
    outlineVariant = OneLight.Border,
    error = OneLight.Red,
    onError = Color.White,
)

/**
 * Incognito stays in the blue family but shifts to cyan on a darker ground.
 *
 * The mode has to be obvious at a glance and from across a room — a small badge
 * is not enough, because the cost of being wrong about which mode you are in is
 * asymmetric. Cyan is far enough from the standard blue to read as a different
 * mode without leaving the palette.
 */
private val IncognitoColors = DarkColors.copy(
    primary = OneDark.Cyan,
    onPrimary = OneDark.BackgroundDeepest,
    background = OneDark.BackgroundDeepest,
    surface = OneDark.BackgroundDeepest,
    surfaceContainer = Color(0xFF16191D),
    surfaceContainerLow = Color(0xFF16191D),
    surfaceContainerLowest = Color(0xFF101317),
    surfaceVariant = Color(0xFF23272E),
)

@Composable
fun CobaltTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    incognito: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = when {
        incognito -> IncognitoColors
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colors,
        shapes = CobaltShapes,
        typography = CobaltTypography,
        content = content,
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

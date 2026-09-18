package app.auriel.cobalt.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

/**
 * A theme, as the tokens of decision 0007 and nothing else.
 *
 * Everything the interface draws is derived from these. They are what the
 * on-device editor edits, what the text format carries, and what a preset is.
 * Material's forty-odd color roles are an implementation detail computed from
 * them ([toColorScheme]), not something a person making a theme should face.
 */
data class Palette(
    /** Stable identity: a preset's fixed id, or `custom-…` for a user's own. */
    val id: String,
    val name: String,
    /** Whether this is a dark theme; decides which Material baseline it builds on. */
    val isDark: Boolean,
    /** The page and chrome behind everything. */
    val bgPrimary: Color,
    /** Toolbar, sheets, raised areas. */
    val bgSecondary: Color,
    /** Cards and fields on top of those. */
    val surface: Color,
    /** Dividers and outlines. */
    val border: Color,
    /** Primary text and icons. */
    val fg: Color,
    /** Secondary text, placeholders, inactive icons. */
    val fgMuted: Color,
    /** Everything interactive. */
    val accent: Color,
    /** The gradient's second stop, for emphasis surfaces. */
    val accentEnd: Color,
    /** Security and status. Overridable, never unset (0007). */
    val success: Color,
    val warning: Color,
    val danger: Color,
) {
    val isCustom: Boolean get() = id.startsWith(CUSTOM_PREFIX)

    companion object {
        const val CUSTOM_PREFIX = "custom-"
    }
}

/** The four themes Cobalt ships. */
object Presets {

    /**
     * Cobalt, the default dark theme: One Dark, modified, after 0007: IBM
     * Carbon's neutral greys and ice-blue ramp, with One Dark's blue-grey
     * warmth so it does not read as clinical. Brighter text than One Dark.
     */
    val Cobalt = Palette(
        id = "cobalt",
        name = "Cobalt",
        isDark = true,
        bgPrimary = Color(0xFF16181C),
        bgSecondary = Color(0xFF1D2026),
        surface = Color(0xFF2A2E36),
        border = Color(0xFF3A3F4B),
        fg = Color(0xFFF4F4F4),
        fgMuted = Color(0xFFA2A9B4),
        accent = Color(0xFF78A9FF),
        accentEnd = Color(0xFF82CFFF),
        success = Color(0xFF42BE65),
        warning = Color(0xFFF1C21B),
        danger = Color(0xFFFA4D56),
    )

    /** Atom's One Dark, unchanged: what 0.1.0 shipped. */
    val OneDark = Palette(
        id = "one-dark",
        name = "One Dark",
        isDark = true,
        bgPrimary = Color(0xFF282C34),
        bgSecondary = Color(0xFF21252B),
        surface = Color(0xFF2C313A),
        border = Color(0xFF3E4451),
        fg = Color(0xFFD7DAE0),
        fgMuted = Color(0xFFABB2BF),
        accent = Color(0xFF61AFEF),
        accentEnd = Color(0xFF56B6C2),
        success = Color(0xFF98C379),
        warning = Color(0xFFE5C07B),
        danger = Color(0xFFE06C75),
    )

    /**
     * Ethan Schoonover's Solarized, light. The base tones are the published
     * values except the text, below; the surface between base3 and base2 is
     * interpolated, because Solarized defines two backgrounds and an interface
     * needs three.
     */
    val SolarizedLight = Palette(
        id = "solarized-light",
        name = "Solarized Light",
        isDark = false,
        bgPrimary = Color(0xFFFDF6E3),   // base3
        bgSecondary = Color(0xFFEEE8D5), // base2
        surface = Color(0xFFF6F0DC),
        border = Color(0xFFD6CFBB),
        // base01 (#586E75) nudged 3% darker: on the base2 toolbar base01 is
        // 4.39:1, just under the 4.5:1 body text needs. Indistinguishable
        // by eye; measurable by anyone reading small text in daylight.
        fg = Color(0xFF566B72),
        fgMuted = Color(0xFF657B83),     // base00: body
        accent = Color(0xFF268BD2),      // blue
        accentEnd = Color(0xFF2AA198),   // cyan
        success = Color(0xFF859900),
        warning = Color(0xFFB58900),
        danger = Color(0xFFDC322F),
    )

    /** Solarized, dark. Same accents; base03/base02 grounds, base1/base0 text. */
    val SolarizedDark = Palette(
        id = "solarized-dark",
        name = "Solarized Dark",
        isDark = true,
        bgPrimary = Color(0xFF002B36),   // base03
        bgSecondary = Color(0xFF073642), // base02
        surface = Color(0xFF0D3D49),
        border = Color(0xFF28535E),
        fg = Color(0xFF93A1A1),          // base1: emphasised content
        fgMuted = Color(0xFF839496),     // base0: body
        accent = Color(0xFF268BD2),
        accentEnd = Color(0xFF2AA198),
        success = Color(0xFF859900),
        warning = Color(0xFFB58900),
        danger = Color(0xFFDC322F),
    )

    /**
     * Dreamy Purple, after the Chrome theme of that name
     * (nbfadodpeabiajchiglbobhhgjihjnio), which a Cobalt user asked for.
     *
     * Its own colours where they map: the toolbar (#3A1A83) is the darkest
     * surface, the new-tab ground (#674EA7) is the ground here, and its text is
     * white. Its frame (#6868C2) becomes the border and, lightened, the
     * gradient's far stop.
     *
     * Two things are not replicated. The Chrome theme is mostly four background
     * *images*; a Cobalt theme is colour tokens (0007), so this is the palette
     * without the artwork. And its red would have been unreadable: the natural
     * partner to these purples reached 2.37:1 against the ground, under the
     * 3:1 a status colour needs, so danger is lightened until it passes.
     */
    val DreamyPurple = Palette(
        id = "dreamy-purple",
        name = "Dreamy Purple",
        isDark = true,
        bgPrimary = Color(0xFF674EA7),   // ntp_background
        bgSecondary = Color(0xFF3A1A83), // toolbar
        surface = Color(0xFF4A2A9B),
        border = Color(0xFF7A63B8),
        fg = Color(0xFFFFFFFF),          // tab_text, ntp_text, bookmark_text
        fgMuted = Color(0xFFD8D0F0),
        accent = Color(0xFFC0AFFF),      // the buttons tint's hue, at a legible lightness
        accentEnd = Color(0xFFA8C0FF),   // frame (#6868C2), lightened
        success = Color(0xFF6FE39B),
        warning = Color(0xFFFFD35C),
        danger = Color(0xFFFFA0AC),
    )

    val all = listOf(Cobalt, OneDark, SolarizedLight, SolarizedDark, DreamyPurple)

    val defaultDark = Cobalt
    val defaultLight = SolarizedLight
}

// --- Contrast ---------------------------------------------------------------

/** WCAG 2 contrast ratio between two opaque colors, 1..21. */
fun contrast(a: Color, b: Color): Double {
    val la = a.luminance().toDouble()
    val lb = b.luminance().toDouble()
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

/** A pairing the editor checks, and the ratio it asks for. */
data class ContrastCheck(val label: String, val ratio: Double, val required: Double) {
    val passes: Boolean get() = ratio >= required
}

/**
 * The pairings that make a theme legible. Body text needs WCAG AA's 4.5:1;
 * secondary text and interactive color, which are never the only carrier of
 * meaning, need the 3:1 WCAG gives non-text UI.
 */
fun Palette.contrastChecks(): List<ContrastCheck> = listOf(
    ContrastCheck("Text on background", contrast(fg, bgPrimary), 4.5),
    ContrastCheck("Text on toolbar", contrast(fg, bgSecondary), 4.5),
    ContrastCheck("Secondary text", contrast(fgMuted, bgPrimary), 3.0),
    ContrastCheck("Accent on background", contrast(accent, bgPrimary), 3.0),
)

/**
 * The nearest color to [color] that reaches [ratio] against [against]: moved
 * toward white or black, whichever [against] is further from. This is 0007's
 * one-tap fix, and it keeps the hue, so a fixed theme still looks like itself.
 */
fun fixContrast(color: Color, against: Color, ratio: Double): Color {
    if (contrast(color, against) >= ratio) return color
    val target = if (against.luminance() < 0.5f) Color.White else Color.Black
    var lo = 0f
    var hi = 1f
    repeat(20) {
        val mid = (lo + hi) / 2
        if (contrast(lerp(color, target, mid), against) >= ratio) hi = mid else lo = mid
    }
    return lerp(color, target, hi)
}

// --- Incognito --------------------------------------------------------------

/**
 * Incognito, derived by a fixed transform from a dark theme, never authored
 * (0007): it is a safety signal, and a user must not be able to make it look
 * like normal browsing by accident.
 *
 * Grounds sink toward black and the accent is replaced by a cyan, the same
 * move 0.1.0 made, applied to whichever theme is in use.
 */
fun Palette.incognito(): Palette = copy(
    id = "$id-incognito",
    isDark = true,
    bgPrimary = lerp(bgPrimary, Color.Black, 0.55f),
    bgSecondary = lerp(bgSecondary, Color.Black, 0.5f),
    surface = lerp(surface, Color.Black, 0.35f),
    accent = IncognitoAccent,
    accentEnd = IncognitoAccent,
).let { derived ->
    // A light theme's text would vanish on the darkened grounds.
    if (contrast(derived.fg, derived.bgPrimary) >= 4.5) derived
    else derived.copy(fg = Color(0xFFE6E6E6), fgMuted = Color(0xFFA0A4AA))
}

private val IncognitoAccent = Color(0xFF56D4E0)

package app.auriel.cobalt.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb

/**
 * A theme as text, for sharing: CSS custom properties, because that is what
 * anyone who has written a theme before will recognise.
 *
 *     :root {
 *       --name: Night Owl;
 *       --scheme: dark;
 *       --bg-primary: #011627;
 *       --accent: #82AAFF;
 *       ...
 *     }
 *
 * This is the *secondary* path (0007). Themes are made in the editor by
 * entering colors; text is how a finished one travels between people. So the
 * parser is lenient where leniency costs nothing: any order, comments, `#RGB`,
 * `#RRGGBB` or `rgb(r, g, b)`, and tokens left out are derived rather than
 * refused. It is strict only about what would otherwise be silently wrong.
 */
object ThemeText {

    fun format(p: Palette): String = buildString {
        appendLine("/* Cobalt theme */")
        appendLine(":root {")
        appendLine("  --name: ${p.name.replace(";", "")};")
        appendLine("  --scheme: ${if (p.isDark) "dark" else "light"};")
        for ((key, get) in COLORS) appendLine("  --$key: ${hex(get(p))};")
        appendLine("}")
    }

    sealed interface Result {
        data class Ok(val palette: Palette, val derived: List<String>) : Result
        data class Error(val message: String) : Result
    }

    /** @param id the id the parsed theme gets; a pasted theme is always a new custom one */
    fun parse(text: String, id: String): Result {
        val values = DECLARATION.findAll(text.replace(COMMENT, ""))
            .associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }

        if (values.isEmpty()) return Result.Error("No --token: value; lines found.")

        val colors = HashMap<String, Color>()
        for ((key, raw) in values) {
            if (key == "name" || key == "scheme") continue
            if (key !in COLORS.map { it.first }) continue // unknown tokens are other apps' business
            colors[key] = parseColor(raw) ?: return Result.Error("--$key: \"$raw\" is not a color.")
        }

        val bg = colors["bg-primary"] ?: return Result.Error("--bg-primary is required.")
        val fg = colors["fg"] ?: return Result.Error("--fg is required.")
        val accent = colors["accent"] ?: return Result.Error("--accent is required.")

        val isDark = when (values["scheme"]?.lowercase()) {
            "dark" -> true
            "light" -> false
            else -> contrast(bg, Color.Black) < contrast(bg, Color.White)
        }
        // The fallback for status colors is the preset of the same brightness:
        // 0007 lets them be overridden but never unset.
        val base = if (isDark) Presets.Cobalt else Presets.SolarizedLight
        val derived = mutableListOf<String>()
        fun pick(key: String, derive: () -> Color): Color =
            colors[key] ?: derive().also { derived += key }

        val palette = Palette(
            id = id,
            name = values["name"]?.trim('"', '\'', ' ')?.takeIf { it.isNotEmpty() } ?: "Imported theme",
            isDark = isDark,
            bgPrimary = bg,
            bgSecondary = pick("bg-secondary") { lerp(bg, fg, 0.04f) },
            surface = pick("surface") { lerp(bg, fg, 0.09f) },
            border = pick("border") { lerp(bg, fg, 0.18f) },
            fg = fg,
            fgMuted = pick("fg-muted") { lerp(fg, bg, 0.35f) },
            accent = accent,
            accentEnd = pick("accent-end") { accent },
            success = pick("success") { base.success },
            warning = pick("warning") { base.warning },
            danger = pick("danger") { base.danger },
        )
        return Result.Ok(palette, derived)
    }

    /** `#RGB`, `#RRGGBB`, or `rgb(r, g, b)`; null for anything else. */
    fun parseColor(raw: String): Color? {
        val s = raw.trim().lowercase()
        HEX.matchEntire(s)?.let { m ->
            val digits = m.groupValues[1]
            val full = if (digits.length == 3) digits.map { "$it$it" }.joinToString("") else digits
            return Color(0xFF000000 or full.toLong(16))
        }
        RGB.matchEntire(s)?.let { m ->
            val (r, g, b) = m.destructured
            val parts = listOf(r, g, b).map { it.toInt() }
            if (parts.any { it !in 0..255 }) return null
            return Color(parts[0], parts[1], parts[2])
        }
        return null
    }

    fun hex(c: Color): String = "#%06X".format(c.toArgb() and 0xFFFFFF)

    /** Token name in the text format, and where it lives in a [Palette]. */
    val COLORS: List<Pair<String, (Palette) -> Color>> = listOf(
        "bg-primary" to Palette::bgPrimary,
        "bg-secondary" to Palette::bgSecondary,
        "surface" to Palette::surface,
        "border" to Palette::border,
        "fg" to Palette::fg,
        "fg-muted" to Palette::fgMuted,
        "accent" to Palette::accent,
        "accent-end" to Palette::accentEnd,
        "success" to Palette::success,
        "warning" to Palette::warning,
        "danger" to Palette::danger,
    )

    private val DECLARATION = Regex("""--([a-zA-Z-]+)\s*:\s*([^;}\n]+)""")
    private val COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    private val HEX = Regex("""#([0-9a-f]{6}|[0-9a-f]{3})""")
    private val RGB = Regex("""rgb\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*\)""")
}

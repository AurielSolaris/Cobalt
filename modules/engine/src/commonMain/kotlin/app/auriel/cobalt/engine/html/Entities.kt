package app.auriel.cobalt.engine.html

/**
 * Character reference decoding.
 *
 * A working subset, not the full HTML named-character table — that table has
 * over two thousand entries and Blink already ships it. These are the references
 * that actually appear in prose, plus numeric forms, which cover the rest.
 *
 * Deleted in Stage 6.
 */
internal object Entities {

    private val NAMED: Map<String, String> = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "copy" to "©", "reg" to "®", "trade" to "™",
        "hellip" to "…", "mdash" to "—", "ndash" to "–",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
        "laquo" to "«", "raquo" to "»", "bull" to "•", "middot" to "·",
        "deg" to "°", "plusmn" to "±", "times" to "×", "divide" to "÷",
        "frac12" to "½", "frac14" to "¼", "sup2" to "²", "sup3" to "³",
        "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢",
        "sect" to "§", "para" to "¶", "dagger" to "†", "permil" to "‰",
        "larr" to "←", "rarr" to "→", "uarr" to "↑", "darr" to "↓",
        "harr" to "↔", "ne" to "≠", "le" to "≤", "ge" to "≥",
        "minus" to "−", "prime" to "′", "Prime" to "″", "infin" to "∞",
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
        "pi" to "π", "sigma" to "σ", "omega" to "ω", "mu" to "μ",
        "shy" to "­", "ensp" to " ", "emsp" to " ", "thinsp" to " ",
        "zwnj" to "‌", "zwj" to "‍",
    )

    /**
     * Replace character references in [input].
     *
     * Anything unrecognized is left exactly as written. A stray `&` in prose is
     * far more common than a typo'd entity, and mangling it would be the worse
     * failure.
     */
    fun decode(input: String): String {
        if (!input.contains('&')) return input

        val out = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }

            val semicolon = input.indexOf(';', i + 1)
            // Entities are short; a distant semicolon means this ampersand is just
            // an ampersand.
            if (semicolon < 0 || semicolon - i > 32) {
                out.append(c)
                i++
                continue
            }

            val body = input.substring(i + 1, semicolon)
            val decoded = when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    body.substring(2).toIntOrNull(16)?.let(::fromCodePoint)
                body.startsWith("#") ->
                    body.substring(1).toIntOrNull()?.let(::fromCodePoint)
                else -> NAMED[body]
            }

            if (decoded != null) {
                out.append(decoded)
                i = semicolon + 1
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun fromCodePoint(codePoint: Int): String? = when {
        codePoint <= 0 || codePoint > 0x10FFFF -> null
        // Lone surrogates are not characters and would corrupt the string.
        codePoint in 0xD800..0xDFFF -> null
        codePoint <= 0xFFFF -> codePoint.toChar().toString()
        else -> {
            val v = codePoint - 0x10000
            charArrayOf(
                (0xD800 + (v shr 10)).toChar(),
                (0xDC00 + (v and 0x3FF)).toChar(),
            ).concatToString()
        }
    }
}

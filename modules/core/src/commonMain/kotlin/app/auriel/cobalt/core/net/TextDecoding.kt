package app.auriel.cobalt.core.net

/**
 * Chooses the character encoding for a fetched document.
 *
 * The precedence is deliberate and matches what browsers do: a byte order mark
 * is authoritative because it is in the bytes themselves, a declared charset is
 * trusted next, and UTF-8 is the fallback because guessing anything else on the
 * modern web is worse than being wrong in a predictable direction.
 */
object CharsetDetection {

    const val UTF_8 = "UTF-8"
    const val UTF_16BE = "UTF-16BE"
    const val UTF_16LE = "UTF-16LE"

    /** The charset implied by a byte order mark, or null if there is none. */
    fun fromByteOrderMark(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> UTF_8
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> UTF_16BE
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> UTF_16LE
        else -> null
    }

    /** How many leading bytes are byte order mark and must not reach the parser. */
    fun byteOrderMarkLength(bytes: ByteArray): Int = when (fromByteOrderMark(bytes)) {
        UTF_8 -> 3
        UTF_16BE, UTF_16LE -> 2
        else -> 0
    }

    /** Pick a charset given what the headers said and what the bytes start with. */
    fun select(declared: String?, bytes: ByteArray): String =
        fromByteOrderMark(bytes)
            ?: declared?.trim()?.ifEmpty { null }
            ?: UTF_8
}

/**
 * Turns bytes into text. Implemented per platform because the set of supported
 * encodings is a platform capability; the selection logic above is shared and
 * tested once.
 */
interface TextDecoder {

    /**
     * Decode [bytes] as [charset]. Implementations must not throw: an unknown or
     * unsupported encoding falls back to UTF-8, and malformed input is replaced
     * rather than rejected. A page that is 1% mojibake still beats an error screen.
     */
    fun decode(bytes: ByteArray, charset: String): String
}

/**
 * The shared fallback decoder. Handles UTF-8 only — every other encoding is
 * decoded as UTF-8, which is wrong but survivable. Platforms that can do better
 * provide their own [TextDecoder].
 */
object Utf8TextDecoder : TextDecoder {
    override fun decode(bytes: ByteArray, charset: String): String {
        val start = CharsetDetection.byteOrderMarkLength(bytes)
        return if (start == 0) {
            bytes.decodeToString()
        } else {
            bytes.decodeToString(startIndex = start, endIndex = bytes.size)
        }
    }
}

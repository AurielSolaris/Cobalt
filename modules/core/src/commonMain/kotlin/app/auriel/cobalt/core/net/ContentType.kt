package app.auriel.cobalt.core.net

/**
 * A parsed `Content-Type` header: the MIME type, and the charset if the server
 * bothered to declare one. Parameters other than charset are discarded.
 */
data class ContentType(
    val mimeType: String,
    val charset: String?,
) {

    val isHtml: Boolean get() = mimeType == "text/html" || mimeType == "application/xhtml+xml"

    val isText: Boolean get() = mimeType.startsWith("text/") || isHtml

    override fun toString(): String =
        if (charset == null) mimeType else "$mimeType; charset=$charset"

    companion object {
        val HTML = ContentType("text/html", null)

        /** Parse a header value. Returns null for null, blank, or unparseable input. */
        fun parse(header: String?): ContentType? {
            val value = header?.trim().orEmpty()
            if (value.isEmpty()) return null

            val parts = value.split(';')
            val mime = parts[0].trim().lowercase()
            if (mime.isEmpty() || !mime.contains('/')) return null

            var charset: String? = null
            for (i in 1 until parts.size) {
                val parameter = parts[i].trim()
                val eq = parameter.indexOf('=')
                if (eq <= 0) continue
                if (!parameter.substring(0, eq).trim().equals("charset", ignoreCase = true)) continue
                charset = parameter.substring(eq + 1).trim().trim('"', '\'').ifEmpty { null }
            }

            return ContentType(mime, charset)
        }
    }
}

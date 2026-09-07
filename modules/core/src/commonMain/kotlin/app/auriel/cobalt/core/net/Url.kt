package app.auriel.cobalt.core.net

/**
 * A parsed http(s) URL.
 *
 * Deliberately small: Cobalt only navigates http and https in 0.1.0, and the
 * shell needs exactly enough structure to normalize what a user types and to
 * resolve the links on a page against it. Anything richer belongs to Chromium's
 * GURL once the engine lands.
 */
data class Url(
    val scheme: String,
    val host: String,
    val port: Int?,
    val path: String,
    val query: String?,
    val fragment: String?,
) {

    /** The port actually used, filling in the scheme default. */
    val effectivePort: Int get() = port ?: defaultPortFor(scheme)

    val isSecure: Boolean get() = scheme == "https"

    /** Canonical serialization: lowercased, default port dropped, path never empty. */
    override fun toString(): String = buildString {
        append(scheme).append("://").append(host)
        if (port != null && port != defaultPortFor(scheme)) append(':').append(port)
        append(path.ifEmpty { "/" })
        if (query != null) append('?').append(query)
        if (fragment != null) append('#').append(fragment)
    }

    /** A short form for display in the address bar. */
    fun displayForm(): String = toString()

    companion object {
        private val HOST = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*\\.?$")

        internal fun defaultPortFor(scheme: String): Int = if (scheme == "https") 443 else 80

        /**
         * Parse an absolute http(s) URL. Returns null for anything else — other
         * schemes, malformed hosts, garbage. Never throws.
         */
        fun parse(raw: String): Url? {
            val input = raw.trim()
            if (input.isEmpty()) return null
            if (input.any { it == ' ' || it.code < 0x20 }) return null

            val schemeEnd = input.indexOf("://")
            if (schemeEnd <= 0) return null
            val scheme = input.substring(0, schemeEnd).lowercase()
            if (scheme != "http" && scheme != "https") return null

            var rest = input.substring(schemeEnd + 3)

            var fragment: String? = null
            val hash = rest.indexOf('#')
            if (hash >= 0) {
                fragment = rest.substring(hash + 1)
                rest = rest.substring(0, hash)
            }

            var query: String? = null
            val question = rest.indexOf('?')
            if (question >= 0) {
                query = rest.substring(question + 1)
                rest = rest.substring(0, question)
            }

            val slash = rest.indexOf('/')
            var authority = if (slash >= 0) rest.substring(0, slash) else rest
            val path = if (slash >= 0) rest.substring(slash) else "/"

            // Credentials in the authority are a phishing vector and we have no use
            // for them, so they are dropped rather than carried.
            val at = authority.lastIndexOf('@')
            if (at >= 0) authority = authority.substring(at + 1)
            if (authority.isEmpty()) return null

            var host = authority
            var port: Int? = null
            val colon = authority.lastIndexOf(':')
            if (colon >= 0 && authority.indexOf(']') < colon) {
                host = authority.substring(0, colon)
                val portText = authority.substring(colon + 1)
                if (portText.isNotEmpty()) {
                    port = portText.toIntOrNull() ?: return null
                    if (port !in 1..65535) return null
                }
            }

            host = host.lowercase()
            if (host.isEmpty()) return null
            if (!host.startsWith("[") && !HOST.matches(host)) return null

            return Url(
                scheme = scheme,
                host = host,
                port = port,
                path = normalizePath(path),
                query = query,
                fragment = fragment,
            )
        }

        /**
         * Turn whatever the user typed into a URL.
         *
         * A bare host gets `https://` — never `http://`. Cobalt does not silently
         * downgrade a navigation to cleartext; a user who wants http must say so.
         * Returns null when the input cannot be read as a URL at all.
         */
        fun normalize(typed: String): Url? {
            val input = typed.trim()
            if (input.isEmpty()) return null

            if (input.contains("://")) return parse(input)

            // A scheme we do not support (mailto:, javascript:, file:, ...) is
            // rejected rather than guessed at.
            val colon = input.indexOf(':')
            if (colon > 0) {
                val maybeScheme = input.substring(0, colon)
                val looksLikeScheme = maybeScheme.isNotEmpty() &&
                    maybeScheme[0].isLetter() &&
                    maybeScheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }
                val afterColon = input.substring(colon + 1)
                // "localhost:8080" is a host and a port, not a scheme.
                val isHostPort = afterColon.takeWhile { it != '/' }.let {
                    it.isNotEmpty() && it.all { c -> c.isDigit() }
                }
                if (looksLikeScheme && !isHostPort) return null
            }

            return parse("https://$input")
        }

        private fun normalizePath(path: String): String {
            if (path.isEmpty()) return "/"
            val absolute = path.startsWith("/")
            val out = ArrayList<String>()
            for (segment in path.split('/')) {
                when (segment) {
                    "", "." -> Unit
                    ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                    else -> out.add(segment)
                }
            }
            val trailing = path.endsWith("/") || path.endsWith("/.") || path.endsWith("/..")
            val body = out.joinToString("/")
            return buildString {
                if (absolute) append('/')
                append(body)
                if (trailing && body.isNotEmpty()) append('/')
            }.ifEmpty { "/" }
        }
    }
}

/**
 * Resolve [href] — as written in a page's markup — against [base].
 *
 * Handles the forms that actually occur in links: absolute, protocol-relative,
 * root-relative, query-only, fragment-only, and plain relative paths. Returns
 * null when the result would not be an http(s) URL, which is how `mailto:` and
 * `javascript:` links are declined.
 */
fun resolveUrl(base: Url, href: String): Url? {
    val ref = href.trim()
    if (ref.isEmpty()) return base

    if (ref.startsWith("//")) return Url.parse("${base.scheme}:$ref")
    if (ref.contains("://")) return Url.parse(ref)

    // A scheme with no authority — mailto:, tel:, javascript: — is not navigable.
    val colon = ref.indexOf(':')
    val slash = ref.indexOf('/')
    if (colon > 0 && (slash < 0 || colon < slash) && ref.take(colon).all { it.isLetterOrDigit() || it in "+-." }) {
        return null
    }

    if (ref.startsWith("#")) return base.copy(fragment = ref.substring(1))

    if (ref.startsWith("?")) {
        val q = ref.substring(1).substringBefore('#')
        val f = ref.substringAfter('#', "").ifEmpty { null }
        return base.copy(query = q, fragment = f)
    }

    val withoutFragment = ref.substringBefore('#')
    val fragment = ref.substringAfter('#', "").ifEmpty { null }
    val pathPart = withoutFragment.substringBefore('?')
    val queryPart = withoutFragment.substringAfter('?', "").ifEmpty { null }

    val merged = if (pathPart.startsWith("/")) {
        pathPart
    } else {
        val baseDir = base.path.substringBeforeLast('/', "") + "/"
        baseDir + pathPart
    }

    return Url.parse(
        buildString {
            append(base.scheme).append("://").append(base.host)
            if (base.port != null) append(':').append(base.port)
            append(if (merged.startsWith("/")) merged else "/$merged")
            if (queryPart != null) append('?').append(queryPart)
            if (fragment != null) append('#').append(fragment)
        }
    )
}

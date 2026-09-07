package app.auriel.cobalt.core.net

/**
 * A document fetched from the network.
 *
 * [finalUrl] is the URL after redirects, not the one that was requested — links
 * inside [text] resolve against where the bytes actually came from.
 */
data class Page(
    val finalUrl: Url,
    val text: String,
    val contentType: ContentType,
    val statusCode: Int,
)

/**
 * Why a fetch did not produce a page.
 *
 * These are the categories a user can act on, not a mirror of the platform's
 * exception hierarchy: the shell renders [headline] and [detail] directly, so a
 * new case here means a new thing the user is told, deliberately.
 */
sealed interface FetchError {

    /** One short line, suitable as an error screen's title. */
    val headline: String

    /** The specifics, suitable as body text. May be empty. */
    val detail: String

    /** The typed address could not be read as an http(s) URL. */
    data class InvalidUrl(val typed: String) : FetchError {
        override val headline = "That is not a web address"
        override val detail = "Cobalt could not read \"$typed\" as an http or https URL."
    }

    /** DNS failed — the host does not exist, or nothing answered for it. */
    data class HostNotFound(val host: String) : FetchError {
        override val headline = "Site not found"
        override val detail = "No server was found at $host. Check the address for typos."
    }

    /** The connection failed or dropped. */
    data class Unreachable(override val detail: String) : FetchError {
        override val headline = "Could not connect"
    }

    /** The TLS handshake failed — bad certificate, protocol mismatch, or a proxy. */
    data class SecureConnectionFailed(override val detail: String) : FetchError {
        override val headline = "Secure connection failed"
    }

    /** The server did not answer in time. */
    data class Timeout(val seconds: Int) : FetchError {
        override val headline = "The site took too long to respond"
        override val detail = "No response after $seconds seconds."
    }

    /** The server answered, but not with a document. */
    data class HttpStatus(val code: Int, val reason: String) : FetchError {
        override val headline = "Server returned $code"
        override val detail = reason
    }

    /** The response was not something 0.1.0 can display. */
    data class UnsupportedContent(val contentType: String) : FetchError {
        override val headline = "Cobalt cannot display this yet"
        override val detail = "The server sent $contentType. This build renders HTML and plain text only."
    }

    /** Anything the layers below did not classify. */
    data class Unknown(override val detail: String) : FetchError {
        override val headline = "Something went wrong"
    }
}

/** The outcome of a fetch: a page, or a reason there is not one. */
sealed interface FetchResult {
    data class Success(val page: Page) : FetchResult
    data class Failure(val error: FetchError) : FetchResult
}

/**
 * Fetches documents over http(s).
 *
 * An interface rather than an `expect` declaration on purpose: the Android
 * implementation is the only real one, and the shared code stays free of
 * platform APIs without forcing empty actuals onto every other target. It is
 * also what lets the shell be tested against a canned loader.
 *
 * Deleted in Stage 6, when Chromium's network stack takes over.
 */
interface PageLoader {

    /** Fetch [url], following redirects. Never throws; failures come back typed. */
    suspend fun load(url: Url): FetchResult
}

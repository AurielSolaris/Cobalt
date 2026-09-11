package app.auriel.cobalt.core.net

import kotlin.concurrent.Volatile

/**
 * The user agent Cobalt's own Kotlin code sends: the document engine's fetches,
 * and anything else the shell requests itself.
 *
 * **It is Chromium's, exactly.** Cobalt is Chromium underneath, and a site must
 * see one browser, not two: before this, Chromium sent its reduced UA while
 * OkHttp appended a `Cobalt/0.1.0` token, so the same person looked like
 * different browsers depending on which code path made the request, which is
 * itself a fingerprint. Now the Chromium engine sets [value] from
 * `ContentUtils.getBrowserUserAgent()` as soon as it starts, and nothing here
 * keeps its own opinion.
 *
 * Until then (and in builds without Chromium) the default is the same string
 * Chromium 140 produces: its reduced UA, which freezes the platform as
 * `Android 10; K` and the version as `140.0.0.0` to cut fingerprinting surface.
 * Decision 0016: the version stays truthful to the tree Cobalt is built from.
 */
object UserAgent {

    /** The Chromium milestone Cobalt is built from, as the reduced UA reports it. */
    const val CHROMIUM_VERSION = "140.0.0.0"

    /** What Chromium 140's reduced user agent is on Android. */
    const val DEFAULT: String =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$CHROMIUM_VERSION Mobile Safari/537.36"

    /** The `User-Agent` header value; the engine's own string once it has started. */
    @Volatile
    var value: String = DEFAULT
        private set

    /** Called by the Chromium engine with the string it actually sends. */
    fun syncFrom(engineUserAgent: String) {
        if (engineUserAgent.isNotBlank()) value = engineUserAgent
    }

    /**
     * The `Accept` header for a navigation. Mirrors what Chrome sends, for the
     * same reason as the user agent: content negotiation should land on the same
     * representation Chrome would get, because that is what will be rendered.
     */
    const val ACCEPT: String =
        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
}

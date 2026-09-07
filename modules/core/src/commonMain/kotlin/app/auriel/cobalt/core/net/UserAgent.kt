package app.auriel.cobalt.core.net

/**
 * The user agent Cobalt sends.
 *
 * Cobalt is a Chromium browser and says so. This is not a disguise: Blink
 * renders the pages and V8 runs the scripts from Stage 6 onward, so a server
 * that branches on the Chrome token gets exactly the engine it is branching for.
 * A novel token would only earn Cobalt a bot challenge or a 2005 fallback page
 * for no benefit to anyone.
 *
 * The `Cobalt/<version>` product is appended rather than substituted, in the
 * same way Edge and Brave identify themselves. Sniffers that look for Chrome
 * still find it; anyone who wants to know which browser this actually is can
 * read to the end of the string.
 *
 * ### Keeping it honest
 *
 * [CHROMIUM_VERSION] must track the Chromium the app is actually built from.
 * Until Stage 6 there is no Blink behind it, which is the one period where this
 * string promises slightly more than it delivers — 0.1.0 fetches with OkHttp and
 * renders a structural subset through Compose. It is stated here rather than
 * quietly glossed over, and it stops being true the moment the real engine lands.
 *
 * Chromium's own reduced user agent freezes the platform as `Android 10; K` to
 * cut fingerprinting surface; Cobalt matches that rather than leaking the real
 * device and OS build.
 */
object UserAgent {

    /**
     * The Chromium milestone Cobalt reports. Updated with every rebase in
     * Stage 5 — a user agent that drifts behind the engine it describes is worse
     * than no user agent at all.
     */
    const val CHROMIUM_VERSION = "140.0.0.0"

    /** Cobalt's own version, appended as a distinct product token. */
    const val COBALT_VERSION = "0.1.0"

    /** The full `User-Agent` header value. */
    const val VALUE: String =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$CHROMIUM_VERSION Mobile Safari/537.36 Cobalt/$COBALT_VERSION"

    /**
     * The `Accept` header for a navigation. Mirrors what Chrome sends, for the
     * same reason as the user agent: content negotiation should land on the same
     * representation Chrome would get, because that is what will be rendered.
     */
    const val ACCEPT: String =
        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
}

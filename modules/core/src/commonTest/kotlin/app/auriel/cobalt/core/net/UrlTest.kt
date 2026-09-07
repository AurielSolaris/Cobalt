package app.auriel.cobalt.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UrlTest {

    @Test
    fun bareHostBecomesHttps() {
        assertEquals("https://example.com/", Url.normalize("example.com").toString())
        assertEquals("https://example.com/path", Url.normalize("example.com/path").toString())
        assertEquals("https://sub.example.co.uk/", Url.normalize("sub.example.co.uk").toString())
    }

    @Test
    fun neverSilentlyDowngradesToCleartext() {
        // A typed host gets https, never http. An explicit http:// is honoured.
        assertEquals("https", Url.normalize("example.com")!!.scheme)
        assertEquals("http", Url.normalize("http://example.com")!!.scheme)
    }

    @Test
    fun surroundingWhitespaceIsIgnored() {
        assertEquals("https://example.com/", Url.normalize("  example.com \n").toString())
    }

    @Test
    fun schemeAndHostAreLowercased() {
        assertEquals("https://example.com/Path", Url.normalize("HTTPS://EXAMPLE.COM/Path").toString())
    }

    @Test
    fun defaultPortsAreDroppedAndOthersKept() {
        assertEquals("https://example.com/", Url.normalize("https://example.com:443").toString())
        assertEquals("http://example.com/", Url.normalize("http://example.com:80").toString())
        assertEquals("https://example.com:8443/", Url.normalize("https://example.com:8443").toString())
    }

    @Test
    fun hostAndPortIsNotMistakenForAScheme() {
        val url = Url.normalize("localhost:8080/admin")
        assertEquals("localhost", url?.host)
        assertEquals(8080, url?.port)
        assertEquals("/admin", url?.path)
    }

    @Test
    fun unsupportedSchemesAreRejected() {
        assertNull(Url.normalize("mailto:someone@example.com"))
        assertNull(Url.normalize("javascript:alert(1)"))
        assertNull(Url.normalize("file:///etc/passwd"))
        assertNull(Url.normalize("ftp://example.com"))
    }

    @Test
    fun garbageIsRejectedRatherThanGuessedAt() {
        assertNull(Url.normalize(""))
        assertNull(Url.normalize("   "))
        assertNull(Url.normalize("not a url at all"))
        assertNull(Url.normalize("https://"))
        assertNull(Url.normalize("https://example.com:99999"))
        assertNull(Url.normalize("https://exa mple.com"))
    }

    @Test
    fun credentialsInTheAuthorityAreDropped() {
        // https://example.com@evil.example is a phishing shape; the host is what
        // matters and the credentials have no use here.
        val url = Url.parse("https://user:secret@example.com/x")
        assertEquals("example.com", url?.host)
        assertEquals("https://example.com/x", url.toString())
    }

    @Test
    fun queryAndFragmentSurvive() {
        val url = Url.normalize("example.com/search?q=cobalt&n=1#results")
        assertEquals("/search", url?.path)
        assertEquals("q=cobalt&n=1", url?.query)
        assertEquals("results", url?.fragment)
        assertEquals("https://example.com/search?q=cobalt&n=1#results", url.toString())
    }

    @Test
    fun dotSegmentsAreResolvedAway() {
        assertEquals("https://example.com/b", Url.normalize("example.com/a/../b").toString())
        assertEquals("https://example.com/a/b", Url.normalize("example.com/./a/b").toString())
        // Escaping above the root is clamped, not honoured.
        assertEquals("https://example.com/x", Url.normalize("example.com/../../x").toString())
    }
}

class ResolveUrlTest {

    private val base = Url.parse("https://example.com/docs/guide/intro.html")!!

    @Test
    fun absoluteReferenceWins() {
        assertEquals(
            "https://other.example/x",
            resolveUrl(base, "https://other.example/x").toString(),
        )
    }

    @Test
    fun protocolRelativeInheritsTheScheme() {
        assertEquals("https://cdn.example/a.png", resolveUrl(base, "//cdn.example/a.png").toString())
    }

    @Test
    fun rootRelativeReplacesThePath() {
        assertEquals("https://example.com/about", resolveUrl(base, "/about").toString())
    }

    @Test
    fun relativeResolvesAgainstTheContainingDirectory() {
        assertEquals(
            "https://example.com/docs/guide/next.html",
            resolveUrl(base, "next.html").toString(),
        )
        assertEquals(
            "https://example.com/docs/api.html",
            resolveUrl(base, "../api.html").toString(),
        )
    }

    @Test
    fun fragmentOnlyKeepsTheCurrentPage() {
        assertEquals(
            "https://example.com/docs/guide/intro.html#section",
            resolveUrl(base, "#section").toString(),
        )
    }

    @Test
    fun queryOnlyReplacesTheQuery() {
        assertEquals(
            "https://example.com/docs/guide/intro.html?page=2",
            resolveUrl(base, "?page=2").toString(),
        )
    }

    @Test
    fun nonNavigableSchemesAreDeclined() {
        assertNull(resolveUrl(base, "mailto:hi@example.com"))
        assertNull(resolveUrl(base, "javascript:void(0)"))
        assertNull(resolveUrl(base, "tel:+15550100"))
    }

    @Test
    fun emptyReferenceIsTheCurrentPage() {
        assertEquals(base.toString(), resolveUrl(base, "")!!.toString())
    }
}

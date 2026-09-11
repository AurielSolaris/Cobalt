package app.auriel.cobalt.browser.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchTest {

    @Test
    fun wordsAreSearches() {
        for (s in listOf("cats", "cobalt browser", "how do i sort a list", "?example.com", "v2.", ".net")) {
            assertTrue(looksLikeSearch(s), s)
        }
    }

    @Test
    fun addressesAreNot() {
        for (s in listOf("example.com", "en.wikipedia.org/wiki/Cobalt", "https://x", "chrome://extensions",
                         "localhost", "localhost:8080/app", "192.168.1.1", "[::1]:80", "a.b?q=1 2".substringBefore(' '))) {
            assertFalse(looksLikeSearch(s), s)
        }
    }

    @Test
    fun queriesAreEncoded() {
        assertEquals("https://duckduckgo.com/?q=cobalt+%26+kiwi", SearchEngine.DuckDuckGo.urlFor("cobalt & kiwi"))
        assertEquals("https://www.google.com/search?q=example.com", SearchEngine.Google.urlFor(searchQuery("?example.com")))
    }

    @Test
    fun duckDuckGoIsTheDefault() {
        assertEquals(SearchEngine.DuckDuckGo, SearchEngine.Default)
        assertEquals(SearchEngine.DuckDuckGo, SearchEngine.byId("gone"))
    }
}

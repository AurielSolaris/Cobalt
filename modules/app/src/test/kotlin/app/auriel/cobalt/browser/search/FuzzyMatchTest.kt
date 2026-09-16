package app.auriel.cobalt.browser.search

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FuzzyMatchTest {

    private fun fuzzy(q: String) = FuzzyMatch.compile(q, regex = false)
    private fun regex(q: String) = FuzzyMatch.compile(q, regex = true)

    @Test fun subsequenceInOrderMatches() {
        assertNotNull(fuzzy("wasm").score("WebAssembly - Wikipedia"))
        assertNotNull(fuzzy("wbsmbly").score("WebAssembly"))
    }

    @Test fun outOfOrderDoesNotMatch() {
        assertNull(fuzzy("msaw").score("WebAssembly"))
    }

    @Test fun everyWordMustMatch() {
        assertNotNull(fuzzy("wasm wik").score("WebAssembly - Wikipedia"))
        assertNull(fuzzy("wasm mdn").score("WebAssembly - Wikipedia"))
    }

    @Test fun caseIsIgnored() {
        assertNotNull(fuzzy("WEBASSEMBLY").score("webassembly"))
    }

    @Test fun substringRanksAboveScatteredLetters() {
        val q = fuzzy("cobalt")
        val direct = q.score("Cobalt - Wikipedia")!!
        val scattered = q.score("Colour balance tutorial")!!
        assertTrue(direct > scattered, "$direct vs $scattered")
    }

    @Test fun wordStartsRankAboveMidWord() {
        val q = fuzzy("ab")
        assertTrue(q.score("Alpha Beta")!! > q.score("xaxb")!!)
    }

    @Test fun emptyQueryMatchesEverything() {
        assertNotNull(fuzzy("  ").score("anything"))
    }

    @Test fun regexMatchesAnywhereIgnoringCase() {
        assertNotNull(regex("wiki(pedia)?").score("en.WIKIPEDIA.org"))
        assertNull(regex("^https://example").score("http://example.com"))
    }

    @Test fun invalidRegexIsReportedNotThrown() {
        val q = regex("(unclosed")
        assertIs<FuzzyMatch.Query.Invalid>(q)
        assertNull(q.score("(unclosed"))
    }

    @Test fun lengthChangingLowercaseDoesNotCrash() {
        // "İ" lowercases to two chars, shifting every later index.
        assertNotNull(fuzzy("stanbul").score("İstanbul İstanbul"))
    }
}

package app.auriel.cobalt.browser.search

/**
 * Searching a list the way a person half-remembers it.
 *
 * **Fuzzy (the default).** Every word typed must appear in the text as a
 * subsequence, in order, ignoring case: "wasm wik" finds "WebAssembly -
 * Wikipedia". Letters that start a word, and letters that follow the previous
 * match directly, score higher, so the closest matches come first; a letter
 * far from the last one costs a little.
 *
 * **Regular expression.** The pattern as typed, ignoring case, anywhere in
 * the text. An invalid pattern is reported, not thrown.
 */
object FuzzyMatch {

    /** A matcher, or why there cannot be one ([Invalid]). */
    interface Query {
        /** Null if [text] does not match; higher is better. */
        fun score(text: String): Int?

        data class Invalid(val reason: String) : Query {
            override fun score(text: String): Int? = null
        }
    }

    fun compile(input: String, regex: Boolean): Query {
        if (regex) {
            return try {
                val pattern = Regex(input, RegexOption.IGNORE_CASE)
                object : Query {
                    override fun score(text: String): Int? {
                        val match = pattern.find(text) ?: return null
                        // Earlier and shorter matches first.
                        return 1_000 - match.range.first - match.value.length / 4
                    }
                }
            } catch (e: IllegalArgumentException) {
                // PatternSyntaxException's message spans lines, with a caret
                // under the error; the first line is the reason.
                Query.Invalid(e.message?.lineSequence()?.firstOrNull() ?: "invalid pattern")
            }
        }
        val terms = input.lowercase().split(' ').filter { it.isNotEmpty() }
        return object : Query {
            override fun score(text: String): Int? {
                if (terms.isEmpty()) return 0
                val haystack = text.lowercase()
                var total = 0
                for (term in terms) total += termScore(term, haystack, text) ?: return null
                return total
            }
        }
    }

    /**
     * The score of one term as a subsequence of [text] (lowercased), or null.
     * [original] keeps its case, for finding word starts in camelCase.
     */
    private fun termScore(term: String, text: String, original: String): Int? {
        // A plain substring is the strongest evidence; take it first.
        val at = text.indexOf(term)
        if (at >= 0) return 100 + term.length * 10 - minOf(at, 50) + if (isWordStart(original, at)) 30 else 0
        var score = 0
        var from = 0
        var last = -2
        for (ch in term) {
            val i = text.indexOf(ch, from)
            if (i < 0) return null
            score += when {
                i == last + 1 -> 8 // continues the previous match
                isWordStart(original, i) -> 6
                else -> 1
            }
            score -= minOf(i - from, 10) / 3 // a long jump costs a little
            last = i
            from = i + 1
        }
        return score
    }

    // Indices come from the lowercased text, which for a few characters ("İ")
    // is longer than the original; past the end is simply not a word start.
    private fun isWordStart(text: String, i: Int): Boolean = when {
        i == 0 -> true
        i >= text.length -> false
        else -> !text[i - 1].isLetterOrDigit() || (text[i].isUpperCase() && text[i - 1].isLowerCase())
    }
}

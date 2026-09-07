package app.auriel.cobalt.engine.html

/**
 * Builds a document tree from HTML source.
 *
 * The contract that matters: **this never throws**. Every input produces a
 * document, including empty input, binary noise, and markup that closes tags it
 * never opened. A browser that shows an error screen because a page nested a
 * `<b>` badly is not a browser.
 *
 * The tree it produces is deliberately thin — elements and text, no comments, no
 * script or style content. See [Node].
 *
 * Deleted in Stage 6, when Blink's parser takes over.
 */
object HtmlParser {

    /**
     * Elements that close an open element of the same or related kind when a new
     * one starts. Real pages leave `<p>` and `<li>` unclosed constantly; without
     * this the whole document ends up nested inside the first list item.
     */
    private val IMPLIED_END: Map<String, Set<String>> = mapOf(
        "p" to setOf("p"),
        "li" to setOf("li"),
        "dt" to setOf("dt", "dd"),
        "dd" to setOf("dt", "dd"),
        "tr" to setOf("tr", "td", "th"),
        "td" to setOf("td", "th"),
        "th" to setOf("td", "th"),
        "option" to setOf("option"),
        "thead" to setOf("tbody", "tfoot"),
        "tbody" to setOf("thead", "tbody", "tfoot"),
        "tfoot" to setOf("thead", "tbody"),
    )

    /** Block elements that a `<p>` cannot survive; starting one closes it. */
    private val CLOSES_PARAGRAPH = setOf(
        "address", "article", "aside", "blockquote", "div", "dl", "fieldset",
        "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr",
        "main", "nav", "ol", "pre", "section", "table", "ul",
    )

    fun parse(html: String): Document {
        val root = Element("html")
        val stack = ArrayList<Element>()
        stack.add(root)

        var title: String? = null
        var titleDepth = -1

        for (token in HtmlTokenizer(html).tokenize()) {
            when (token) {
                is Token.Text -> {
                    val current = stack.last()
                    if (titleDepth >= 0) {
                        // Inside <title>: capture, do not render.
                        title = (title.orEmpty() + token.text)
                    } else {
                        current.append(TextNode(token.text))
                    }
                }

                is Token.StartTag -> {
                    if (token.name == "title") {
                        titleDepth = stack.size
                        continue
                    }
                    if (token.name in RAW_TEXT_ELEMENTS) continue

                    closeImpliedBy(token.name, stack)

                    val element = Element(token.name, token.attributes)
                    stack.last().append(element)
                    if (!token.selfClosing && token.name !in VOID_ELEMENTS) stack.add(element)
                }

                is Token.EndTag -> {
                    if (token.name == "title") {
                        titleDepth = -1
                        continue
                    }
                    if (token.name in VOID_ELEMENTS || token.name in RAW_TEXT_ELEMENTS) continue

                    // Pop to the matching open element. A close tag for something
                    // that was never opened is ignored rather than unwinding the
                    // tree — that is the difference between a stray `</div>`
                    // costing nothing and it truncating the page.
                    val index = stack.indexOfLast { it.tagName == token.name }
                    if (index > 0) {
                        while (stack.size > index) stack.removeAt(stack.size - 1)
                    }
                }
            }
        }

        return Document(root, title?.trim()?.ifEmpty { null })
    }

    private fun closeImpliedBy(tagName: String, stack: MutableList<Element>) {
        val closes = IMPLIED_END[tagName].orEmpty()
        val closesParagraph = tagName in CLOSES_PARAGRAPH

        if (closes.isEmpty() && !closesParagraph) return

        // Only unwind as far as the nearest container. A <li> in a nested list
        // must not close the outer list's item.
        while (stack.size > 1) {
            val open = stack.last().tagName
            val shouldClose = open in closes || (closesParagraph && open == "p")
            if (!shouldClose) break
            stack.removeAt(stack.size - 1)
        }
    }
}

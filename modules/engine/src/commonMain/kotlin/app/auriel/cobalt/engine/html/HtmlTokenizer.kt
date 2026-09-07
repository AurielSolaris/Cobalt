package app.auriel.cobalt.engine.html

/** One lexical unit of an HTML document. */
internal sealed interface Token {

    data class StartTag(
        val name: String,
        val attributes: Map<String, String>,
        val selfClosing: Boolean,
    ) : Token

    data class EndTag(val name: String) : Token

    data class Text(val text: String) : Token
}

/**
 * Turns HTML source into a token stream.
 *
 * This is a pragmatic tokenizer, not a conforming one. The HTML specification
 * defines around eighty tokenizer states to make every malformed document parse
 * identically everywhere; that matters enormously for a real browser and not at
 * all for a shim whose job is to survive until Blink arrives.
 *
 * What it does guarantee is that it never throws and always terminates. Every
 * unterminated construct — a tag with no `>`, a comment with no `-->`, a
 * `<script>` with no close — is consumed to the end of input rather than
 * looping or failing. Malformed input is the normal case on the web, not the
 * exceptional one.
 *
 * Deleted in Stage 6.
 */
internal class HtmlTokenizer(private val source: String) {

    private var position = 0

    fun tokenize(): List<Token> {
        val tokens = ArrayList<Token>()
        while (position < source.length) {
            val next = source.indexOf('<', position)

            if (next < 0) {
                emitText(source.substring(position), tokens)
                position = source.length
                break
            }

            if (next > position) emitText(source.substring(position, next), tokens)
            position = next

            when {
                startsWith("<!--") -> skipComment()
                startsWith("<!") -> skipTo('>')          // doctype, CDATA, junk
                startsWith("<?") -> skipTo('>')          // processing instruction
                startsWith("</") -> readEndTag(tokens)
                isTagNameStart(charAt(position + 1)) -> readStartTag(tokens)
                else -> {
                    // A bare '<' in prose. Not a tag; emit it as text.
                    emitText("<", tokens)
                    position++
                }
            }
        }
        return tokens
    }

    private fun emitText(raw: String, tokens: MutableList<Token>) {
        if (raw.isEmpty()) return
        tokens.add(Token.Text(Entities.decode(raw)))
    }

    private fun charAt(index: Int): Char? = source.getOrNull(index)

    private fun startsWith(prefix: String): Boolean = source.startsWith(prefix, position)

    private fun isTagNameStart(c: Char?): Boolean = c != null && (c.isLetter() || c.isDigit())

    private fun skipComment() {
        val end = source.indexOf("-->", position + 4)
        position = if (end < 0) source.length else end + 3
    }

    private fun skipTo(terminator: Char) {
        val end = source.indexOf(terminator, position)
        position = if (end < 0) source.length else end + 1
    }

    private fun readEndTag(tokens: MutableList<Token>) {
        val nameStart = position + 2
        var i = nameStart
        while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '-' || source[i] == ':')) i++
        val name = source.substring(nameStart, i).lowercase()

        val close = source.indexOf('>', i)
        position = if (close < 0) source.length else close + 1

        if (name.isNotEmpty()) tokens.add(Token.EndTag(name))
    }

    private fun readStartTag(tokens: MutableList<Token>) {
        val nameStart = position + 1
        var i = nameStart
        while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '-' || source[i] == ':')) i++
        val name = source.substring(nameStart, i).lowercase()

        if (name.isEmpty()) {
            emitText("<", tokens)
            position++
            return
        }

        val attributes = LinkedHashMap<String, String>()
        var selfClosing = false

        while (i < source.length) {
            while (i < source.length && source[i].isWhitespace()) i++
            if (i >= source.length) break

            if (source[i] == '>') {
                i++
                break
            }
            if (source[i] == '/') {
                selfClosing = true
                i++
                continue
            }

            val attrStart = i
            while (i < source.length && !source[i].isWhitespace() && source[i] != '=' && source[i] != '>' && source[i] != '/') i++
            val attrName = source.substring(attrStart, i).lowercase()
            if (attrName.isEmpty()) {
                i++
                continue
            }

            while (i < source.length && source[i].isWhitespace()) i++

            var value = ""
            if (i < source.length && source[i] == '=') {
                i++
                while (i < source.length && source[i].isWhitespace()) i++
                if (i < source.length) {
                    val quote = source[i]
                    if (quote == '"' || quote == '\'') {
                        val valueStart = i + 1
                        val end = source.indexOf(quote, valueStart)
                        if (end < 0) {
                            value = source.substring(valueStart)
                            i = source.length
                        } else {
                            value = source.substring(valueStart, end)
                            i = end + 1
                        }
                    } else {
                        val valueStart = i
                        while (i < source.length && !source[i].isWhitespace() && source[i] != '>') i++
                        value = source.substring(valueStart, i)
                    }
                }
            }

            attributes[attrName] = Entities.decode(value)
        }

        position = i
        tokens.add(Token.StartTag(name, attributes, selfClosing || name in VOID_ELEMENTS))

        // Raw-text elements swallow their content wholesale: a '<' inside a
        // script is not markup, and treating it as markup is how a stray
        // comparison operator eats the rest of the page.
        if (name in RAW_TEXT_ELEMENTS) {
            val closeTag = "</$name"
            val end = source.indexOf(closeTag, position, ignoreCase = true)
            if (end < 0) {
                position = source.length
            } else {
                // The content between is deliberately dropped, not tokenized.
                position = end
                readEndTag(tokens)
            }
        }
    }
}

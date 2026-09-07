package app.auriel.cobalt.engine.html

/**
 * A node in a parsed document.
 *
 * The tree carries elements and text and nothing else: no comments, no script or
 * style content, no doctype. Those are dropped during parsing because 0.1.0 has
 * nothing to do with them, and keeping them would invite the renderer to grow
 * features it is not allowed to have.
 *
 * Deleted in Stage 6, when Blink's DOM replaces it.
 */
sealed interface Node

/** A run of character data. */
data class TextNode(val text: String) : Node {
    val isBlank: Boolean get() = text.isBlank()
}

/** An element, its attributes, and its children. */
class Element(
    val tagName: String,
    val attributes: Map<String, String> = emptyMap(),
    children: List<Node> = emptyList(),
) : Node {

    private val _children: MutableList<Node> = children.toMutableList()

    val children: List<Node> get() = _children

    internal fun append(node: Node) {
        _children.add(node)
    }

    /** Attribute lookup, case-insensitive as the parser lowercases names. */
    fun attribute(name: String): String? = attributes[name.lowercase()]

    /** All text beneath this element, concatenated. */
    fun textContent(): String = buildString { appendText(this@Element, this) }

    override fun toString(): String = "<$tagName>${children.size} children"

    private fun appendText(element: Element, out: StringBuilder) {
        for (child in element.children) {
            when (child) {
                is TextNode -> out.append(child.text)
                is Element -> appendText(child, out)
            }
        }
    }
}

/** A parsed document. [title] is the `<title>` text if the page had one. */
class Document(val root: Element, val title: String?) {

    /** Depth-first walk over every element, root first. */
    fun forEachElement(action: (Element) -> Unit) {
        fun visit(element: Element) {
            action(element)
            for (child in element.children) if (child is Element) visit(child)
        }
        visit(root)
    }
}

/**
 * Elements that never have children and never need a closing tag. A parser that
 * does not know these will nest the entire rest of a page inside the first
 * `<br>` it meets.
 */
internal val VOID_ELEMENTS = setOf(
    "area", "base", "br", "col", "embed", "hr", "img", "input",
    "link", "meta", "param", "source", "track", "wbr",
)

/** Elements whose content is raw text, not markup, and is discarded. */
internal val RAW_TEXT_ELEMENTS = setOf("script", "style", "template", "noscript")

package app.auriel.cobalt.render

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.auriel.cobalt.engine.html.Document
import app.auriel.cobalt.engine.html.Element
import app.auriel.cobalt.engine.html.TextNode
import app.auriel.cobalt.ui.theme.DocumentType
import app.auriel.cobalt.ui.theme.JetBrainsMono

/**
 * Renders a parsed document as Compose content.
 *
 * ### This code is scheduled for deletion
 *
 * It exists to put pixels on screen in 0.1.0, before the Chromium build works.
 * Blink replaces it wholesale in Stage 6, along with the parser that feeds it.
 *
 * It is therefore **deliberately incomplete**, and must stay that way. There is
 * no CSS, no layout algorithm, no box model, no floats, no tables, and no
 * scripting. Every one of those is something Blink already does properly, and
 * every hour spent approximating one here is an hour spent on code with a
 * deletion date. If a page renders badly, the fix is to land Stage 6 sooner.
 *
 * What it does support is the structural subset that makes a text document
 * readable: headings, paragraphs, inline emphasis, links, lists, preformatted
 * text, block quotes, rules, and line breaks.
 */
@Composable
fun DocumentRenderer(
    document: Document,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    // The tree is flattened once, so the content area can be lazy. A long page
    // rendered as one nested Column measures every node on every frame, which a
    // phone notices immediately.
    val blocks = remember(document, colors.primary) {
        BlockBuilder(linkColor = colors.primary).build(document)
    }

    LazyColumn(modifier = modifier, contentPadding = contentPadding) {
        items(blocks.size) { index ->
            when (val block = blocks[index]) {
                is Block.Paragraph -> LinkableText(
                    text = block.text,
                    style = if (block.fontSize != null) {
                        // Headings are set in the serif; body prose is not.
                        DocumentType.heading.copy(
                            fontSize = block.fontSize,
                            color = colors.onSurface,
                        )
                    } else {
                        DocumentType.body.copy(color = colors.onSurface)
                    },
                    onLinkClick = onLinkClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = block.spaceAbove.dp, bottom = 10.dp),
                )

                is Block.Preformatted -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .background(colors.surfaceVariant, RoundedCornerShape(2.dp))
                        .padding(10.dp)
                        .horizontalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = block.text,
                        style = DocumentType.code,
                        color = colors.onSurfaceVariant,
                        softWrap = false,
                    )
                }

                is Block.Quote -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                ) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .background(colors.outlineVariant),
                    ) {
                        // The rule sizes itself to the quote it sits beside.
                        Text("", style = DocumentType.body)
                    }
                    LinkableText(
                        text = block.text,
                        style = DocumentType.body.copy(
                            fontStyle = FontStyle.Italic,
                            color = colors.onSurfaceVariant,
                        ),
                        onLinkClick = onLinkClick,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }

                is Block.ListItem -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = (block.depth * 18).dp, bottom = 6.dp),
                ) {
                    Text(
                        text = block.marker,
                        style = DocumentType.body,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    LinkableText(
                        text = block.text,
                        style = DocumentType.body.copy(color = colors.onSurface),
                        onLinkClick = onLinkClick,
                    )
                }

                Block.Rule -> HorizontalDivider(
                    Modifier.padding(vertical = 12.dp),
                    color = colors.outlineVariant,
                )
            }
        }
    }
}

/** Text whose link annotations are tappable. */
@Composable
private fun LinkableText(
    text: AnnotatedString,
    style: TextStyle,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resolved = style.copy(color = if (style.color == Color.Unspecified) LocalContentColor.current else style.color)

    ClickableText(
        text = text,
        style = resolved,
        modifier = modifier,
        onClick = { offset ->
            text.getStringAnnotations(LINK_ANNOTATION, offset, offset)
                .firstOrNull()
                ?.let { onLinkClick(it.item) }
        },
    )
}

internal const val LINK_ANNOTATION = "cobalt.href"

/** One renderable block, flattened out of the tree. */
private sealed interface Block {

    data class Paragraph(
        val text: AnnotatedString,
        val fontSize: androidx.compose.ui.unit.TextUnit? = null,
        val fontWeight: FontWeight? = null,
        val spaceAbove: Int = 0,
    ) : Block

    data class Preformatted(val text: String) : Block

    data class Quote(val text: AnnotatedString) : Block

    data class ListItem(val marker: String, val text: AnnotatedString, val depth: Int) : Block

    data object Rule : Block
}

private val HEADINGS = mapOf(
    "h1" to (28.sp to 12),
    "h2" to (24.sp to 10),
    "h3" to (20.sp to 8),
    "h4" to (18.sp to 8),
    "h5" to (16.sp to 6),
    "h6" to (15.sp to 6),
)

private val LIST_TAGS = setOf("ul", "ol")

/**
 * Elements that break a run of inline content.
 *
 * `html` and `body` belong here even though no author thinks of them as blocks.
 * Leaving them out makes the walker treat the document root as an inline run and
 * flatten the entire page into one paragraph — every heading, list, and rule
 * concatenated into a single line of text.
 */
private val BLOCK_TAGS = setOf(
    "html", "body", "address", "article", "aside", "blockquote", "center",
    "details", "div", "dd", "dl", "dt", "fieldset", "figcaption", "figure",
    "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hgroup",
    "hr", "img", "li", "main", "menu", "nav", "ol", "p", "pre", "section",
    "summary", "table", "tbody", "td", "tfoot", "th", "thead", "tr", "ul",
)

/** Elements with no readable content — their subtrees are skipped entirely. */
private val SKIPPED_TAGS = setOf("head", "title", "meta", "link", "base", "select", "textarea")

/**
 * Walks the DOM and flattens it into blocks.
 *
 * Link destinations ride along as annotations on the text rather than as
 * separate nodes, which is what lets a paragraph containing three links stay a
 * single text layout instead of three.
 */
private class BlockBuilder(private val linkColor: Color) {

    private val blocks = ArrayList<Block>()

    fun build(document: Document): List<Block> {
        walk(document.root, depth = 0)
        return blocks
    }

    private fun walk(element: Element, depth: Int) {
        if (element.tagName in SKIPPED_TAGS) return

        when {
            element.tagName == "hr" -> blocks.add(Block.Rule)

            element.tagName == "pre" -> {
                val text = element.textContent().trim('\n')
                if (text.isNotBlank()) blocks.add(Block.Preformatted(text))
            }

            element.tagName == "blockquote" -> {
                val text = inline(element)
                if (text.isNotBlank()) blocks.add(Block.Quote(text))
            }

            element.tagName == "img" -> {
                // Images are not decoded in 0.1.0; alt text stands in, which is
                // what the attribute is for.
                val alt = element.attribute("alt").orEmpty().trim()
                if (alt.isNotEmpty()) {
                    blocks.add(Block.Paragraph(AnnotatedString("[image: $alt]")))
                }
            }

            element.tagName in HEADINGS -> {
                val (size, space) = HEADINGS.getValue(element.tagName)
                val text = inline(element)
                if (text.isNotBlank()) {
                    blocks.add(Block.Paragraph(text, fontSize = size, fontWeight = FontWeight.Bold, spaceAbove = space))
                }
            }

            element.tagName in LIST_TAGS -> walkList(element, depth)

            else -> walkContainer(element, depth)
        }
    }

    private fun walkList(list: Element, depth: Int) {
        val ordered = list.tagName == "ol"
        var counter = list.attribute("start")?.toIntOrNull() ?: 1

        for (child in list.children) {
            if (child !is Element) continue

            if (child.tagName != "li") {
                walk(child, depth)
                continue
            }

            val marker = if (ordered) "${counter++}." else "•"
            blocks.add(Block.ListItem(marker, inline(child, skipLists = true), depth + 1))

            // A nested list becomes its own items at greater depth rather than
            // being flattened into the parent item's text.
            for (grandchild in child.children) {
                if (grandchild is Element && grandchild.tagName in LIST_TAGS) walk(grandchild, depth + 1)
            }
        }
    }

    private fun walkContainer(element: Element, depth: Int) {
        val hasBlockChildren = element.children.any { it is Element && it.tagName in BLOCK_TAGS }

        if (!hasBlockChildren) {
            val text = inline(element)
            if (text.isNotBlank()) blocks.add(Block.Paragraph(text))
            return
        }

        // Mixed content. Loose inline runs between block children are emitted as
        // their own paragraphs so that text not wrapped in a <p> — which is most
        // of the web — is not silently dropped.
        var pending = InlineWriter(linkColor)
        for (child in element.children) {
            when (child) {
                is TextNode -> pending.text(child.text)
                is Element -> if (child.tagName in BLOCK_TAGS) {
                    pending.finish()?.let { if (it.isNotBlank()) blocks.add(Block.Paragraph(it)) }
                    pending = InlineWriter(linkColor)
                    walk(child, depth)
                } else if (child.tagName !in SKIPPED_TAGS) {
                    pending.element(child)
                }
            }
        }
        pending.finish()?.let { if (it.isNotBlank()) blocks.add(Block.Paragraph(it)) }
    }

    private fun inline(element: Element, skipLists: Boolean = false): AnnotatedString {
        val writer = InlineWriter(linkColor, skipLists)
        for (child in element.children) {
            when (child) {
                is TextNode -> writer.text(child.text)
                is Element -> writer.element(child)
            }
        }
        return writer.finish() ?: AnnotatedString("")
    }
}

/**
 * Builds one run of inline content.
 *
 * Whitespace is collapsed **as it is appended**, not afterwards. Collapsing a
 * finished [AnnotatedString] would shift every span and annotation off the text
 * it describes, which silently detaches links from their words.
 */
private class InlineWriter(
    private val linkColor: Color,
    private val skipLists: Boolean = false,
) {

    private val builder = AnnotatedString.Builder()
    private var pendingSpace = false
    private var wroteAnything = false

    fun text(raw: String) {
        for (c in raw) {
            if (c == '\r') continue
            if (c == ' ' || c == '\n' || c == '\t') {
                // Leading whitespace is dropped; interior runs collapse to one
                // space, emitted lazily so trailing space never survives.
                if (wroteAnything) pendingSpace = true
                continue
            }
            if (pendingSpace) {
                builder.append(' ')
                pendingSpace = false
            }
            builder.append(c)
            wroteAnything = true
        }
    }

    fun element(element: Element) {
        when (element.tagName) {
            "br" -> {
                builder.append('\n')
                pendingSpace = false
                wroteAnything = true
            }

            "b", "strong" -> styled(SpanStyle(fontWeight = FontWeight.Bold), element)
            "i", "em", "cite", "var", "dfn" -> styled(SpanStyle(fontStyle = FontStyle.Italic), element)
            "code", "kbd", "samp", "tt" -> styled(SpanStyle(fontFamily = JetBrainsMono), element)
            "u", "ins" -> styled(SpanStyle(textDecoration = TextDecoration.Underline), element)
            "s", "del", "strike" -> styled(SpanStyle(textDecoration = TextDecoration.LineThrough), element)
            "small" -> styled(SpanStyle(fontSize = 13.sp), element)

            "a" -> {
                val href = element.attribute("href")?.trim()
                if (href.isNullOrEmpty()) {
                    children(element)
                } else {
                    builder.pushStringAnnotation(LINK_ANNOTATION, href)
                    builder.pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                    children(element)
                    builder.pop()
                    builder.pop()
                }
            }

            "ul", "ol" -> if (!skipLists) children(element)

            in SKIPPED_TAGS -> Unit

            else -> children(element)
        }
    }

    /** Returns the accumulated text, or null if nothing readable was written. */
    fun finish(): AnnotatedString? {
        if (!wroteAnything) return null
        return builder.toAnnotatedString()
    }

    private fun styled(style: SpanStyle, element: Element) {
        builder.pushStyle(style)
        children(element)
        builder.pop()
    }

    private fun children(element: Element) {
        for (child in element.children) {
            when (child) {
                is TextNode -> text(child.text)
                is Element -> element(child)
            }
        }
    }
}

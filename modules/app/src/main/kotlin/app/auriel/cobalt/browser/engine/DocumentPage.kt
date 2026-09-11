package app.auriel.cobalt.browser.engine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.auriel.cobalt.core.net.resolveUrl
import app.auriel.cobalt.engine.html.HtmlParser
import app.auriel.cobalt.render.DocumentRenderer

/**
 * A [DocumentSession]'s page, drawn with Compose.
 *
 * Chromium draws its own error pages; the document engine has none, so its
 * failures are drawn here rather than by the shell, which stays the same for
 * both engines.
 */
@Composable
internal fun DocumentPage(session: DocumentSession, modifier: Modifier) {
    val state by session.state.collectAsState()
    val page by session.page.collectAsState()

    Box(modifier.fillMaxSize()) {
        val error = state.error
        val current = page
        when {
            error != null -> Message(error.headline(), error.detail())
            current != null -> {
                val document = remember(current) {
                    if (current.contentType.isHtml) {
                        HtmlParser.parse(current.text)
                    } else {
                        // Plain text is wrapped rather than parsed, so markup in
                        // a text/plain response is shown, not rendered.
                        HtmlParser.parse("<pre>${escape(current.text)}</pre>")
                    }
                }
                DocumentRenderer(
                    document = document,
                    onLinkClick = { href ->
                        // mailto:, javascript: and the like resolve to null.
                        // Declining silently is right: there is nothing to hand
                        // them to, and an error would blame the page.
                        resolveUrl(current.finalUrl, href)?.let { session.loadUrl(it.toString()) }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private fun SessionError.headline() = when (this) {
    is SessionError.BadUrl -> "That is not a web address"
    is SessionError.Unreachable -> "Could not load the page"
    is SessionError.HttpStatus -> "Server returned $code"
    is SessionError.Blocked -> "Blocked"
    is SessionError.Other -> "Something went wrong"
}

private fun SessionError.detail() = when (this) {
    is SessionError.BadUrl -> "Cobalt could not read \"$typed\" as an http or https URL."
    is SessionError.Unreachable -> detail.orEmpty()
    is SessionError.HttpStatus -> ""
    is SessionError.Blocked -> detail.orEmpty()
    is SessionError.Other -> detail.orEmpty()
}

@Composable
private fun Message(headline: String, detail: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = headline,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (detail.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun escape(text: String) =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

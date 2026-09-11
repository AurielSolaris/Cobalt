package app.auriel.cobalt.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The new-tab page.
 *
 * ### On what is deliberately absent
 *
 * Reference designs for this screen usually carry a shortcut grid and a
 * trending-searches list. Both are advertising surfaces: the shortcuts are sold
 * placements and the trending list is a feed with an incentive to be sticky.
 * Cobalt has neither, and will not grow either.
 *
 * When there is browsing history to draw on (Stage 7), this page can show the
 * user's own most-visited sites — earned by their behaviour, not by a payment.
 * Until then it shows nothing, which is honest and also blank in a restful way.
 */
@Composable
fun HomeContent(
    incognito: Boolean,
    modifier: Modifier = Modifier,
) {
    if (incognito) {
        IncognitoHome(modifier)
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Wordmark()

        Spacer(Modifier.height(14.dp))

        Text(
            text = "Type an address below to load a page.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun IncognitoHome(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .background(colors.surfaceVariant, MaterialTheme.shapes.medium)
                .border(1.dp, colors.primary.copy(alpha = 0.4f), MaterialTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.VisibilityOff,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(40.dp),
            )
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Incognito",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
        )

        Spacer(Modifier.height(10.dp))

        Text(
            // Stated precisely, because a vague promise here is worse than none.
            // 0.1.0 keeps no history, cookies, or cache in any tab, so incognito
            // currently marks intent rather than enforcing a boundary. That
            // becomes real in Stage 7, when there is state to withhold.
            text = "Pages opened in this tab are kept out of history and site data.\n\n" +
                "This build stores nothing yet in any tab, so the separation is not " +
                "enforced until Stage 7. Incognito does not hide you from the sites " +
                "you visit or from your network.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** The wordmark. A real mark replaces this in Stage 4. */
@Composable
private fun Wordmark() {
    Text(
        text = "Cobalt",
        style = MaterialTheme.typography.displaySmall.copy(
            fontSize = 40.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 6.sp,
        ),
        color = MaterialTheme.colorScheme.primary,
    )
}

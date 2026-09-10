package app.auriel.cobalt.content

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The Compose-over-SurfaceView spike, kept as a runnable screen.
 *
 * docs/shell-integration.md calls this the riskiest unknown in the shell work:
 * Chromium composites the page into a SurfaceView, which is composited by
 * SurfaceFlinger rather than by the view hierarchy, so "does Compose draw over
 * it" is not answerable from Compose's own painting rules.
 *
 * What this shows:
 *
 *  - a real [SurfaceView] with real pixels drawn onto it through its holder,
 *    standing in for the renderer;
 *  - Compose chrome above it — a translucent bar top and bottom, the shape the
 *    real interface takes ([0002](docs/decisions/0002-shell-design.md));
 *  - a switch that flips `setZOrderOnTop`, so the failure mode is visible next
 *    to the working one rather than described.
 *
 * With the switch off, the Compose bars must be visible over the surface. With
 * it on, they must disappear behind it. If the first does not hold, Cobalt's
 * interface cannot be Compose drawn over the page and the shell needs a
 * different structure.
 *
 * Kept rather than deleted because it is the regression test for the seam: when
 * `ContentViewRenderView` replaces the placeholder, this screen should keep
 * behaving identically.
 */
@Composable
fun ContentSurfaceSpike(modifier: Modifier = Modifier) {
    var zOrderOnTop by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        ContentSurface(
            modifier = Modifier.fillMaxSize(),
            zOrderOnTop = zOrderOnTop,
        )

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Stands in for the address bar.
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .background(Color(0xCC1B2130)),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = "Compose draws here",
                    color = Color(0xFF61AFEF),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            // Stands in for the bottom bar: Home, Extensions, Tabs, Downloads.
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xCC1B2130))
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (zOrderOnTop) "zOrderOnTop = true" else "zOrderOnTop = false",
                        color = Color(0xFFABB2BF),
                    )
                    Switch(checked = zOrderOnTop, onCheckedChange = { zOrderOnTop = it })
                }
                Text(
                    text =
                        if (zOrderOnTop) {
                            "Surface is above the window: this bar should be hidden."
                        } else {
                            "Surface is behind the window: this bar should be visible."
                        },
                    color = Color(0xFF7F848E),
                )
            }
        }
    }
}

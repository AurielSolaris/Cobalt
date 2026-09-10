package app.auriel.cobalt.content

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import app.auriel.cobalt.ui.theme.CobaltTheme

/**
 * Runs [ContentSurfaceSpike] on its own, without touching the browser.
 *
 * A separate Activity rather than a screen inside the app, so the spike cannot
 * affect anything the browser does. It stays `exported="false"`, which means
 * `adb shell am start` is refused outright:
 *
 *     Permission Denial: starting Intent { ... } not exported from uid ...
 *
 * Launch it with root, which is the honest cost of not exporting a debug
 * surface from a shipping app:
 *
 *     adb shell su -c 'am start -n app.auriel.cobalt.nightly/ *         app.auriel.cobalt.content.ContentSurfaceSpikeActivity'
 *
 * Note the package: debug builds carry `applicationIdSuffix = ".nightly"`, so
 * this installs alongside the Chromium-based build rather than over it.
 */
class ContentSurfaceSpikeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CobaltTheme { ContentSurfaceSpike(Modifier.fillMaxSize()) } }
    }
}

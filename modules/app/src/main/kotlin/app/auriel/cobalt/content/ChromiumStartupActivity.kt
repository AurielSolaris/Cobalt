package app.auriel.cobalt.content

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.auriel.cobalt.ui.theme.CobaltTheme

/**
 * Starts Chromium's browser process and reports what happened.
 *
 * The join between Cobalt's Compose app and the Chromium AAR, made runnable.
 * It draws the state and nothing else — no page, no tab, no chrome — because
 * "did the browser process start" is one question and mixing rendering into it
 * would make a failure ambiguous.
 *
 * Not exported, so `adb shell am start` is refused; launch it with root:
 *
 *     adb shell su -c 'am start -n app.auriel.cobalt.nightly/\
 *         app.auriel.cobalt.content.ChromiumStartupActivity'
 *
 * What each outcome means:
 *
 *  - **Failed at `LibraryLoader.ensureInitialized`** — `libchrome.so` did not
 *    load, or its JNI registration disagreed with the Java in the AAR. That is
 *    the mismatch predicted in `docs/shell-integration.md` and would mean the
 *    AAR has to carry `chrome/android`'s Java after all.
 *  - **Failed at `startBrowserProcessesAsync`** — the library loaded and
 *    registered fine, and the browser process itself refused. Most likely a
 *    missing asset (the `.pak` bundles, ICU data) or a path Chromium expected
 *    to own.
 *  - **Ready** — Chromium is running inside an app built from Compose source,
 *    and gate 3 has no unanswered viability questions left.
 */
class ChromiumStartupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CobaltTheme { StartupScreen() } }
    }
}

@Composable
private fun StartupScreen() {
    val context = LocalContext.current
    val state by ChromiumStartup.state.collectAsState()

    // Startup has to run on the UI thread; a composition effect is on it.
    LaunchedEffect(Unit) { ChromiumStartup.start(context) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text("Chromium startup", color = Color(0xFF61AFEF))
        when (val s = state) {
            is ChromiumStartup.State.NotStarted ->
                Text("not started", color = Color(0xFF7F848E))
            is ChromiumStartup.State.Starting ->
                Text("starting…", color = Color(0xFFABB2BF))
            is ChromiumStartup.State.Ready ->
                Text("READY — the browser process is running.", color = Color(0xFF98C379))
            is ChromiumStartup.State.Failed -> {
                Text("FAILED at ${s.stage}", color = Color(0xFFE06C75))
                Text(
                    text = s.cause?.toString() ?: "no exception; see logcat",
                    color = Color(0xFF7F848E),
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

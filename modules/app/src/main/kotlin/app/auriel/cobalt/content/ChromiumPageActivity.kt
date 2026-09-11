package app.auriel.cobalt.content

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.auriel.cobalt.browser.engine.EngineSession
import app.auriel.cobalt.browser.tabs.TabModel
import app.auriel.cobalt.ui.theme.CobaltTheme

/**
 * Pages, rendered by Chromium, inside a Compose window.
 *
 * The step after [ChromiumStartupActivity]. It answered whether a `WebContents`
 * can be driven from a Compose shell that carries none of `chrome/android`'s
 * UI, and now exercises [TabModel] over the real engine: several tabs sharing
 * one surface, switched with `ChromiumEngine.show`.
 *
 * It is still a spike, not the browser. No address bar, no bottom bar — a
 * status line and three buttons over the page. The real interface is
 * `browser/BrowserScreen.kt`, and it plugs into the same [TabModel].
 *
 * Not exported; launch it with root:
 *
 *     adb shell su -c 'am start -n app.auriel.cobalt.nightly/\
 *         app.auriel.cobalt.content.ChromiumPageActivity'
 *
 * An `-e url <address>` extra overrides the first page, which is how this gets
 * pointed at `chrome://extensions` to check the extension surface, or at a real
 * site to check that uBlock Origin is still blocking.
 */
class ChromiumPageActivity : ComponentActivity() {

    private var engine: ChromiumEngine? = null

    /**
     * Compose state, not a plain field.
     *
     * The status line reads this, and a plain `var` set from the `AndroidView`
     * factory changes nothing Compose is watching: the first screenshot of this
     * spike showed a fully rendered page under the words "creating the tab…",
     * which had been true for about a second.
     */
    private var tabs: TabModel? by mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent?.getStringExtra("url") ?: DEFAULT_URL
        setContent { CobaltTheme { PageScreen(url) } }
    }

    override fun onDestroy() {
        // Order matters: a session is a renderer process and the engine owns the
        // surface it draws into, so the tabs go first.
        tabs?.destroy()
        engine?.shutdown()
        tabs = null
        engine = null
        super.onDestroy()
    }

    @Composable
    private fun PageScreen(url: String) {
        val startup by ChromiumStartup.state.collectAsState()
        var failure by remember { mutableStateOf<String?>(null) }

        // The browser process has to be running before an engine can exist, so
        // this is one screen with two phases rather than two screens.
        DisposableEffect(Unit) {
            ChromiumStartup.start(this@ChromiumPageActivity)
            onDispose {}
        }

        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF0B0E14))
                // The page is edge to edge; the status line is not, or it
                // draws underneath the system clock.
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            val model = tabs
            val list = model?.state?.collectAsState()?.value
            StatusLine(startup, list?.active?.session, failure)

            if (model != null && list != null) {
                Row(Modifier.padding(horizontal = 4.dp)) {
                    TextButton(onClick = { model.newTab(url = url) }) { Text("new") }
                    TextButton(onClick = {
                        val i = list.tabs.indexOfFirst { it.id == list.activeId }
                        model.select(list.tabs[(i + 1) % list.tabs.size].id)
                    }) { Text("next") }
                    TextButton(onClick = { model.close(list.activeId) }) { Text("close") }
                    Text(
                        "tab ${list.tabs.indexOfFirst { it.id == list.activeId } + 1}" +
                            " of ${list.tabs.size}",
                        color = Color(0xFF7F848E),
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (startup is ChromiumStartup.State.Ready && failure == null) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            try {
                                val created = ChromiumEngine(this@ChromiumPageActivity)
                                engine = created
                                tabs = TabModel(created).also {
                                    it.state.value.active.session.loadUrl(url)
                                }
                                created.createContentContainer(context)
                            } catch (t: Throwable) {
                                // A failure here is native, and the exception is
                                // usually the only readable part of it. Drawing
                                // it beats a blank screen and a logcat hunt.
                                failure = t.toString()
                                android.widget.FrameLayout(context)
                            }
                        },
                    )
                }
            }
        }
    }

    private companion object {
        /**
         * Chromium's own version page: served by the browser itself, so it
         * needs no network and cannot fail for a reason outside the engine.
         * That makes it the right first page — a blank screen means the
         * renderer, not the connection.
         */
        const val DEFAULT_URL = "chrome://version"
    }
}

@Composable
private fun StatusLine(
    startup: ChromiumStartup.State,
    session: EngineSession?,
    failure: String?,
) {
    val page = session?.state?.collectAsState()?.value

    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        when {
            failure != null ->
                Text(
                    "engine failed: $failure",
                    color = Color(0xFFE06C75),
                    fontFamily = FontFamily.Monospace,
                )

            startup is ChromiumStartup.State.Failed ->
                Text(
                    "startup failed at ${startup.stage}",
                    color = Color(0xFFE06C75),
                    fontFamily = FontFamily.Monospace,
                )

            startup !is ChromiumStartup.State.Ready ->
                Text("starting the browser process…", color = Color(0xFF7F848E))

            page == null ->
                Text("creating the tab…", color = Color(0xFF7F848E))

            else -> {
                Text(page.title ?: "(no title)", color = Color(0xFFABB2BF))
                Text(
                    page.url ?: "(no url)",
                    color = if (page.isLoading) Color(0xFFE5C07B) else Color(0xFF98C379),
                    fontFamily = FontFamily.Monospace,
                )
                page.error?.let {
                    Text(it.toString(), color = Color(0xFFE06C75))
                }
            }
        }
    }
}

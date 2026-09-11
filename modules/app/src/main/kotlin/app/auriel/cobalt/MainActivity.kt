package app.auriel.cobalt

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.auriel.cobalt.browser.BrowserApp
import app.auriel.cobalt.browser.BrowserController
import app.auriel.cobalt.browser.CenteredMessage
import app.auriel.cobalt.browser.Screenshot
import app.auriel.cobalt.browser.engine.ShellEngine
import app.auriel.cobalt.browser.engine.ShellEngines
import app.auriel.cobalt.ui.theme.CobaltTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The browser's Activity.
 *
 * It owns the engine and the tabs directly, not through a `ViewModel`: every
 * tab is bound to this Activity's window (see [BrowserController]), and the
 * manifest's `configChanges` keep it alive across rotation, as browsers do.
 */
class MainActivity : ComponentActivity() {

    private lateinit var shell: ShellEngine
    private var controller: BrowserController? by mutableStateOf(null)

    /**
     * A URL handed over by an intent, waiting for the tabs to exist. Browser
     * start-up is asynchronous on Chromium, so an intent can arrive first.
     */
    private var pendingUrl by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        pendingUrl = urlFrom(intent)
        shell = ShellEngines.create(this)
        lifecycleScope.launch {
            shell.status.first { it == ShellEngine.Status.Ready }
            controller = BrowserController(shell, lifecycleScope)
        }

        setContent {
            val current = controller
            if (current == null) {
                Starting()
                return@setContent
            }

            val state by current.state.collectAsStateWithLifecycle()

            LaunchedEffect(pendingUrl) {
                pendingUrl?.let { url ->
                    current.navigateTo(url)
                    pendingUrl = null
                }
            }

            // Only claims Back when there is somewhere to go inside the
            // browser; otherwise it falls through and leaves the app.
            BackHandler(enabled = state.canHandleBack) { current.onBack() }

            BrowserApp(
                state = state,
                page = { modifier -> shell.Page(state.activeTab.let { current.session(it.id) }, modifier) },
                onAddressChanged = current::onAddressChanged,
                onGo = current::onGo,
                onReload = current::onReload,
                onStop = current::onStop,
                onForward = current::onForward,
                onHome = current::onHome,
                onScreenshot = ::screenshot,
                onSectionSelected = current::onSectionSelected,
                onNewTab = current::onNewTab,
                onTabSelected = current::onTabSelected,
                onCloseTab = current::onCloseTab,
                onCloseAllTabs = current::onCloseAllTabs,
                onSheetOpening = current::captureThumbnail,
            )
        }
    }

    @androidx.compose.runtime.Composable
    private fun Starting() {
        val status by shell.status.collectAsState()
        CobaltTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                when (val s = status) {
                    is ShellEngine.Status.Failed -> CenteredMessage("Cobalt could not start", s.message)
                    else -> CenteredMessage("Cobalt", "Starting…")
                }
            }
        }
    }

    private fun screenshot(bounds: Rect) {
        val current = controller ?: return
        val session = current.session(current.state.value.activeTabId)
        lifecycleScope.launch {
            val saved = Screenshot.save(
                this@MainActivity,
                shell.capturePage(session),
                android.graphics.Rect(
                    bounds.left.toInt(), bounds.top.toInt(),
                    bounds.right.toInt(), bounds.bottom.toInt(),
                ),
            )
            Toast.makeText(
                this@MainActivity,
                if (saved != null) "Screenshot saved to Pictures/Cobalt" else "Screenshot failed",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingUrl = urlFrom(intent)
    }

    override fun onDestroy() {
        // Tabs before the engine: a tab is a renderer process, and the engine
        // owns the surface it draws into.
        controller?.destroy()
        controller = null
        shell.destroy()
        super.onDestroy()
    }

    private fun urlFrom(intent: Intent?): String? =
        if (intent?.action == Intent.ACTION_VIEW) intent.dataString else null
}

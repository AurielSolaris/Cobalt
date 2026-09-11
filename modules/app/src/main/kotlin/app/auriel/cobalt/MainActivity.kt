package app.auriel.cobalt

import android.content.Intent
import app.auriel.cobalt.browser.LocalPdf
import android.net.Uri
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
import app.auriel.cobalt.browser.DownloadActions
import app.auriel.cobalt.browser.search.SearchStore
import app.auriel.cobalt.browser.Screenshot
import app.auriel.cobalt.browser.engine.ShellEngine
import app.auriel.cobalt.browser.engine.ShellEngines
import app.auriel.cobalt.browser.settings.AboutInfo
import app.auriel.cobalt.ui.theme.CobaltTheme
import app.auriel.cobalt.ui.theme.ThemeStore
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

    /** A PDF another app asked Cobalt to open, waiting like [pendingUrl]. */
    private var pendingPdf by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        ThemeStore.init(this)
        SearchStore.init(this)
        pendingUrl = urlFrom(intent)
        pendingPdf = pdfFrom(intent)
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
            LaunchedEffect(pendingPdf) {
                pendingPdf?.let { uri ->
                    openPdf(uri)
                    pendingPdf = null
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
                certificate = { current.session(state.activeTabId).certificate() },
                about = AboutInfo(engineName = shell.engineName, creditsUrl = shell.creditsUrl),
                onOpenInNewTab = current::openInNewTab,
                downloadActions = DownloadActions(
                    onPause = { current.onPauseDownload(it) },
                    onResume = { current.onResumeDownload(it) },
                    onCancel = { current.onCancelDownload(it) },
                    onRemove = { current.onRemoveDownload(it) },
                    onOpen = { id ->
                        val uri = current.downloadUri(id)
                        if (shell.opensPdfs && uri != null && current.downloadIsPdf(id)) {
                            // A PDF opens here, in pdf.js, not in another app.
                            openPdf(uri)
                        } else if (!current.onOpenDownload(id)) {
                            Toast.makeText(this, "No app on this phone can open that file", Toast.LENGTH_SHORT).show()
                        }
                    },
                ),
                onDismissDownloadNotice = current::onDismissDownloadNotice,
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
        pendingPdf = pdfFrom(intent)
    }

    /**
     * A local PDF, handed over through the "Cobalt" entry Android shows in its
     * open-with list (the PDF activity-alias in the manifest). Web addresses
     * that happen to end in .pdf go through [urlFrom] like any page.
     */
    private fun pdfFrom(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val data = intent.data ?: return null
        if (data.scheme != "content" && data.scheme != "file") return null
        return data.takeIf { LocalPdf.isPdf(intent.type, it) }
    }

    /** Copies the PDF where pdf.js can read it and opens it in a new tab. */
    private fun openPdf(uri: Uri) {
        val current = controller ?: return
        lifecycleScope.launch {
            val fileUrl = LocalPdf.stage(this@MainActivity, uri)
            if (fileUrl == null) {
                Toast.makeText(this@MainActivity, "Could not open that PDF", Toast.LENGTH_SHORT).show()
            } else {
                current.openLocalFile(fileUrl)
            }
        }
    }

    override fun onDestroy() {
        // Tabs before the engine: a tab is a renderer process, and the engine
        // owns the surface it draws into.
        controller?.destroy()
        controller = null
        shell.destroy()
        super.onDestroy()
    }

    /** A web address handed over by another app; local files are [pdfFrom]'s. */
    private fun urlFrom(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val scheme = intent.data?.scheme ?: return null
        return intent.dataString.takeIf { scheme == "http" || scheme == "https" }
    }
}

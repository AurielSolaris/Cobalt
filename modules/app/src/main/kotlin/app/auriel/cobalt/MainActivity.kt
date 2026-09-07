package app.auriel.cobalt

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.auriel.cobalt.browser.BrowserApp
import app.auriel.cobalt.browser.BrowserViewModel

/**
 * The only Activity.
 *
 * Stage 7 adds a second one for a custom-tab surface; until then a browser that
 * shows one document at a time has no reason to have more than one.
 */
class MainActivity : ComponentActivity() {

    /**
     * A URL handed over by an intent, waiting for the composition to pick it up.
     *
     * The Activity does not call the view model directly: the model is owned by
     * the composition, and reaching into it from here would mean holding a
     * reference whose lifetime is not the Activity's to manage. Publishing the
     * URL as state and letting the composition react keeps that ownership
     * one-directional.
     */
    private var pendingUrl by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        pendingUrl = urlFrom(intent)

        setContent {
            val model: BrowserViewModel = viewModel()
            val state by model.state.collectAsStateWithLifecycle()

            LaunchedEffect(pendingUrl) {
                pendingUrl?.let { url ->
                    model.navigateTo(url)
                    // Cleared so a configuration change does not re-navigate.
                    pendingUrl = null
                }
            }

            BrowserApp(
                state = state,
                onAddressChanged = model::onAddressChanged,
                onGo = model::onGo,
                onReload = model::onReload,
                onStop = model::onStop,
                onLinkClicked = model::onLinkClicked,
                onSectionSelected = model::onSectionSelected,
                onNewTab = model::onNewTab,
                onTabSelected = model::onTabSelected,
                onCloseTab = model::onCloseTab,
                onCloseAllTabs = model::onCloseAllTabs,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingUrl = urlFrom(intent)
    }

    private fun urlFrom(intent: Intent?): String? =
        if (intent?.action == Intent.ACTION_VIEW) intent.dataString else null
}

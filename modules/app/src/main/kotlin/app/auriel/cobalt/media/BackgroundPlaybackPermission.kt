package app.auriel.cobalt.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Whether Cobalt may keep audio playing after you leave it, and how it asks.
 *
 * Keeping a page's audio alive in the background is not passive on Android. It
 * needs a foreground service of type `mediaPlayback`, that service must post a
 * notification, and from API 33 posting a notification needs the user's
 * permission. So "background playback" and "may Cobalt notify you" are the same
 * question, and this treats them as one rather than asking twice.
 *
 * ## When to ask, which matters more than the asking
 *
 * **Not at startup.** A browser that opens with a notification prompt before it
 * has shown a page is asking for something the user has no way to evaluate, and
 * the reflex is to refuse — permanently, because Android stops asking after two
 * refusals. Call [rememberBackgroundPlaybackPermission] and request only when
 * the user first plays media, where the request explains itself.
 *
 * ## Refusal is a real answer
 *
 * Denied means playback stops when Cobalt goes to the background. It does not
 * mean a nag on next launch, and it does not mean a degraded browser anywhere
 * else. [State.PermanentlyDenied] exists so the interface can say so plainly and
 * point at system settings, rather than showing a prompt Android will never
 * display.
 */
object BackgroundPlayback {

    /**
     * Below API 33, `POST_NOTIFICATIONS` does not exist and notifications are
     * granted by installing. The permission flow only applies above it.
     */
    val needsPermission: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun isGranted(context: Context): Boolean {
        if (!needsPermission) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    sealed interface State {
        /** Cobalt may keep playing in the background. */
        data object Granted : State

        /** Not asked yet, or asked and dismissed. Asking again is reasonable. */
        data object NotGranted : State

        /**
         * Refused to the point where Android will no longer show the dialog.
         *
         * Requesting again does nothing at all — the callback returns denied
         * without any UI — so the interface must stop offering to ask and offer
         * system settings instead.
         */
        data object PermanentlyDenied : State
    }
}

/** Handle for asking, and the current answer. */
class BackgroundPlaybackPermission internal constructor(
    val state: BackgroundPlayback.State,
    private val requestPermission: () -> Unit,
) {
    /**
     * Shows the system dialog, if there is any point.
     *
     * A no-op when already granted, and when permanently denied — in the second
     * case Android shows nothing and reports denial immediately, so calling it
     * would leave the interface waiting for an answer that already arrived.
     */
    fun request() {
        if (state == BackgroundPlayback.State.NotGranted) requestPermission()
    }
}

/**
 * Tracks and requests the background-playback permission.
 *
 * Ask at the moment media starts, not before:
 *
 * ```
 * val permission = rememberBackgroundPlaybackPermission()
 * // when the user presses play:
 * if (permission.state != BackgroundPlayback.State.Granted) permission.request()
 * ```
 */
@Composable
fun rememberBackgroundPlaybackPermission(): BackgroundPlaybackPermission {
    val context = LocalContext.current

    var state by remember {
        mutableStateOf(
            if (BackgroundPlayback.isGranted(context)) {
                BackgroundPlayback.State.Granted
            } else {
                BackgroundPlayback.State.NotGranted
            }
        )
    }

    // Tracks whether this session has already shown the dialog. A denial after
    // the dialog was shown is an answer; a denial with no dialog means Android
    // has stopped asking, and the two need different handling. There is no API
    // that reports the difference directly.
    var asked by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        state = when {
            granted -> BackgroundPlayback.State.Granted
            asked -> BackgroundPlayback.State.PermanentlyDenied
            else -> BackgroundPlayback.State.NotGranted
        }
    }

    return BackgroundPlaybackPermission(state) {
        asked = true
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

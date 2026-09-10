package app.auriel.cobalt.content

import android.graphics.Color as AndroidColor
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Hosts the surface the web page is composited onto.
 *
 * This is the seam between Cobalt's Compose interface and Chromium's renderer.
 * Chromium composites into a [SurfaceView] owned by `ContentViewRenderView`, and
 * a SurfaceView is not an ordinary View: it punches a hole through the window
 * and its content is composited by SurfaceFlinger, not by the view hierarchy.
 * Anything Compose draws is therefore drawn by a *different* compositor, and
 * whether it lands above or below the page is decided by z-ordering rules that
 * have nothing to do with Compose's own painting order.
 *
 * That is the risk this file exists to pin down, and it is worth pinning down
 * before the browser is wired in, because if Compose cannot reliably draw over
 * the content surface then Cobalt's whole interface — the bottom bar, the
 * address bar, every menu and sheet — has to be built a different way.
 *
 * See docs/shell-integration.md.
 *
 * Today this draws a solid colour so the composition can be verified on its own.
 * [onSurfaceReady] is where `ContentViewRenderView` attaches later: it hands out
 * the real [SurfaceView] so the embedder can take it over without this file
 * knowing anything about Chromium.
 */
@Composable
fun ContentSurface(
    modifier: Modifier = Modifier,
    zOrderOnTop: Boolean = false,
    placeholderColor: Int = AndroidColor.parseColor("#0B0E14"),
    onSurfaceReady: (SurfaceView) -> Unit = {},
) {
    val state = remember { ContentSurfaceState() }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                SurfaceView(context).apply {
                    // The whole question in one line.
                    //
                    // false: the surface sits *behind* the window, so anything
                    //   Compose draws lands on top of it. This is what a browser
                    //   wants — page underneath, interface over it.
                    // true: the surface is composited above the window and
                    //   Compose content is hidden behind it, whatever the
                    //   painting order says.
                    //
                    // Chromium's ContentViewRenderView defaults to false for the
                    // same reason, which is a good sign that this is the
                    // supported direction rather than a lucky accident.
                    setZOrderOnTop(zOrderOnTop)
                    holder.addCallback(state.callback(this, placeholderColor, onSurfaceReady))
                }
            },
            update = { view -> view.setZOrderOnTop(zOrderOnTop) },
            onRelease = { view -> state.release(view) },
        )
    }

    DisposableEffect(Unit) { onDispose { state.dispose() } }
}

/**
 * Tracks the surface lifecycle separately from composition.
 *
 * A [SurfaceView]'s surface is created and destroyed by the window, on its own
 * schedule, which does not line up with when Compose decides to recompose. The
 * renderer has to be told about both, so the callback is kept in one place
 * rather than captured inside the factory lambda.
 */
private class ContentSurfaceState {
    val surfaceCreated: MutableState<Boolean> = mutableStateOf(false)
    private var holderCallback: SurfaceHolder.Callback? = null

    fun callback(
        view: SurfaceView,
        placeholderColor: Int,
        onSurfaceReady: (SurfaceView) -> Unit,
    ): SurfaceHolder.Callback {
        val callback =
            object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    surfaceCreated.value = true
                    paintPlaceholder(holder, placeholderColor)
                    onSurfaceReady(view)
                }

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int,
                ) {
                    paintPlaceholder(holder, placeholderColor)
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    surfaceCreated.value = false
                }
            }
        holderCallback = callback
        return callback
    }

    /**
     * Stands in for the renderer.
     *
     * Drawing with [SurfaceHolder.lockCanvas] is exactly what Chromium will not
     * do — it composites through GL — but it produces real pixels on the real
     * surface, which is what the z-order question needs. A [Box] with a
     * background colour would prove nothing, because it would be drawn by the
     * same compositor as everything else.
     */
    private fun paintPlaceholder(holder: SurfaceHolder, color: Int) {
        val canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawColor(color)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    fun release(view: View) {
        (view as? SurfaceView)?.let { surface ->
            holderCallback?.let { surface.holder.removeCallback(it) }
        }
        holderCallback = null
        surfaceCreated.value = false
    }

    fun dispose() {
        holderCallback = null
        surfaceCreated.value = false
    }
}

package app.auriel.cobalt.browser

import android.app.Activity
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * The ⋮ menu's screenshot: the visible page, without the browser around it.
 *
 * Where the pixels come from depends on who drew them. Chromium's page is not
 * in the window at all (see `ShellEngine.capturePage`), so the engine supplies
 * it. A page Compose draws, the document engine's, is copied from the window
 * with `PixelCopy`.
 */
object Screenshot {

    /**
     * Saves the page; null if capturing or saving failed.
     *
     * @param fromEngine the engine's own capture, if it made one
     * @param bounds the page area in window coordinates, used when it did not
     */
    suspend fun save(activity: Activity, fromEngine: Bitmap?, bounds: Rect): Uri? {
        val bitmap = fromEngine ?: copy(activity, bounds) ?: return null
        return withContext(Dispatchers.IO) { save(activity, bitmap) }
    }

    private suspend fun copy(activity: Activity, bounds: Rect): Bitmap? {
        if (bounds.isEmpty) return null
        return suspendCancellableCoroutine { continuation ->
            val bitmap = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
            PixelCopy.request(
                activity.window,
                bounds,
                bitmap,
                { result -> continuation.resume(if (result == PixelCopy.SUCCESS) bitmap else null) },
                Handler(Looper.getMainLooper()),
            )
        }
    }

    /**
     * Into `Pictures/Cobalt` through MediaStore. No storage permission: an app
     * may always add to the shared collections on API 29+, which is Cobalt's
     * floor. Written pending and published once complete, so a gallery never
     * shows a half-written file.
     */
    private fun save(activity: Activity, bitmap: Bitmap): Uri? {
        val resolver = activity.contentResolver
        val name = "Cobalt_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Cobalt")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val written = resolver.openOutputStream(uri)?.use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        } ?: false
        if (!written) {
            resolver.delete(uri, null, null)
            return null
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }
}

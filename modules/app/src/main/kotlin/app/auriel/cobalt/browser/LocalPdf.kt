package app.auriel.cobalt.browser

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Makes a PDF Cobalt has been handed openable by the bundled pdf.js.
 *
 * Other apps (and Cobalt's own Downloads) hand over `content://` URIs, which a
 * web page, the pdf.js viewer included, cannot read. pdf.js opens local files
 * by `file://` URL, and Chromium on Android lets a tab open `file://` only on
 * external storage or in the app's private Downloads directories
 * (`IsAccessAllowedAndroid` in chrome_network_delegate.cc). So the PDF is
 * copied into the second of those, which needs no storage permission, and the
 * tab is sent to its `file://` URL; pdf.js, granted file access at install,
 * redirects that to its viewer.
 *
 * Copies live in one folder and are replaced by name, so opening the same file
 * twice does not accumulate; the folder is app storage, removed with the app.
 */
object LocalPdf {

    /** @return the `file://` URL to open, or null if the file could not be read. */
    suspend fun stage(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.let { File(it, "pdf") }
            ?: return@withContext null
        if (!dir.isDirectory && !dir.mkdirs()) return@withContext null

        val dest = File(dir, safeName(displayName(context, uri)))
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            } ?: return@withContext null
        }.getOrElse { return@withContext null }
        Uri.fromFile(dest).toString()
    }

    fun isPdf(mimeType: String?, uri: Uri?): Boolean =
        mimeType.equals("application/pdf", ignoreCase = true) ||
            uri?.lastPathSegment?.endsWith(".pdf", ignoreCase = true) == true

    private fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment ?: "document.pdf"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) return c.getString(0) }
        return uri.lastPathSegment ?: "document.pdf"
    }

    /** A file name, never a path: another app's name must not point outside the folder. */
    private fun safeName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim('.', ' ')
            .ifEmpty { "document" }
            .take(120)
        // pdf.js's file:// rule matches on the extension.
        return if (base.endsWith(".pdf", ignoreCase = true)) base else "$base.pdf"
    }
}

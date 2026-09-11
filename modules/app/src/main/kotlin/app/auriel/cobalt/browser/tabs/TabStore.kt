package app.auriel.cobalt.browser.tabs

import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/** One tab as it was written to disk. */
class SavedTab(
    val url: String?,
    val title: String?,
    /** The engine's own history bytes ([app.auriel.cobalt.browser.engine.EngineSession.saveState]). */
    val state: ByteArray?,
)

class SavedTabs(val tabs: List<SavedTab>, val activeIndex: Int)

/**
 * The open tabs, on disk, so they survive the app closing: swiped away,
 * killed by Android in the background, crashed, or updated.
 *
 * One file, replaced whole through [AtomicFile], so a crash mid-write leaves
 * the previous list rather than half of a new one. Incognito tabs are never
 * written; that is what incognito means.
 *
 * The format is Cobalt's, versioned by [FORMAT]; a file this build cannot read
 * is ignored and the browser opens with one blank tab, as it did before tabs
 * were kept at all.
 */
class TabStore(dir: File) {

    private val file = AtomicFile(File(dir, "tabs.bin"))

    fun load(): SavedTabs? = try {
        DataInputStream(file.openRead().buffered()).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != FORMAT) return null
            val active = input.readInt()
            val count = input.readInt()
            if (count !in 0..MAX_TABS) return null
            val tabs = List(count) {
                SavedTab(
                    url = input.readBytesOrNull()?.decodeToString(),
                    title = input.readBytesOrNull()?.decodeToString(),
                    state = input.readBytesOrNull(),
                )
            }
            SavedTabs(tabs, active)
        }
    } catch (_: IOException) {
        null
    }

    fun save(saved: SavedTabs) {
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(FORMAT)
                out.writeInt(saved.activeIndex)
                out.writeInt(saved.tabs.size)
                for (tab in saved.tabs) {
                    out.writeBytesOrNull(tab.url?.encodeToByteArray())
                    out.writeBytesOrNull(tab.title?.encodeToByteArray())
                    out.writeBytesOrNull(tab.state)
                }
            }
        }.toByteArray()
        val stream = try {
            file.startWrite()
        } catch (_: IOException) {
            return
        }
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (_: IOException) {
            file.failWrite(stream)
        }
    }

    private fun DataInputStream.readBytesOrNull(): ByteArray? {
        val size = readInt()
        if (size < 0) return null
        if (size > MAX_FIELD) throw IOException("field of $size bytes")
        return ByteArray(size).also(::readFully)
    }

    private fun DataOutputStream.writeBytesOrNull(bytes: ByteArray?) {
        if (bytes == null) {
            writeInt(-1)
        } else {
            writeInt(bytes.size)
            write(bytes)
        }
    }

    private companion object {
        const val MAGIC = 0x43425442 // "CBTB"
        const val FORMAT = 1
        const val MAX_TABS = 10_000
        const val MAX_FIELD = 64 shl 20
    }
}

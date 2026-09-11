package app.auriel.cobalt

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every R field Chromium's bytecode reads from a package the app's own
 * dependencies provide must exist in the R AGP wrote for it.
 *
 * `tools/build/generate-chromium-r.py` skips those packages rather than
 * emitting duplicate classes, and lists what it skipped. A field missing here
 * would otherwise surface as a `NoSuchFieldError` deep inside Chromium, on
 * device, at whatever moment that resource is first touched.
 *
 * Passes vacuously without the AAR: then there is no Chromium code to read them.
 */
class ProvidedRFieldsTest {

    @Test
    fun everySkippedFieldExistsInTheAppsR() {
        val list = File("src/chromium/r/provided-r-fields.txt")
        if (!list.exists()) return

        val missing = list.readLines().filter { it.isNotBlank() }.filterNot { ref ->
            val owner = ref.substringBeforeLast('.')
            val field = ref.substringAfterLast('.')
            runCatching { Class.forName(owner).getField(field) }.isSuccess
        }
        assertTrue(missing.isEmpty(), "absent from the app's R:\n" + missing.joinToString("\n"))
    }
}

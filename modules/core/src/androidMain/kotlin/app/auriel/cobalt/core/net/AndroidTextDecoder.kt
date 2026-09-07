package app.auriel.cobalt.core.net

import java.io.UnsupportedEncodingException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.IllegalCharsetNameException
import java.nio.charset.UnsupportedCharsetException

/**
 * Decodes with the JVM's charset registry, so legacy encodings that are still
 * common on the web — windows-1252, Shift_JIS, GBK — render correctly.
 *
 * Never throws. An unknown encoding falls back to UTF-8, and malformed bytes are
 * replaced rather than rejected.
 */
object AndroidTextDecoder : TextDecoder {

    override fun decode(bytes: ByteArray, charset: String): String {
        val resolved = resolve(charset)
        val start = CharsetDetection.byteOrderMarkLength(bytes)

        return try {
            resolved.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(ByteBuffer.wrap(bytes, start, bytes.size - start))
                .toString()
        } catch (e: Exception) {
            // The decoder is configured to replace rather than fail, so reaching
            // here means something structural. Lossy UTF-8 still beats no page.
            bytes.decodeToString(startIndex = start, endIndex = bytes.size)
        }
    }

    private fun resolve(charset: String): Charset = try {
        Charset.forName(charset)
    } catch (e: IllegalCharsetNameException) {
        Charsets.UTF_8
    } catch (e: UnsupportedCharsetException) {
        Charsets.UTF_8
    } catch (e: UnsupportedEncodingException) {
        Charsets.UTF_8
    }
}

package app.auriel.cobalt.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContentTypeTest {

    @Test
    fun mimeTypeIsLowercasedAndCharsetExtracted() {
        val parsed = ContentType.parse("Text/HTML; charset=UTF-8")
        assertEquals("text/html", parsed?.mimeType)
        assertEquals("UTF-8", parsed?.charset)
    }

    @Test
    fun missingCharsetIsNullRatherThanAssumed() {
        assertNull(ContentType.parse("text/html")?.charset)
    }

    @Test
    fun quotedAndSpacedParametersAreHandled() {
        assertEquals("utf-8", ContentType.parse("text/html ; charset=\"utf-8\"")?.charset)
        assertEquals("iso-8859-1", ContentType.parse("text/html;charset='iso-8859-1'")?.charset)
    }

    @Test
    fun otherParametersAreIgnoredButCharsetIsStillFound() {
        assertEquals("utf-8", ContentType.parse("text/html; boundary=xyz; charset=utf-8")?.charset)
    }

    @Test
    fun unparseableInputIsNull() {
        assertNull(ContentType.parse(null))
        assertNull(ContentType.parse(""))
        assertNull(ContentType.parse("   "))
        assertNull(ContentType.parse("nonsense"))
    }

    @Test
    fun textAndHtmlAreClassified() {
        assertEquals(true, ContentType.parse("text/html")?.isHtml)
        assertEquals(true, ContentType.parse("application/xhtml+xml")?.isHtml)
        assertEquals(true, ContentType.parse("text/plain")?.isText)
        assertEquals(false, ContentType.parse("image/png")?.isText)
        assertEquals(false, ContentType.parse("application/pdf")?.isText)
    }
}

class CharsetDetectionTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun byteOrderMarksAreRecognized() {
        assertEquals("UTF-8", CharsetDetection.fromByteOrderMark(bytes(0xEF, 0xBB, 0xBF, 0x41)))
        assertEquals("UTF-16BE", CharsetDetection.fromByteOrderMark(bytes(0xFE, 0xFF, 0x00, 0x41)))
        assertEquals("UTF-16LE", CharsetDetection.fromByteOrderMark(bytes(0xFF, 0xFE, 0x41, 0x00)))
        assertNull(CharsetDetection.fromByteOrderMark(bytes(0x41, 0x42)))
        assertNull(CharsetDetection.fromByteOrderMark(ByteArray(0)))
    }

    @Test
    fun aByteOrderMarkOutranksTheHeader() {
        // The bytes know better than the server does.
        val withBom = bytes(0xEF, 0xBB, 0xBF, 0x41)
        assertEquals("UTF-8", CharsetDetection.select("windows-1252", withBom))
    }

    @Test
    fun theHeaderIsUsedWhenThereIsNoMark() {
        assertEquals("windows-1252", CharsetDetection.select("windows-1252", bytes(0x41)))
    }

    @Test
    fun fallbackIsUtf8() {
        assertEquals("UTF-8", CharsetDetection.select(null, bytes(0x41)))
        assertEquals("UTF-8", CharsetDetection.select("", bytes(0x41)))
        assertEquals("UTF-8", CharsetDetection.select("   ", bytes(0x41)))
    }
}

class Utf8TextDecoderTest {

    @Test
    fun decodesUtf8() {
        val bytes = "héllo — wörld".encodeToByteArray()
        assertEquals("héllo — wörld", Utf8TextDecoder.decode(bytes, "UTF-8"))
    }

    @Test
    fun stripsTheByteOrderMark() {
        // A leading BOM must not reach the parser as a zero-width character.
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val bytes = bom + "<p>hi</p>".encodeToByteArray()
        assertEquals("<p>hi</p>", Utf8TextDecoder.decode(bytes, "UTF-8"))
    }

    @Test
    fun malformedBytesDoNotThrow() {
        val bytes = byteArrayOf(0x41, 0xFF.toByte(), 0xFE.toByte(), 0x42)
        // The content is lossy; the requirement is only that it comes back.
        assertEquals(true, Utf8TextDecoder.decode(bytes, "UTF-8").isNotEmpty())
    }

    @Test
    fun emptyInputDecodesToEmptyString() {
        assertEquals("", Utf8TextDecoder.decode(ByteArray(0), "UTF-8"))
    }
}

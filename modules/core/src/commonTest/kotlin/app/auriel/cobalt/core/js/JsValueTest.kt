package app.auriel.cobalt.core.js

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsValueTest {

    @Test
    fun undefinedAndNullAreDistinct() {
        assertTrue(JsValue.UNDEFINED.isUndefined)
        assertFalse(JsValue.UNDEFINED.isNull)
        assertTrue(JsValue.NULL.isNull)
        assertFalse(JsValue.NULL.isUndefined)
    }

    @Test
    fun primitivesRoundTripThroughFactories() {
        assertEquals(JsValueType.BOOLEAN, JsValue.of(true).type)
        assertEquals(true, JsValue.of(true).asBoolean)

        assertEquals(JsValueType.NUMBER, JsValue.of(3.5).type)
        assertEquals(3.5, JsValue.of(3.5).asNumber)

        assertEquals(JsValueType.STRING, JsValue.of("cobalt").type)
        assertEquals("cobalt", JsValue.of("cobalt").asString)
    }

    @Test
    fun stringRepresentationMatchesJsSemantics() {
        assertEquals("undefined", JsValue.UNDEFINED.toString())
        assertEquals("null", JsValue.NULL.toString())
        assertEquals("true", JsValue.of(true).toString())
        assertEquals("42.0", JsValue.of(42.0).toString())
    }
}

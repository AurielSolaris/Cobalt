package app.auriel.cobalt.engine

import app.auriel.cobalt.core.js.JsValue
import app.auriel.cobalt.core.js.JsValueType
import kotlin.test.Test
import kotlin.test.assertEquals

class NoopEngineTest {

    @Test
    fun satisfiesEngineContract() {
        val engine = NoopEngine()
        assertEquals("noop", engine.name)

        val context = engine.createContext()
        assertEquals(JsValueType.UNDEFINED, context.evaluate("1 + 1").type)
        assertEquals(JsValue.UNDEFINED, context.evaluate("anything"))
    }
}

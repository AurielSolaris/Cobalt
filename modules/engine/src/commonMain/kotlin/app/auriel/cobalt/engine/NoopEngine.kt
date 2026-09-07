package app.auriel.cobalt.engine

import app.auriel.cobalt.core.js.JsContext
import app.auriel.cobalt.core.js.JsEngine
import app.auriel.cobalt.core.js.JsValue

/**
 * A backend-neutral JS engine used until the real JSC/V8 bindings are wired
 * in via cinterop. It implements the full [JsEngine] contract so the rest of
 * the browser can be built and tested independently of any native engine.
 */
class NoopEngine : JsEngine {

    override val name: String = "noop"
    override val isJsc: Boolean = false

    override fun createContext(): JsContext = NoopContext()

    override fun dispose() = Unit
}

private class NoopContext : JsContext {
    override fun evaluate(script: String): JsValue = JsValue.UNDEFINED
    override fun dispose() = Unit
}

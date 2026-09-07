package app.auriel.cobalt.engine

import app.auriel.cobalt.core.js.JsContext
import app.auriel.cobalt.core.js.JsEngine
import app.auriel.cobalt.core.js.JsValue

/**
 * A JS engine that accepts scripts and does nothing observable.
 *
 * 0.1.0 does not execute JavaScript; this satisfies the [JsEngine] contract so
 * the shell can be built and tested before V8 arrives with the Chromium tree in
 * Stage 6. Every evaluation returns `undefined` — the honest answer for an
 * engine that did not run anything.
 */
class NoopEngine : JsEngine {

    override val name: String = "noop"

    override fun createContext(): JsContext = NoopContext()

    override fun dispose() = Unit
}

private class NoopContext : JsContext {
    override fun evaluate(script: String): JsValue = JsValue.UNDEFINED
    override fun dispose() = Unit
}

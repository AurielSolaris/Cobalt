package app.auriel.cobalt.core.js

/**
 * An isolated JavaScript execution context. Analogous to a v8::Context or a
 * JSGlobalContextRef — but expressed without any engine-specific type.
 */
interface JsContext {

    /** Evaluate [script] and return a snapshot of the result. */
    fun evaluate(script: String): JsValue

    /** Release the context and its associated native resources. */
    fun dispose()
}

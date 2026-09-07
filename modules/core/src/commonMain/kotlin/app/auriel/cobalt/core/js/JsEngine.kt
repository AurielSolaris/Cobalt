package app.auriel.cobalt.core.js

/**
 * The JavaScript engine abstraction — the seam that lets Cobalt swap V8 out
 * for JavaScriptCore without touching the rest of the browser.
 *
 * Every concrete backend (V8, JSC, ...) implements this single interface.
 * Rendering, networking, and layout code depend only on [JsEngine], never on
 * a specific engine's types.
 */
interface JsEngine {

    val name: String

    /** True when this backend is JavaScriptCore. */
    val isJsc: Boolean

    /** Create an isolated execution context. */
    fun createContext(): JsContext

    /** Dispose of the engine and release all native resources. */
    fun dispose()
}

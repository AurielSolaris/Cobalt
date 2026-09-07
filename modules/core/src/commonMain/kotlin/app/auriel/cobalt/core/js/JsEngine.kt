package app.auriel.cobalt.core.js

/**
 * The JavaScript engine abstraction.
 *
 * V8 is the engine Cobalt ships. This interface exists so the rest of the
 * browser depends on a JS engine rather than on V8's types — which keeps the
 * shell testable against a stub, and keeps the door open for the Stage 12
 * JavaScriptCore experiment without committing to it.
 *
 * Nothing here may name a specific backend. A caller that needs to know which
 * engine it has should read [name] and be suspicious of its own reasons.
 */
interface JsEngine {

    /** Identifies the backend, for diagnostics and about screens. */
    val name: String

    /** Create an isolated execution context. */
    fun createContext(): JsContext

    /** Dispose of the engine and release all native resources. */
    fun dispose()
}

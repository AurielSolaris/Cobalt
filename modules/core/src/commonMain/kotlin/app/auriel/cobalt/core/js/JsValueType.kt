package app.auriel.cobalt.core.js

/**
 * Engine-agnostic classification of JavaScript values.
 * This is the shared vocabulary used across every JS engine backend
 * (V8, JavaScriptCore, or a future replacement), so no engine-specific
 * types leak into the rest of the browser.
 */
enum class JsValueType {
    UNDEFINED,
    NULL,
    BOOLEAN,
    NUMBER,
    STRING,
    OBJECT,
    ARRAY,
    FUNCTION,
    SYMBOL,
    BIGINT,
}

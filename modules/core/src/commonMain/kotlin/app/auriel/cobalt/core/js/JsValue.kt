package app.auriel.cobalt.core.js

/**
 * A plain, engine-agnostic JavaScript value snapshot.
 *
 * Engines marshal their native values (v8::Value, JSValueRef, ...) into this
 * type at the binding boundary. It deliberately carries no engine pointers so
 * it can cross threads, be cached, and survive a context teardown.
 */
data class JsValue(
    val type: JsValueType,
    val asBoolean: Boolean = false,
    val asNumber: Double = 0.0,
    val asString: String = "",
) {
    val isUndefined: Boolean get() = type == JsValueType.UNDEFINED
    val isNull: Boolean get() = type == JsValueType.NULL

    companion object {
        val UNDEFINED = JsValue(JsValueType.UNDEFINED)
        val NULL = JsValue(JsValueType.NULL)

        fun of(value: Boolean) = JsValue(JsValueType.BOOLEAN, asBoolean = value)
        fun of(value: Double) = JsValue(JsValueType.NUMBER, asNumber = value)
        fun of(value: String) = JsValue(JsValueType.STRING, asString = value)
    }

    override fun toString(): String = when (type) {
        JsValueType.UNDEFINED -> "undefined"
        JsValueType.NULL -> "null"
        JsValueType.BOOLEAN -> asBoolean.toString()
        JsValueType.NUMBER -> asNumber.toString()
        JsValueType.STRING -> asString
        JsValueType.OBJECT -> "[object Object]"
        JsValueType.ARRAY -> "[object Array]"
        JsValueType.FUNCTION -> "[object Function]"
        JsValueType.SYMBOL -> "Symbol()"
        JsValueType.BIGINT -> "0n"
    }
}

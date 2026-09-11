package app.auriel.cobalt.browser.search

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URLEncoder

/**
 * The address bar's search engines.
 *
 * Google and DuckDuckGo, plus the three the Chromium build already adds to
 * every region (Ecosia, Kagi, Qwant; tools/patches/cobalt-search-engines.py),
 * so the shell offers the same set as the engine underneath. DuckDuckGo is the
 * default: the maintainer's choice, and the one that fits a browser that ships
 * a tracker blocker.
 */
enum class SearchEngine(val id: String, val label: String, private val template: String) {
    DuckDuckGo("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    Google("google", "Google", "https://www.google.com/search?q=%s"),
    Ecosia("ecosia", "Ecosia", "https://www.ecosia.org/search?q=%s"),
    Kagi("kagi", "Kagi", "https://kagi.com/search?q=%s"),
    Qwant("qwant", "Qwant", "https://www.qwant.com/?q=%s");

    fun urlFor(query: String): String =
        template.replace("%s", URLEncoder.encode(query.trim(), "UTF-8"))

    /** The host, for the settings list: the address a search goes to. */
    val host: String get() = template.substringAfter("://").substringBefore('/')

    companion object {
        val Default = DuckDuckGo
        fun byId(id: String?): SearchEngine = entries.firstOrNull { it.id == id } ?: Default
    }
}

/**
 * Whether typed text is a search rather than an address.
 *
 * Decided before URL parsing, because a parser will happily read "cats" as the
 * host `https://cats`. Search wins when the text has a space, has no dot (and
 * is not localhost or a host:port), or starts with "?" -- the conventional way
 * to force a search for something that looks like a domain.
 */
fun looksLikeSearch(typed: String): Boolean {
    val text = typed.trim()
    if (text.isEmpty()) return false
    if (text.startsWith("?")) return true
    if (text.contains("://")) return false
    if (text.any { it.isWhitespace() }) return true
    val host = text.substringBefore('/').substringBefore('?').substringBefore('#')
    if (host.equals("localhost", ignoreCase = true) || host.startsWith("localhost:", ignoreCase = true)) return false
    // An IPv6 literal, or host:port.
    if (host.startsWith("[")) return false
    if (!host.contains('.')) return true
    // A dot but nothing either side of it ("v2." or ".net") is not a domain.
    return host.startsWith('.') || host.endsWith('.')
}

/** The query a forced search carries, without its leading "?". */
fun searchQuery(typed: String): String = typed.trim().removePrefix("?").trim()

/** The chosen engine, persisted. Process-wide, like the theme. */
object SearchStore {
    private val _engine = MutableStateFlow(SearchEngine.Default)
    val engine: StateFlow<SearchEngine> = _engine.asStateFlow()
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("search", Context.MODE_PRIVATE)
        _engine.value = SearchEngine.byId(prefs?.getString("engine", null))
    }

    fun set(engine: SearchEngine) {
        _engine.value = engine
        prefs?.edit()?.putString("engine", engine.id)?.apply()
    }
}

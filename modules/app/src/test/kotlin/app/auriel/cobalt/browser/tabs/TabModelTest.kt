package app.auriel.cobalt.browser.tabs

import app.auriel.cobalt.browser.engine.BrowserEngine
import app.auriel.cobalt.browser.engine.EngineSession
import app.auriel.cobalt.browser.engine.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Records what the model asks of the engine, and checks the one ordering rule
 * `ChromiumEngine` relies on: the session on screen is never a closed one.
 */
private class FakeEngine : BrowserEngine {
    val created = mutableListOf<FakeSession>()
    var shown: FakeSession? = null
    var shows = 0

    override fun createSession(incognito: Boolean) =
        FakeSession(incognito).also { created += it }

    override fun show(session: EngineSession) {
        session as FakeSession
        check(!session.closed) { "showed a closed session" }
        shown = session
        shows++
    }
}

private class FakeSession(val incognito: Boolean) : EngineSession {
    override val state: StateFlow<SessionState> = MutableStateFlow(SessionState())
    val loaded = mutableListOf<String>()
    var closed = false

    override fun loadUrl(url: String) { loaded += url }
    override fun reload() {}
    override fun stop() {}
    override fun goBack() = false
    override fun goForward() = false
    override fun close() {
        check(!closed) { "closed twice" }
        closed = true
    }
}

class TabModelTest {
    private val engine = FakeEngine()
    private val model = TabModel(engine)
    private val tabs get() = model.state.value

    @Test
    fun startsWithOneShownTab() {
        assertEquals(1, tabs.tabs.size)
        assertSame<Any?>(tabs.active.session, engine.shown)
    }

    @Test
    fun newTabOpensBesideActiveAndShowsIt() {
        val first = tabs.active
        val third = model.newTab()
        model.select(first.id)
        val second = model.newTab(url = "https://example.org")

        assertEquals(listOf(first.id, second.id, third.id), tabs.tabs.map { it.id })
        assertSame<Any?>(second.session, engine.shown)
        assertEquals(listOf("https://example.org"), (second.session as FakeSession).loaded)
    }

    @Test
    fun selectingTheActiveTabDoesNotReshow() {
        val before = engine.shows
        model.select(tabs.activeId)
        model.select(999)
        assertEquals(before, engine.shows)
    }

    @Test
    fun closingActivePrefersLeftNeighbourAndClosesSession() {
        val a = tabs.active
        val b = model.newTab()
        val c = model.newTab()
        model.close(c.id)

        assertEquals(b.id, tabs.activeId)
        assertSame<Any?>(b.session, engine.shown)
        assertTrue((c.session as FakeSession).closed)

        model.select(a.id)
        model.close(a.id)
        assertEquals(b.id, tabs.activeId) // leftmost closed: falls to the right
    }

    @Test
    fun closingBackgroundTabKeepsActive() {
        val a = tabs.active
        model.newTab()
        val shows = engine.shows
        model.close(a.id)
        assertEquals(shows, engine.shows)
        assertFalse(tabs.tabs.any { it.id == a.id })
    }

    @Test
    fun closingLastTabLeavesAFreshOne() {
        val only = tabs.active
        model.close(only.id)
        assertEquals(1, tabs.tabs.size)
        assertTrue(tabs.activeId != only.id)
        assertSame<Any?>(tabs.active.session, engine.shown)
    }

    @Test
    fun closeAllIncognitoKeepsNormalTabs() {
        val normal = tabs.active
        val secret = model.newTab(incognito = true)
        model.closeAll(incognitoOnly = true)

        assertEquals(listOf(normal.id), tabs.tabs.map { it.id })
        assertSame<Any?>(normal.session, engine.shown)
        assertTrue((secret.session as FakeSession).closed)
    }

    @Test
    fun destroyClosesEverySessionOnce() {
        model.newTab()
        model.destroy()
        model.destroy()
        assertTrue(engine.created.all { it.closed })
        assertFailsWith<IllegalStateException> { model.newTab() }
    }
}

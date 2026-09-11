package app.auriel.cobalt.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ThemeTest {

    @Test
    fun everyPresetIsLegible() {
        for (p in Presets.all) {
            val failing = p.contrastChecks().filterNot { it.passes }
            assertTrue(failing.isEmpty(), "${p.name}: $failing")
        }
    }

    @Test
    fun formatThenParseIsTheSameTheme() {
        for (p in Presets.all) {
            val back = ThemeText.parse(ThemeText.format(p), p.id)
            assertIs<ThemeText.Result.Ok>(back)
            assertEquals(p, back.palette)
            assertTrue(back.derived.isEmpty())
        }
    }

    @Test
    fun aMinimalThemeIsCompletedNotRefused() {
        val r = ThemeText.parse(
            """
            /* hand written */
            --name: "Night";
            --bg-primary: #011627;
            --fg: rgb(214, 222, 235);
            --accent: #8AF;
            """.trimIndent(),
            "custom-1",
        )
        assertIs<ThemeText.Result.Ok>(r)
        assertEquals("Night", r.palette.name)
        assertTrue(r.palette.isDark, "a dark background makes a dark theme")
        assertEquals(Color(0xFF88AAFF), r.palette.accent)
        assertTrue("danger" in r.derived)
    }

    @Test
    fun whatWouldBeSilentlyWrongIsAnError() {
        assertIs<ThemeText.Result.Error>(ThemeText.parse("--fg: #fff; --accent: #00f;", "x"))
        assertIs<ThemeText.Result.Error>(ThemeText.parse("--bg-primary: blue; --fg: #fff; --accent: #00f;", "x"))
        assertIs<ThemeText.Result.Error>(ThemeText.parse("not a theme", "x"))
    }

    @Test
    fun fixContrastReachesTheRatio() {
        val fixed = fixContrast(Color(0xFF333333), Color(0xFF222222), 4.5)
        assertTrue(contrast(fixed, Color(0xFF222222)) >= 4.5)
    }

    @Test
    fun incognitoIsAlwaysDistinctAndDark() {
        for (p in Presets.all) {
            val i = p.incognito()
            assertTrue(i.isDark)
            assertNotEquals(p.accent, i.accent)
            assertNotEquals(p.bgPrimary, i.bgPrimary)
            assertTrue(contrast(i.fg, i.bgPrimary) >= 4.5, p.name)
        }
    }

    @Test
    fun thePairFollowsTheSystemUnlessPinned() {
        val s = ThemeSettings()
        assertEquals(Presets.Cobalt, s.active(systemDark = true))
        assertEquals(Presets.SolarizedLight, s.active(systemDark = false))
        assertEquals(Presets.Cobalt, s.copy(mode = ThemeMode.Dark).active(systemDark = false))
        assertFalse(s.copy(darkId = "custom-gone").dark.isCustom, "a missing theme falls back")
    }
}

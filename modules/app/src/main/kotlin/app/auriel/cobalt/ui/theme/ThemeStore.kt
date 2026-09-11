package app.auriel.cobalt.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/** Which of the pair is in use. */
enum class ThemeMode { System, Light, Dark }

/** The user's theme choices, as one value. */
data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.System,
    val lightId: String = Presets.defaultLight.id,
    val darkId: String = Presets.defaultDark.id,
    val custom: List<Palette> = emptyList(),
) {
    val all: List<Palette> get() = Presets.all + custom

    fun byId(id: String): Palette? = all.firstOrNull { it.id == id }

    val light: Palette get() = byId(lightId) ?: Presets.defaultLight
    val dark: Palette get() = byId(darkId) ?: Presets.defaultDark

    /** The theme to draw with, given whether the system is in dark mode. */
    fun active(systemDark: Boolean): Palette = when (mode) {
        ThemeMode.Light -> light
        ThemeMode.Dark -> dark
        ThemeMode.System -> if (systemDark) dark else light
    }
}

/**
 * Persists [ThemeSettings]: the choices in `SharedPreferences`, each custom
 * theme as its own text file in the [ThemeText] format.
 *
 * Text rather than a binary blob so a user's themes are the same files they
 * would share, readable if anything ever goes wrong. They live in the app's
 * files directory, which Android's auto-backup covers, so they survive a
 * reinstall on a device with backup enabled (0007).
 *
 * A process-wide object: the theme is one fact for the whole app, and every
 * Activity, including the development spikes, draws with it.
 */
object ThemeStore {

    private val _settings = MutableStateFlow(ThemeSettings())
    val settings: StateFlow<ThemeSettings> = _settings.asStateFlow()

    private var dir: File? = null
    private var prefs: android.content.SharedPreferences? = null

    /** Loads saved themes. Idempotent; call from any Activity's `onCreate`. */
    fun init(context: Context) {
        if (prefs != null) return
        val app = context.applicationContext
        prefs = app.getSharedPreferences("themes", Context.MODE_PRIVATE)
        dir = File(app.filesDir, "themes").apply { mkdirs() }

        val custom = dir!!.listFiles { f -> f.extension == "css" }.orEmpty()
            .sortedBy { it.name }
            .mapNotNull { file ->
                (ThemeText.parse(file.readText(), file.nameWithoutExtension) as? ThemeText.Result.Ok)?.palette
            }
        val p = prefs!!
        _settings.value = ThemeSettings(
            mode = runCatching { ThemeMode.valueOf(p.getString("mode", null)!!) }.getOrDefault(ThemeMode.System),
            lightId = p.getString("light", null) ?: Presets.defaultLight.id,
            darkId = p.getString("dark", null) ?: Presets.defaultDark.id,
            custom = custom,
        )
    }

    fun setMode(mode: ThemeMode) = change { it.copy(mode = mode) }
    fun setLight(id: String) = change { it.copy(lightId = id) }
    fun setDark(id: String) = change { it.copy(darkId = id) }

    /** Adds or replaces a custom theme. */
    fun save(palette: Palette) {
        require(palette.isCustom) { "presets are not editable" }
        dir?.let { File(it, "${palette.id}.css").writeText(ThemeText.format(palette)) }
        change { s -> s.copy(custom = s.custom.filterNot { it.id == palette.id } + palette) }
    }

    /** Deletes a custom theme; a slot that used it falls back to the default. */
    fun delete(id: String) {
        dir?.let { File(it, "$id.css").delete() }
        change { s ->
            s.copy(
                custom = s.custom.filterNot { it.id == id },
                lightId = if (s.lightId == id) Presets.defaultLight.id else s.lightId,
                darkId = if (s.darkId == id) Presets.defaultDark.id else s.darkId,
            )
        }
    }

    fun newCustomId(): String = Palette.CUSTOM_PREFIX + System.currentTimeMillis()

    private fun change(transform: (ThemeSettings) -> ThemeSettings) {
        _settings.update(transform)
        val s = _settings.value
        prefs?.edit()
            ?.putString("mode", s.mode.name)
            ?.putString("light", s.lightId)
            ?.putString("dark", s.darkId)
            ?.apply()
    }
}

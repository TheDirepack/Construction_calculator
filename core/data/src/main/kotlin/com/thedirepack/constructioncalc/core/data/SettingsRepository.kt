package com.thedirepack.constructioncalc.core.data

import android.content.Context
import androidx.core.content.edit
import com.thedirepack.constructioncalc.core.measure.Precision
import com.thedirepack.constructioncalc.core.measure.input.LengthDisplay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/**
 * App-wide preferences: the single source for the one Settings screen. Every tool shows lengths
 * with these, so the app behaves as one system. Tools keep their own extra options in
 * [SettingsRepository.module] stores, shown as that tool's page in Settings.
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** When off, all motion (sheets, transitions) is instant. */
    val animations: Boolean = true,
    /** Rounding for fractional inches everywhere. */
    val precision: Precision = Precision.DEFAULT,
    /** Feet-inches, inches only, or Auto (inches when the input was in inches). */
    val lengthDisplay: LengthDisplay = LengthDisplay.AUTO,
    /** Where "Save to project" goes by default. */
    val activeProjectId: String? = null,
    val activeRunId: String? = null,
)

class SettingsRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = state.asStateFlow()

    fun setThemeMode(mode: ThemeMode) = save { it.copy(themeMode = mode) }

    fun setAnimations(enabled: Boolean) = save { it.copy(animations = enabled) }

    /** A tool's own settings store, e.g. `module("foot-inch")`. */
    fun module(toolId: String): ModuleSettings = synchronized(modules) {
        modules.getOrPut(toolId) {
            val known = prefs.getStringSet(KEY_MODULES, emptySet()).orEmpty()
            if (toolId !in known) prefs.edit { putStringSet(KEY_MODULES, known + toolId) }
            ModuleSettings(context.getSharedPreferences("module_$toolId", Context.MODE_PRIVATE))
        }
    }

    private val modules = mutableMapOf<String, ModuleSettings>()

    // --- Backup ---

    /** App-wide settings for a backup file. */
    fun exportSettings(): JsonObject {
        val s = state.value
        return buildJsonObject {
            put(KEY_THEME, s.themeMode.name)
            put(KEY_ANIMATIONS, s.animations)
            put(KEY_PRECISION, s.precision.denominator)
            put(KEY_DISPLAY, s.lengthDisplay.name)
            s.activeProjectId?.let { put(KEY_PROJECT, it) }
            s.activeRunId?.let { put(KEY_RUN, it) }
        }
    }

    /** Applies settings from a backup. Missing or unknown values keep their current setting. */
    fun importSettings(json: JsonObject) = save { current ->
        fun str(key: String) = json[key]?.jsonPrimitive?.contentOrNull
        current.copy(
            themeMode = str(KEY_THEME)?.let { n -> ThemeMode.entries.firstOrNull { it.name == n } } ?: current.themeMode,
            animations = json[KEY_ANIMATIONS]?.jsonPrimitive?.booleanOrNull ?: current.animations,
            precision = json[KEY_PRECISION]?.jsonPrimitive?.intOrNull?.let(Precision::fromDenominator) ?: current.precision,
            lengthDisplay = str(KEY_DISPLAY)?.let { n -> LengthDisplay.entries.firstOrNull { it.name == n } } ?: current.lengthDisplay,
            activeProjectId = str(KEY_PROJECT) ?: current.activeProjectId,
            activeRunId = str(KEY_RUN) ?: current.activeRunId,
        )
    }

    /** Every tool's settings that have any values, keyed by tool id. */
    fun exportToolSettings(): Map<String, JsonObject> =
        prefs.getStringSet(KEY_MODULES, emptySet()).orEmpty().sorted()
            .associateWith { module(it).exportJson() }
            .filterValues { it.isNotEmpty() }

    fun importToolSettings(values: Map<String, JsonObject>, replace: Boolean) {
        values.forEach { (toolId, json) -> module(toolId).importJson(json, replace) }
    }

    fun setPrecision(precision: Precision) = save { it.copy(precision = precision) }

    fun setLengthDisplay(display: LengthDisplay) = save { it.copy(lengthDisplay = display) }

    fun setActiveTarget(projectId: String?, runId: String?) =
        save { it.copy(activeProjectId = projectId, activeRunId = runId) }

    private fun save(change: (AppSettings) -> AppSettings) {
        state.update(change)
        val s = state.value
        prefs.edit {
            putString(KEY_THEME, s.themeMode.name)
            putBoolean(KEY_ANIMATIONS, s.animations)
            putInt(KEY_PRECISION, s.precision.denominator)
            putString(KEY_DISPLAY, s.lengthDisplay.name)
            putString(KEY_PROJECT, s.activeProjectId)
            putString(KEY_RUN, s.activeRunId)
        }
    }

    private fun load() = AppSettings(
        themeMode = prefs.getString(KEY_THEME, null)
            ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
            ?: ThemeMode.SYSTEM,
        animations = prefs.getBoolean(KEY_ANIMATIONS, true),
        precision = Precision.fromDenominator(prefs.getInt(KEY_PRECISION, Precision.DEFAULT.denominator)),
        lengthDisplay = prefs.getString(KEY_DISPLAY, null)
            ?.let { name -> LengthDisplay.entries.firstOrNull { it.name == name } }
            ?: LengthDisplay.AUTO,
        activeProjectId = prefs.getString(KEY_PROJECT, null),
        activeRunId = prefs.getString(KEY_RUN, null),
    )

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_ANIMATIONS = "animations"
        const val KEY_PRECISION = "precision"
        const val KEY_DISPLAY = "length_display"
        const val KEY_PROJECT = "active_project"
        const val KEY_RUN = "active_run"
        const val KEY_MODULES = "module_ids"
    }
}

/**
 * Key-value settings owned by one tool. Values are observable so the tool's screen and its page
 * in Settings stay in sync.
 */
class ModuleSettings internal constructor(private val prefs: android.content.SharedPreferences) {
    private val version = MutableStateFlow(0)

    /** Changes whenever any value changes; collect it to re-read values in Compose. */
    val changes: StateFlow<Int> = version.asStateFlow()

    fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    fun getString(key: String, default: String) = prefs.getString(key, default) ?: default
    fun getInt(key: String, default: Int) = prefs.getInt(key, default)

    fun setBoolean(key: String, value: Boolean) = write { putBoolean(key, value) }
    fun setString(key: String, value: String) = write { putString(key, value) }
    fun setInt(key: String, value: Int) = write { putInt(key, value) }
    fun remove(key: String) = write { remove(key) }

    /**
     * All values for a backup. Each value is stored with its type, e.g. `{"type":"int","value":5}`,
     * so it comes back exactly as it was.
     */
    fun exportJson(): JsonObject = buildJsonObject {
        prefs.all.toSortedMap().forEach { (key, value) ->
            val (type, element) = when (value) {
                is Boolean -> "boolean" to JsonPrimitive(value)
                is Int -> "int" to JsonPrimitive(value)
                is Long -> "long" to JsonPrimitive(value)
                is Float -> "float" to JsonPrimitive(value)
                is String -> "string" to JsonPrimitive(value)
                else -> return@forEach // string sets etc. aren't used by tools
            }
            put(key, buildJsonObject { put("type", type); put("value", element) })
        }
    }

    /** Restores [exportJson] output. With [replace], values not in [json] are cleared first. */
    fun importJson(json: JsonObject, replace: Boolean) = write {
        if (replace) clear()
        json.forEach { (key, entry) ->
            val obj = (entry as? JsonObject) ?: return@forEach
            val v: JsonElement = obj["value"] ?: return@forEach
            val p = v.jsonPrimitive
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "boolean" -> p.booleanOrNull?.let { putBoolean(key, it) }
                "int" -> p.intOrNull?.let { putInt(key, it) }
                "long" -> p.longOrNull?.let { putLong(key, it) }
                "float" -> p.contentOrNull?.toFloatOrNull()?.let { putFloat(key, it) }
                "string" -> p.contentOrNull?.let { putString(key, it) }
            }
        }
    }

    private fun write(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit(action = block)
        version.update { it + 1 }
    }
}

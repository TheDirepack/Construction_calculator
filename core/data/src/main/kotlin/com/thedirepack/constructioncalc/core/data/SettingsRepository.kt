package com.thedirepack.constructioncalc.core.data

import android.content.Context
import androidx.core.content.edit
import com.thedirepack.constructioncalc.core.measure.Precision
import com.thedirepack.constructioncalc.core.measure.FeetInchStyle
import com.thedirepack.constructioncalc.core.measure.MetricDisplay
import com.thedirepack.constructioncalc.core.measure.MetricResolution
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import com.thedirepack.constructioncalc.core.measure.input.LengthDisplay
import com.thedirepack.constructioncalc.core.model.ProjectFormatException
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

/** Who prepared the work: printed on shared projects, panel schedules and other exports. */
data class CompanyProfile(
    val name: String = "",
    val preparedBy: String = "",
    val license: String = "",
    val phone: String = "",
    val email: String = "",
    val address: String = "",
) {
    val isEmpty: Boolean get() = listOf(name, preparedBy, license, phone, email, address).all { it.isBlank() }

    /** Header lines for exports, skipping blanks: "Acme Electric · Lic. #123", "Prepared by …". */
    fun headerLines(): List<String> = buildList {
        val first = listOf(name, license.takeIf { it.isNotBlank() }?.let { "Lic. $it" }.orEmpty()).filter { it.isNotBlank() }
        if (first.isNotEmpty()) add(first.joinToString(" · "))
        val contact = listOf(phone, email).filter { it.isNotBlank() }
        if (contact.isNotEmpty()) add(contact.joinToString(" · "))
        if (address.isNotBlank()) add(address.trim())
        if (preparedBy.isNotBlank()) add("Prepared by ${preparedBy.trim()}")
    }
}

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
    /** Imperial or metric for everything shown and typed; stored values never change. */
    val unitSystem: UnitSystem = UnitSystem.IMPERIAL,
    /** Metric only: which unit lengths are shown in. */
    val metricDisplay: MetricDisplay = MetricDisplay.AUTO,
    /** Metric only: what lengths are rounded to. */
    val metricResolution: MetricResolution = MetricResolution.MILLIMETER,
    /** Imperial only: `10' 4"` or drawing-style `10'-4"`. */
    val feetInchStyle: FeetInchStyle = FeetInchStyle.SPACE,
    /** Where "Save to project" goes by default. */
    val activeProjectId: String? = null,
    val activeRunId: String? = null,
    val company: CompanyProfile = CompanyProfile(),
)

class SettingsRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = state.asStateFlow()

    fun setThemeMode(mode: ThemeMode) = save { it.copy(themeMode = mode) }

    fun setAnimations(enabled: Boolean) = save { it.copy(animations = enabled) }

    fun setCompany(company: CompanyProfile) = save { it.copy(company = company) }

    /** A tool's own settings store, e.g. `module("foot-inch")`. */
    fun module(toolId: String): ModuleSettings = synchronized(modules) {
        require(isValidToolId(toolId)) { "Invalid tool id." }
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
            put(KEY_UNITS, s.unitSystem.name)
            put(KEY_METRIC_DISPLAY, s.metricDisplay.name)
            put(KEY_METRIC_RESOLUTION, s.metricResolution.name)
            put(KEY_FT_IN_STYLE, s.feetInchStyle.name)
            s.activeProjectId?.let { put(KEY_PROJECT, it) }
            s.activeRunId?.let { put(KEY_RUN, it) }
            put(KEY_CO_NAME, s.company.name)
            put(KEY_CO_PREPARED, s.company.preparedBy)
            put(KEY_CO_LICENSE, s.company.license)
            put(KEY_CO_PHONE, s.company.phone)
            put(KEY_CO_EMAIL, s.company.email)
            put(KEY_CO_ADDRESS, s.company.address)
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
            unitSystem = str(KEY_UNITS)?.let { n -> UnitSystem.entries.firstOrNull { it.name == n } } ?: current.unitSystem,
            metricDisplay = str(KEY_METRIC_DISPLAY)?.let { n -> MetricDisplay.entries.firstOrNull { it.name == n } } ?: current.metricDisplay,
            metricResolution = str(KEY_METRIC_RESOLUTION)?.let { n -> MetricResolution.entries.firstOrNull { it.name == n } } ?: current.metricResolution,
            feetInchStyle = str(KEY_FT_IN_STYLE)?.let { n -> FeetInchStyle.entries.firstOrNull { it.name == n } } ?: current.feetInchStyle,
            activeProjectId = str(KEY_PROJECT) ?: current.activeProjectId,
            activeRunId = str(KEY_RUN) ?: current.activeRunId,
            company = CompanyProfile(
                name = str(KEY_CO_NAME) ?: current.company.name,
                preparedBy = str(KEY_CO_PREPARED) ?: current.company.preparedBy,
                license = str(KEY_CO_LICENSE) ?: current.company.license,
                phone = str(KEY_CO_PHONE) ?: current.company.phone,
                email = str(KEY_CO_EMAIL) ?: current.company.email,
                address = str(KEY_CO_ADDRESS) ?: current.company.address,
            ),
        )
    }

    /** Every tool's settings that have any values, keyed by tool id. */
    fun exportToolSettings(): Map<String, JsonObject> =
        prefs.getStringSet(KEY_MODULES, emptySet()).orEmpty().sorted()
            .associateWith { module(it).exportJson() }
            .filterValues { it.isNotEmpty() }

    fun importToolSettings(values: Map<String, JsonObject>, replace: Boolean) {
        if (replace) {
            val known = prefs.getStringSet(KEY_MODULES, emptySet()).orEmpty().filter(::isValidToolId)
            known.filterNot { it in values }.forEach { toolId ->
                module(toolId).importJson(JsonObject(emptyMap()), replace = true)
            }
        }
        values.forEach { (toolId, json) ->
            if (isValidToolId(toolId)) module(toolId).importJson(json, replace)
        }
    }

    fun setPrecision(precision: Precision) = save { it.copy(precision = precision) }

    fun setLengthDisplay(display: LengthDisplay) = save { it.copy(lengthDisplay = display) }

    fun setUnitSystem(system: UnitSystem) = save { it.copy(unitSystem = system) }

    fun setMetricDisplay(display: MetricDisplay) = save { it.copy(metricDisplay = display) }

    fun setMetricResolution(resolution: MetricResolution) = save { it.copy(metricResolution = resolution) }

    fun setFeetInchStyle(style: FeetInchStyle) = save { it.copy(feetInchStyle = style) }

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
            putString(KEY_UNITS, s.unitSystem.name)
            putString(KEY_METRIC_DISPLAY, s.metricDisplay.name)
            putString(KEY_METRIC_RESOLUTION, s.metricResolution.name)
            putString(KEY_FT_IN_STYLE, s.feetInchStyle.name)
            putString(KEY_PROJECT, s.activeProjectId)
            putString(KEY_RUN, s.activeRunId)
            putString(KEY_CO_NAME, s.company.name)
            putString(KEY_CO_PREPARED, s.company.preparedBy)
            putString(KEY_CO_LICENSE, s.company.license)
            putString(KEY_CO_PHONE, s.company.phone)
            putString(KEY_CO_EMAIL, s.company.email)
            putString(KEY_CO_ADDRESS, s.company.address)
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
        unitSystem = prefs.getString(KEY_UNITS, null)?.let { n -> UnitSystem.entries.firstOrNull { it.name == n } } ?: UnitSystem.IMPERIAL,
        metricDisplay = prefs.getString(KEY_METRIC_DISPLAY, null)?.let { n -> MetricDisplay.entries.firstOrNull { it.name == n } } ?: MetricDisplay.AUTO,
        metricResolution = prefs.getString(KEY_METRIC_RESOLUTION, null)?.let { n -> MetricResolution.entries.firstOrNull { it.name == n } }
            ?: MetricResolution.MILLIMETER,
        feetInchStyle = prefs.getString(KEY_FT_IN_STYLE, null)?.let { n -> FeetInchStyle.entries.firstOrNull { it.name == n } } ?: FeetInchStyle.SPACE,
        activeProjectId = prefs.getString(KEY_PROJECT, null),
        activeRunId = prefs.getString(KEY_RUN, null),
        company = CompanyProfile(
            name = prefs.getString(KEY_CO_NAME, null).orEmpty(),
            preparedBy = prefs.getString(KEY_CO_PREPARED, null).orEmpty(),
            license = prefs.getString(KEY_CO_LICENSE, null).orEmpty(),
            phone = prefs.getString(KEY_CO_PHONE, null).orEmpty(),
            email = prefs.getString(KEY_CO_EMAIL, null).orEmpty(),
            address = prefs.getString(KEY_CO_ADDRESS, null).orEmpty(),
        ),
    )

    internal companion object {
        fun validateBackup(settings: JsonObject?, toolSettings: Map<String, JsonObject>) {
            val appKeys = listOf(
                KEY_THEME, KEY_ANIMATIONS, KEY_PRECISION, KEY_DISPLAY, KEY_UNITS, KEY_METRIC_DISPLAY, KEY_METRIC_RESOLUTION, KEY_FT_IN_STYLE, KEY_PROJECT, KEY_RUN,
                KEY_CO_NAME, KEY_CO_PREPARED, KEY_CO_LICENSE, KEY_CO_PHONE, KEY_CO_EMAIL, KEY_CO_ADDRESS,
            )
            val badKey = appKeys.firstOrNull { settings?.get(it)?.let { value -> value !is JsonPrimitive } == true }
            if (badKey != null) throw ProjectFormatException("The backup has a damaged app setting: $badKey.")
            toolSettings.forEach { (tool, values) ->
                if (!isValidToolId(tool)) throw ProjectFormatException("The backup has an invalid tool id: $tool.")
                values.forEach { (key, entry) ->
                    val fields = entry as? JsonObject
                    if (fields?.get("type") !is JsonPrimitive || fields["value"] !is JsonPrimitive) {
                        throw ProjectFormatException("The backup has a damaged setting for $tool: $key.")
                    }
                }
            }
        }

        internal fun isValidToolId(id: String): Boolean =
            id.length in 1..40 && id.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }

        const val KEY_THEME = "theme"
        const val KEY_ANIMATIONS = "animations"
        const val KEY_PRECISION = "precision"
        const val KEY_DISPLAY = "length_display"
        const val KEY_UNITS = "unit_system"
        const val KEY_METRIC_DISPLAY = "metric_display"
        const val KEY_METRIC_RESOLUTION = "metric_resolution"
        const val KEY_FT_IN_STYLE = "feet_inch_style"
        const val KEY_PROJECT = "active_project"
        const val KEY_RUN = "active_run"
        const val KEY_MODULES = "module_ids"
        const val KEY_CO_NAME = "company_name"
        const val KEY_CO_PREPARED = "company_prepared_by"
        const val KEY_CO_LICENSE = "company_license"
        const val KEY_CO_PHONE = "company_phone"
        const val KEY_CO_EMAIL = "company_email"
        const val KEY_CO_ADDRESS = "company_address"
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

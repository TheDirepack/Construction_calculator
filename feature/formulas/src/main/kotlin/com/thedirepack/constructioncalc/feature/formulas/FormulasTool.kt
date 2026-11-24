package com.thedirepack.constructioncalc.feature.formulas

import androidx.compose.runtime.Composable
import com.thedirepack.constructioncalc.core.data.ModuleSettings
import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeEdition
import com.thedirepack.constructioncalc.core.electrical.code.CodeProfile
import com.thedirepack.constructioncalc.core.electrical.code.CodeRegions
import com.thedirepack.constructioncalc.core.electrical.formula.Formula
import com.thedirepack.constructioncalc.core.electrical.formula.FormulaCatalog
import com.thedirepack.constructioncalc.core.model.ProjectItem
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.tool.ToolCategory
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.core.ui.tool.ToolIds
import com.thedirepack.constructioncalc.core.ui.tool.ToolModule

/** Solves any of the formulas in [FormulaCatalog] for whatever is left blank. */
object FormulasTool : ToolModule {
    const val ID = ToolIds.FORMULAS

    override val id = ID
    override val title = "Electrical Formulas"
    override val summary = "Ohm's law, power, motors, transformers: solve for any unknown"
    override val category = ToolCategory.ELECTRICAL
    override val icon = R.drawable.ic_function

    @Composable
    override fun Content(host: ToolHost) = FormulasScreen(host)

    override fun describe(item: ProjectItem): String? = item.results.take(3).joinToString(" · ") { "${it.label} ${it.text}" }.ifBlank { null }
}

/** Optional per-tool code edition and regional amendment override (null fields inherit the app setting). */
internal data class FormulasCodeOverride(
    val editionName: String? = null,
    val regionId: String? = null,
) {
    val isInherited: Boolean get() = editionName == null && regionId == null
}

internal object FormulasCodeProfiles {
    const val INHERIT_LABEL = "Inherit app setting"

    fun resolve(
        override: FormulasCodeOverride,
        baseProfile: CodeProfile = Code.globalProfile,
    ): CodeProfile {
        val edition = override.editionName?.let { name ->
            CodeEdition.entries.firstOrNull { it.name == name && it.isImplemented }
        } ?: baseProfile.edition
        val regionId = override.regionId?.takeIf { it.isNotBlank() && CodeRegions.find(it) != null }
            ?: baseProfile.regionId
        val custom = if (override.regionId == null || override.regionId == baseProfile.regionId) {
            baseProfile.customAmendment
        } else {
            null
        }
        return CodeProfile(edition = edition, regionId = regionId, customAmendment = custom)
    }

    fun resolveOverrideOrNull(
        override: FormulasCodeOverride,
        baseProfile: CodeProfile = Code.globalProfile,
    ): CodeProfile? = if (override.isInherited) null else resolve(override, baseProfile)

    fun chipLabel(profile: CodeProfile, isOverride: Boolean = false): String {
        val region = CodeRegions.getOrDefault(profile.regionId)
        val regionText = if (region.id == CodeRegions.STANDARD_ID) "Standard" else region.shortLabel
        val base = "${profile.edition.label} · $regionText"
        return if (isOverride) "$base · Override" else base
    }
}

internal inline fun <T> withOptionalCodeProfile(profile: CodeProfile?, block: () -> T): T =
    if (profile != null) Code.withProfile(profile, block) else block()

/** Key-value backend for [FormulasStore] so persistence is directly unit-testable on the JVM. */
internal interface FormulasPrefs {
    fun getString(key: String, default: String): String
    fun setString(key: String, value: String)
    fun remove(key: String)
}

private class ModuleFormulasPrefs(private val settings: ModuleSettings) : FormulasPrefs {
    override fun getString(key: String, default: String): String = settings.getString(key, default)
    override fun setString(key: String, value: String) = settings.setString(key, value)
    override fun remove(key: String) = settings.remove(key)
}

/** Favorite formulas and optional NEC edition/region override persisted in `settings.module(FormulasTool.ID)`. */
internal class FormulasStore internal constructor(private val prefs: FormulasPrefs) {
    constructor(settings: ModuleSettings) : this(ModuleFormulasPrefs(settings))

    fun favorites(): List<String> =
        prefs.getString(KEY_FAVORITES, "")
            .split('\n')
            .filter { it.isNotBlank() && FormulaCatalog.find(it) != null }

    fun isFavorite(formulaId: String): Boolean = formulaId in favorites()

    fun toggleFavorite(formulaId: String) {
        if (FormulaCatalog.find(formulaId) == null) return
        val now = favorites()
        val updated = if (formulaId in now) now - formulaId else now + formulaId
        prefs.setString(KEY_FAVORITES, updated.joinToString("\n"))
    }

    fun codeOverride(): FormulasCodeOverride {
        val edition = prefs.getString(KEY_CODE_EDITION_OVERRIDE, "").takeIf { raw ->
            raw.isNotBlank() && CodeEdition.entries.any { it.name == raw && it.isImplemented }
        }
        val region = prefs.getString(KEY_CODE_REGION_OVERRIDE, "").takeIf { raw ->
            raw.isNotBlank() && CodeRegions.find(raw) != null
        }
        return FormulasCodeOverride(editionName = edition, regionId = region)
    }

    fun setCodeOverride(editionName: String?, regionId: String?) {
        val validEdition = editionName?.takeIf { raw ->
            CodeEdition.entries.any { it.name == raw && it.isImplemented }
        }
        val validRegion = regionId?.takeIf { CodeRegions.find(it) != null }
        if (validEdition == null) prefs.remove(KEY_CODE_EDITION_OVERRIDE) else prefs.setString(KEY_CODE_EDITION_OVERRIDE, validEdition)
        if (validRegion == null) prefs.remove(KEY_CODE_REGION_OVERRIDE) else prefs.setString(KEY_CODE_REGION_OVERRIDE, validRegion)
    }

    fun clearCodeOverride() {
        prefs.remove(KEY_CODE_EDITION_OVERRIDE)
        prefs.remove(KEY_CODE_REGION_OVERRIDE)
    }

    companion object {
        const val KEY_FAVORITES = "favorites"
        const val KEY_CODE_EDITION_OVERRIDE = "code_edition_override"
        const val KEY_CODE_REGION_OVERRIDE = "code_region_override"
    }
}

/** Search and favorite filtering over [FormulaCatalog.all]. */
internal object FormulasCatalogSearch {
    val ordered: List<Formula> by lazy { FormulaCatalog.all.sortedBy { it.group.ordinal } }

    fun filter(
        query: String,
        favoritesOnly: Boolean = false,
        favoriteIds: Set<String> = emptySet(),
    ): List<Formula> {
        val pool = if (favoritesOnly) ordered.filter { it.id in favoriteIds } else ordered
        val words = searchWords(query)
        if (words.isEmpty()) return pool
        return pool
            .map { formula -> formula to score(formula, words) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun score(formula: Formula, words: List<String>): Int {
        val haystack = searchText(formula)
        if (!words.all { it in haystack }) return 0
        val title = normalizeSearchText(formula.title)
        return 1 + words.count { it in title } * 2 + if (title.startsWith(words.first())) 3 else 0
    }

    private fun searchText(formula: Formula): String {
        val raw = (
            listOf(formula.id, formula.title, formula.summary, formula.group.label) +
                formula.keywords +
                formula.variables.flatMap { listOf(it.name, it.symbol) } +
                formula.relations.map { it.expression } +
                formula.notes
            ).joinToString(" ")
        return normalizeSearchText(raw)
    }

    internal fun searchWords(query: String): List<String> =
        query.lowercase().split(Regex("[\\s,]+")).map { it.trim() }.filter { it.isNotEmpty() }

    private fun normalizeSearchText(text: String): String {
        val lower = text.lowercase()
        val noApostrophes = lower.replace("'", "").replace("\u2019", "")
        val spaced = noApostrophes.replace("-", " ")
        val phaseAliases = buildString {
            if ("three-phase" in lower || "three phase" in spaced) append(" 3-phase 3 phase 3phase")
            if ("single-phase" in lower || "single phase" in spaced) append(" 1-phase 1 phase 1phase")
        }
        return "$lower $noApostrophes $spaced$phaseAliases"
    }
}
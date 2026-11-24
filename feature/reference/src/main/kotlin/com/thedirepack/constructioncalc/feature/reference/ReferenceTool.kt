package com.thedirepack.constructioncalc.feature.reference

import androidx.compose.runtime.Composable
import com.thedirepack.constructioncalc.core.data.ModuleSettings
import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeEdition
import com.thedirepack.constructioncalc.core.electrical.code.CodeProfile
import com.thedirepack.constructioncalc.core.electrical.code.CodeRegions
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.tool.ToolCategory
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.core.ui.tool.ToolIds
import com.thedirepack.constructioncalc.core.ui.tool.ToolModule
import com.thedirepack.constructioncalc.feature.reference.content.ReferenceCatalog
import com.thedirepack.constructioncalc.feature.reference.content.Topics

/** Searchable code tables, the formula sheet and field references. */
object ReferenceTool : ToolModule {
    const val ID = ToolIds.REFERENCE

    override val id = ID
    override val title = "Reference"
    override val summary = "Code tables, formula sheet, bending, plugs and more"
    override val category = ToolCategory.REFERENCE
    override val icon = R.drawable.ic_book

    @Composable
    override fun Content(host: ToolHost) = ReferenceScreen(host)

    /** For the app shell: whether a tool's top bar gets a Reference action. */
    fun hasEntriesFor(toolId: String): Boolean = toolId != ID && ReferenceCatalog.hasEntriesFor(toolId)

    /** The topic that lists a tool's entries. */
    fun topicFor(toolId: String): String = Topics.forTool(toolId)
}

/** Optional per-tool code edition and regional amendment override (null fields inherit the app setting). */
internal data class ReferenceCodeOverride(
    val editionName: String? = null,
    val regionId: String? = null,
) {
    val isInherited: Boolean get() = editionName == null && regionId == null
}

internal object ReferenceCodeProfiles {
    const val INHERIT_LABEL = "Inherit app setting"

    fun resolve(
        override: ReferenceCodeOverride,
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
        override: ReferenceCodeOverride,
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

/** Key-value backend for [ReferenceStore] so persistence is directly unit-testable on the JVM. */
internal interface ReferencePrefs {
    fun getString(key: String, default: String): String
    fun setString(key: String, value: String)
    fun remove(key: String)
}

private class ModuleReferencePrefs(private val settings: ModuleSettings) : ReferencePrefs {
    override fun getString(key: String, default: String): String = settings.getString(key, default)
    override fun setString(key: String, value: String) = settings.setString(key, value)
    override fun remove(key: String) = settings.remove(key)
}

/** Favorites, recents, and optional NEC edition/region override, kept with the tool's settings (so they're backed up too). */
internal class ReferenceStore internal constructor(private val prefs: ReferencePrefs) {
    constructor(settings: ModuleSettings) : this(ModuleReferencePrefs(settings))

    fun favorites(): List<String> = read(KEY_FAVORITES)

    fun recents(): List<String> = read(KEY_RECENTS)

    fun isFavorite(id: String) = id in favorites()

    fun toggleFavorite(id: String) {
        if (ReferenceCatalog.find(id) == null) return
        val now = favorites()
        write(KEY_FAVORITES, if (id in now) now - id else now + id)
    }

    fun opened(id: String) = write(KEY_RECENTS, (listOf(id) + recents().filter { it != id }).take(MAX_RECENTS))

    fun codeOverride(): ReferenceCodeOverride {
        val edition = prefs.getString(KEY_CODE_EDITION_OVERRIDE, "").takeIf { raw ->
            raw.isNotBlank() && CodeEdition.entries.any { it.name == raw && it.isImplemented }
        }
        val region = prefs.getString(KEY_CODE_REGION_OVERRIDE, "").takeIf { raw ->
            raw.isNotBlank() && CodeRegions.find(raw) != null
        }
        return ReferenceCodeOverride(editionName = edition, regionId = region)
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

    private fun read(key: String) = prefs.getString(key, "").split('\n').filter { it.isNotBlank() && ReferenceCatalog.find(it) != null }

    private fun write(key: String, ids: List<String>) = prefs.setString(key, ids.joinToString("\n"))

    companion object {
        const val KEY_FAVORITES = "favorites"
        const val KEY_RECENTS = "recents"
        const val KEY_CODE_EDITION_OVERRIDE = "code_edition_override"
        const val KEY_CODE_REGION_OVERRIDE = "code_region_override"
        const val MAX_RECENTS = 6
    }
}

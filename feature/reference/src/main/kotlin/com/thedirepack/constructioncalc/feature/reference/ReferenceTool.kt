package com.thedirepack.constructioncalc.feature.reference

import androidx.compose.runtime.Composable
import com.thedirepack.constructioncalc.core.data.ModuleSettings
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

/** Favorites and recently opened entries, kept with the tool's settings (so they're backed up too). */
internal class ReferenceStore(private val settings: ModuleSettings) {
    fun favorites(): List<String> = read(KEY_FAVORITES)

    fun recents(): List<String> = read(KEY_RECENTS)

    fun isFavorite(id: String) = id in favorites()

    fun toggleFavorite(id: String) {
        val now = favorites()
        write(KEY_FAVORITES, if (id in now) now - id else now + id)
    }

    fun opened(id: String) = write(KEY_RECENTS, (listOf(id) + recents().filter { it != id }).take(MAX_RECENTS))

    private fun read(key: String) = settings.getString(key, "").split('\n').filter { it.isNotBlank() && ReferenceCatalog.find(it) != null }

    private fun write(key: String, ids: List<String>) = settings.setString(key, ids.joinToString("\n"))

    companion object {
        const val KEY_FAVORITES = "favorites"
        const val KEY_RECENTS = "recents"
        const val MAX_RECENTS = 6
    }
}

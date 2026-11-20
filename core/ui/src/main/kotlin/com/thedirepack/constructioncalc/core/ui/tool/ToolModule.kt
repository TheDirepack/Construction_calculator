package com.thedirepack.constructioncalc.core.ui.tool

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.thedirepack.constructioncalc.core.data.AppServices
import com.thedirepack.constructioncalc.core.data.ToolBackup
import com.thedirepack.constructioncalc.core.model.ProjectItem
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.model.ToolTarget
import kotlinx.serialization.json.JsonObject

/** Groups tools in the drawer. */
enum class ToolCategory(val label: String) {
    PROJECTS("Projects"),
    MEASURE("Measure & layout"),
    ELECTRICAL("Electrical"),
    REFERENCE("Reference"),
    WATER("Well & water"),
}

/**
 * One section of the app. To add a tool: create a `:feature:<name>` module with the
 * `cc.android.feature` plugin, implement this interface as an `object`, and add it to the
 * registry in `:app` (`ToolRegistry`). See docs/ARCHITECTURE.md.
 */
interface ToolModule {
    /** Stable id, saved in project files. Never change it once released. */
    val id: String
    val title: String
    /** One line for the drawer and home screen. */
    val summary: String
    val category: ToolCategory
    @get:DrawableRes val icon: Int

    /**
     * The tool's screen. Use [com.thedirepack.constructioncalc.core.ui.components.ToolScaffold]
     * for a consistent top bar. If [ToolHost.openedItem] is set, restore inputs from it.
     */
    @Composable
    fun Content(host: ToolHost)

    /**
     * Optional: a short description of a saved item from this tool, used when the item's own
     * summary is blank.
     */
    fun describe(item: ProjectItem): String? = null

    /**
     * True if this tool has its own options. They appear as this tool's page in the one app
     * Settings screen (never a separate settings menu), and the Settings action inside the tool
     * opens that page directly. Store values with `LocalAppServices.current.settings.module(id)`.
     */
    val hasSettings: Boolean get() = false

    /** This tool's page in Settings. Use the rows in `components/SettingsRows.kt`. */
    @Composable
    fun SettingsContent() {}

    /**
     * Data this tool keeps outside projects (history, custom tables…) for full backups, or null
     * if it has none. Settings-page values are backed up automatically.
     */
    fun backup(context: Context): ToolBackup? = null
}

/** What a tool can ask of the app around it. Provided by the app shell. */
interface ToolHost {
    /** The saved item this screen was opened from (to view or edit it), if any. */
    val openedItem: ProjectItem?

    /** What this screen was opened for (a project page, a circuit, a run), if anything. */
    val target: ToolTarget?

    fun openDrawer()

    /** Opens another tool, optionally with a saved item or for a project circuit or run ([ToolIds]). */
    fun openTool(toolId: String, item: ProjectItem? = null, target: ToolTarget? = null)

    /** Opens a project's page in Projects, or the project list when [projectId] is null. */
    fun openProject(projectId: String?)

    /** Goes back to the previous screen (e.g. the project the item was opened from). */
    fun back()

    /**
     * Starts the "Save to project" flow for a finished calculation. If the screen was opened
     * from a saved item, the user can update that item instead of adding a new one.
     */
    fun saveToProject(draft: ItemDraft)

    /**
     * Offers [results] to every other tool's input fields ("use a value from…"). Call it whenever the
     * results change: the shell waits until they settle (so typing "125" doesn't offer 1 and 12 too).
     * Pass [immediate] for a deliberate, finished calculation (e.g. pressing `=`).
     */
    fun publish(results: List<ResultValue>, context: String = "", immediate: Boolean = false)

    /**
     * Opens the app's Settings: this tool's page if it [ToolModule.hasSettings], otherwise the
     * main page (theme, animations, measurement display).
     */
    fun openSettings()

    fun shareText(subject: String, text: String)

    fun showMessage(message: String)
}

/** A calculation ready to be saved as a [ProjectItem]. */
data class ItemDraft(
    val toolId: String,
    val title: String,
    val inputs: JsonObject,
    val results: List<ResultValue>,
    val summary: String,
)

/** App-wide repositories, available to every tool's composables. */
val LocalAppServices = staticCompositionLocalOf<AppServices> {
    error("AppServices not provided. Wrap content in CompositionLocalProvider(LocalAppServices provides …)")
}

/** Every registered tool, provided by the app shell (for showing items from any tool). */
val LocalTools = staticCompositionLocalOf<List<ToolModule>> { emptyList() }

fun List<ToolModule>.find(toolId: String): ToolModule? = firstOrNull { it.id == toolId }

/** A tool's title, or a readable fallback for tools this app version doesn't have. */
fun List<ToolModule>.titleOf(toolId: String): String = find(toolId)?.title ?: toolId.replace('-', ' ').replaceFirstChar { it.uppercase() }

package com.thedirepack.constructioncalc.shell

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thedirepack.constructioncalc.core.data.ProjectSharing
import com.thedirepack.constructioncalc.core.model.AppFile
import com.thedirepack.constructioncalc.core.model.ProjectCodec
import com.thedirepack.constructioncalc.core.model.ProjectItem
import com.thedirepack.constructioncalc.core.model.RecentResult
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.model.ToolTarget
import com.thedirepack.constructioncalc.core.data.activeTarget
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.components.ImportFileDialog
import com.thedirepack.constructioncalc.core.ui.components.rememberAppFileOpener
import com.thedirepack.constructioncalc.core.ui.components.SaveToProjectSheet
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.tool.ItemDraft
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices
import com.thedirepack.constructioncalc.core.ui.tool.ToolCategory
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.core.ui.tool.ToolModule
import com.thedirepack.constructioncalc.feature.projects.ProjectsTool
import com.thedirepack.constructioncalc.feature.reference.ReferenceTool
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Lightweight reference to a saved [ProjectItem] for saved instance state. */
data class ItemRef(val projectId: String? = null, val itemId: String)

/** A place in the app. The shell keeps a simple back stack of these. */
sealed interface Destination {
    /** A tool, optionally showing a saved item (or its lightweight [itemRef]), or opened for a project, circuit or run. */
    data class Tool(
        val toolId: String,
        val item: ProjectItem? = null,
        val target: ToolTarget? = null,
        val itemRef: ItemRef? = null,
    ) : Destination

    /** The one Settings screen; [toolId] opens that tool's page directly. */
    data class Settings(val toolId: String? = null) : Destination
}

/** How long a tool's results must stay unchanged before they're offered to other tools. */
private const val PUBLISH_SETTLE_MS = 1_200L

private const val SEP = '\u0001'
private const val SUBSEP = '\u0002'

/**
 * A back-stack entry as a string, for saved instance state.
 * Stores lightweight `(projectId, itemId)` references instead of full [ProjectItem] JSON,
 * while keeping fallback decoding for legacy JSON-encoded entries.
 */
internal object DestinationCodec {
    fun encode(d: Destination): String = when (d) {
        is Destination.Tool -> {
            val ref = d.itemRef ?: d.item?.let { ItemRef(projectId = null, itemId = it.id) }
            val encodedRef = ref?.let { "R" + (it.projectId ?: "") + SUBSEP + it.itemId } ?: ""
            "T" + d.toolId + SEP + encodedRef + SEP + (d.target?.encode() ?: "")
        }
        is Destination.Settings -> "S" + (d.toolId ?: "")
    }

    /** Null for anything [encode] didn't produce. A damaged item or target is dropped, not the entry. */
    fun decode(s: String): Destination? = when (s.firstOrNull()) {
        'T' -> {
            val parts = s.drop(1).split(SEP)
            val toolId = parts[0].takeIf { it.isNotEmpty() } ?: return null
            val itemPart = parts.getOrNull(1).orEmpty()
            var item: ProjectItem? = null
            var itemRef: ItemRef? = null
            when {
                itemPart.startsWith("R") -> {
                    val refParts = itemPart.drop(1).split(SUBSEP)
                    val projectId = refParts.getOrNull(0)?.ifEmpty { null }
                    val itemId = refParts.getOrNull(1)?.ifEmpty { null }
                    if (itemId != null) itemRef = ItemRef(projectId, itemId)
                }
                itemPart.startsWith("{") -> {
                    // Legacy fallback: decode full ProjectItem JSON from older saved state.
                    item = runCatching { ProjectCodec.json.decodeFromString(ProjectItem.serializer(), itemPart) }.getOrNull()
                    if (item != null) itemRef = ItemRef(projectId = null, itemId = item.id)
                }
            }
            Destination.Tool(toolId, item, parts.getOrNull(2)?.let(ToolTarget::decode), itemRef)
        }
        'S' -> Destination.Settings(s.drop(1).ifEmpty { null })
        else -> null
    }
}

/** Saves the back stack across rotation and process death. */
private val StackSaver = listSaver<SnapshotStateList<Destination>, String>(
    save = { stack -> stack.map(DestinationCodec::encode) },
    restore = { saved ->
        val restored = saved.mapNotNull(DestinationCodec::decode).ifEmpty { listOf(Destination.Tool(ToolRegistry.default.id)) }
        mutableStateListOf(*restored.toTypedArray())
    },
)

@Composable
fun AppShell(imports: Flow<Result<AppFile>>) {
    val services = LocalAppServices.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val snackbar = remember { SnackbarHostState() }
    // Saveable, so rotation, a dark-mode switch or process death keep the user where they were.
    val stack = rememberSaveable(saver = StackSaver) { mutableStateListOf<Destination>(Destination.Tool(ToolRegistry.default.id)) }
    // Tools publish on every input change; only the value that settles is offered to other tools.
    val publishJobs = remember { mutableMapOf<String, Job>() }
    var pendingSave by remember { mutableStateOf<Triple<ItemDraft, ProjectItem?, ToolTarget?>?>(null) }
    var pendingImport by remember { mutableStateOf<AppFile?>(null) }

    val current = stack.last()
    fun navigateTopLevel(destination: Destination) {
        stack.clear()
        stack.add(destination)
    }
    fun push(destination: Destination) = stack.add(destination)
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun message(text: String) { scope.launch { snackbar.showSnackbar(text) } }

    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(enabled = !drawer.isOpen && stack.size > 1) { pop() }

    // The one place files are opened (Projects, Settings and "open with" all end here).
    val openFile = rememberAppFileOpener { result ->
        result.onSuccess { pendingImport = it }.onFailure { message(it.message ?: "Couldn't open that file") }
    }

    // Tell the user once if a saved project file couldn't be read (it was set aside, not deleted).
    LaunchedEffect(Unit) {
        services.projects.loaded.first { it }
        val damaged = services.projects.damaged.value
        if (damaged.isNotEmpty()) {
            message("${damaged.size} project file${if (damaged.size == 1) "" else "s"} couldn't be read and ${if (damaged.size == 1) "was" else "were"} set aside, not deleted.")
        }
    }

    LaunchedEffect(imports) {
        imports.collect { result ->
            result.onSuccess { pendingImport = it }
                .onFailure { message(it.message ?: "Couldn't open that file") }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            AppDrawer(
                current = current,
                onSelect = { destination ->
                    navigateTopLevel(destination)
                    scope.launch { drawer.close() }
                },
            )
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            // key() gives each destination its own state, so going back restores the previous screen fresh.
            key(stack.size, current) {
                when (current) {
                    is Destination.Tool -> {
                        val tool = ToolRegistry.all.firstOrNull { it.id == current.toolId } ?: ToolRegistry.default
                        val projects by services.projects.projects.collectAsStateWithLifecycle()
                        val resolvedItem = current.item ?: current.itemRef?.let { ref ->
                            if (ref.projectId != null) {
                                projects.firstOrNull { it.id == ref.projectId }?.item(ref.itemId)?.second
                            } else {
                                projects.firstNotNullOfOrNull { it.item(ref.itemId)?.second }
                            }
                        }
                        val host = remember(current, resolvedItem) {
                            ShellHost(
                                tool = tool,
                                openedItem = resolvedItem,
                                target = current.target,
                                onOpenDrawer = { scope.launch { drawer.open() } },
                                onOpenTool = { id, item, target ->
                                    val ref = item?.let {
                                        val projectId = services.projects.projects.value.firstOrNull { p -> p.item(it.id) != null }?.id
                                        ItemRef(projectId, it.id)
                                    }
                                    push(Destination.Tool(id, item, target, ref))
                                },
                                onOpenProject = { id ->
                                    val projectsDest = Destination.Tool(ProjectsTool.ID, target = id?.let { ToolTarget.OfProject(it) })
                                    // Already in Projects: show that page (or the list) in place.
                                    if (tool.id == ProjectsTool.ID) stack[stack.lastIndex] = projectsDest else push(projectsDest)
                                },
                                onBack = ::pop,
                                onSave = { pendingSave = Triple(it, resolvedItem, current.target) },
                                onPublish = { results, ctx, immediate ->
                                    publishJobs.remove(tool.id)?.cancel()
                                    val publish = {
                                        val now = services.clock()
                                        services.recents.publish(results.map { RecentResult(it, tool.id, tool.title, ctx, now) })
                                    }
                                    if (immediate) {
                                        publish()
                                    } else {
                                        publishJobs[tool.id] = scope.launch {
                                            delay(PUBLISH_SETTLE_MS)
                                            publish()
                                        }
                                    }
                                },
                                onOpenSettings = { push(Destination.Settings(tool.id.takeIf { tool.hasSettings })) },
                                onShareText = { subject, text ->
                                    try {
                                        context.startActivity(ProjectSharing.shareTextIntent(subject, text))
                                    } catch (_: ActivityNotFoundException) {
                                        message("No app available to share with")
                                    }
                                },
                                onMessage = ::message,
                                onOpenFile = openFile,
                            )
                        }
                        tool.Content(host)
                    }
                    is Destination.Settings -> SettingsScreen(
                        toolId = current.toolId,
                        canGoBack = stack.size > 1,
                        onBack = ::pop,
                        onOpenDrawer = { scope.launch { drawer.open() } },
                        onOpenToolPage = { push(Destination.Settings(it)) },
                        onOpenFile = openFile,
                        onMessage = ::message,
                    )
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp))
        }
    }

    pendingImport?.let { file ->
        ImportFileDialog(
            file = file,
            onFinished = { text, projectId ->
                pendingImport = null
                if (projectId != null) {
                    navigateTopLevel(Destination.Tool(ProjectsTool.ID, target = ToolTarget.OfProject(projectId)))
                }
                message(text)
            },
            onDismiss = { pendingImport = null },
        )
    }

    pendingSave?.let { (draft, editing, target) ->
        SaveToProjectSheet(
            draft = draft,
            editing = editing,
            target = target,
            onSaved = { text ->
                pendingSave = null
                message(text)
            },
            onDismiss = { pendingSave = null },
        )
    }
}

private class ShellHost(
    private val tool: ToolModule,
    override val openedItem: ProjectItem?,
    override val target: ToolTarget?,
    private val onOpenDrawer: () -> Unit,
    private val onOpenTool: (String, ProjectItem?, ToolTarget?) -> Unit,
    private val onOpenProject: (String?) -> Unit,
    private val onBack: () -> Unit,
    private val onSave: (ItemDraft) -> Unit,
    private val onPublish: (List<ResultValue>, String, Boolean) -> Unit,
    private val onOpenSettings: () -> Unit,
    private val onShareText: (String, String) -> Unit,
    private val onMessage: (String) -> Unit,
    private val onOpenFile: () -> Unit,
) : ToolHost {
    override fun openDrawer() = onOpenDrawer()
    override fun openTool(toolId: String, item: ProjectItem?, target: ToolTarget?) = onOpenTool(toolId, item, target)
    override fun openProject(projectId: String?) = onOpenProject(projectId)
    override fun back() = onBack()
    override fun saveToProject(draft: ItemDraft) = onSave(draft)
    override fun publish(results: List<ResultValue>, context: String, immediate: Boolean) = onPublish(results, context, immediate)
    override fun openSettings() = onOpenSettings()
    override fun shareText(subject: String, text: String) = onShareText(subject, text)
    override fun showMessage(message: String) = onMessage(message)
    override fun openFile() = onOpenFile()
    override val hasReference: Boolean get() = ReferenceTool.hasEntriesFor(tool.id)
    override fun openReference(topic: String?) =
        onOpenTool(ReferenceTool.ID, null, ToolTarget.OfTopic(topic ?: ReferenceTool.topicFor(tool.id)))
}

@Composable
private fun AppDrawer(current: Destination, onSelect: (Destination) -> Unit) {
    val services = LocalAppServices.current
    val settings = rememberAppSettings()
    val projects by services.projects.projects.collectAsStateWithLifecycle()
    val active = settings.activeTarget(projects)
    val currentToolId = (current as? Destination.Tool)?.toolId

    ModalDrawerSheet {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Spacer(Modifier.height(20.dp))
            Text("Construction Calc", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
            Text(
                active?.let { (p, run) ->
                    "Saving to: ${p.name}" + (run?.takeIf { p.runs.size > 1 }?.let { " › ${it.name}" } ?: "")
                } ?: "No active project",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            ToolCategory.entries.forEach { category ->
                val tools = ToolRegistry.all.filter { it.category == category }
                if (tools.isEmpty()) return@forEach
                Spacer(Modifier.height(8.dp))
                if (category != ToolCategory.PROJECTS) {
                    Text(
                        category.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                tools.forEach { tool ->
                    NavigationDrawerItem(
                        label = { Text(tool.title) },
                        icon = { Icon(painterResource(tool.icon), contentDescription = null) },
                        selected = tool.id == currentToolId,
                        onClick = { onSelect(Destination.Tool(tool.id)) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 16.dp))
            NavigationDrawerItem(
                label = { Text("Settings") },
                icon = { Icon(painterResource(R.drawable.ic_tune), contentDescription = null) },
                selected = current is Destination.Settings,
                onClick = { onSelect(Destination.Settings()) },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

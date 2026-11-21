package com.thedirepack.constructioncalc.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

/**
 * A job. Holds [runs] (a circuit run, a wall, a room…), which hold [ProjectItem]s: saved results
 * from any tool. [ProjectCodec] stores the metadata and references to any original drawing PDFs.
 */
@Serializable
data class Project(
    val id: String = newId(),
    val name: String,
    val note: String = "",
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val runs: List<Run> = emptyList(),
    /**
     * Archived projects keep all their data but are read-only until unarchived: the app refuses
     * edits to them (see `ProjectRepository.edit`) and hides them from save targets.
     */
    val archived: Boolean = false,
    val archivedAt: Long? = null,
    /** Electrical design: panels and their circuits, circuit groups, and design defaults. */
    val panels: List<Panel> = emptyList(),
    val groups: List<CircuitGroup> = emptyList(),
    val design: DesignDefaults = DesignDefaults(),
    val drawings: List<PlanDrawing> = emptyList(),
) {
    fun panel(panelId: String): Panel? = panels.firstOrNull { it.id == panelId }

    fun updatePanel(panelId: String, now: Long, change: (Panel) -> Panel) =
        copy(panels = panels.map { if (it.id == panelId) change(it) else it }, updatedAt = now)

    fun addPanel(panel: Panel, now: Long) = copy(panels = panels + panel, updatedAt = now)

    fun removePanel(panelId: String, now: Long) = copy(
        panels = panels.filterNot { it.id == panelId }.map { p ->
            if (p.fedFrom?.panelId == panelId) p.copy(fedFrom = null) else p
        },
        updatedAt = now,
    )
    fun run(runId: String): Run? = runs.firstOrNull { it.id == runId }

    fun item(itemId: String): Pair<Run, ProjectItem>? =
        runs.firstNotNullOfOrNull { run -> run.items.firstOrNull { it.id == itemId }?.let { run to it } }

    val itemCount: Int get() = runs.sumOf { it.items.size }

    /** Every result in the project, newest item first, with where it came from. */
    fun allResults(): List<SourcedResult> = runs.flatMap { run ->
        run.items.flatMap { item -> item.results.map { SourcedResult(it, item, run) } }
    }.sortedByDescending { it.item.updatedAt }

    fun archive(now: Long) = copy(archived = true, archivedAt = now, updatedAt = now)

    fun unarchive(now: Long) = copy(archived = false, archivedAt = null, updatedAt = now)

    // --- Edits. All return a new Project with [updatedAt] = [now]. ---

    fun renamed(name: String, now: Long) = copy(name = name, updatedAt = now)

    fun withNote(note: String, now: Long) = copy(note = note, updatedAt = now)

    fun addRun(run: Run, now: Long) = copy(runs = runs + run, updatedAt = now)

    fun updateRun(runId: String, now: Long, change: (Run) -> Run) =
        copy(runs = runs.map { if (it.id == runId) change(it) else it }, updatedAt = now)

    /** Removes a run and unassigns it from every circuit that passed through it. */
    fun removeRun(runId: String, now: Long) = copy(
        runs = runs.filterNot { it.id == runId },
        panels = panels.map { p ->
            p.copy(circuits = p.circuits.map { c -> if (runId in c.runIds) c.copy(runIds = c.runIds - runId) else c })
        },
        updatedAt = now,
    )

    fun moveRun(from: Int, to: Int, now: Long) = copy(runs = runs.moved(from, to), updatedAt = now)

    fun addItem(runId: String, item: ProjectItem, now: Long) =
        updateRun(runId, now) { it.copy(items = it.items + item) }

    fun updateItem(itemId: String, now: Long, change: (ProjectItem) -> ProjectItem) = copy(
        runs = runs.map { run ->
            run.copy(items = run.items.map { if (it.id == itemId) change(it).copy(updatedAt = now) else it })
        },
        updatedAt = now,
    )

    /** Removes an item, and any bends it added to its run's path (keeping the run length in step). */
    fun removeItem(itemId: String, now: Long): Project {
        val owner = item(itemId)?.first
        val removed = copy(
            runs = runs.map { run -> run.copy(items = run.items.filterNot { it.id == itemId }) },
            updatedAt = now,
        )
        if (owner == null || !RunPath.hasSource(owner.path, itemId)) return removed
        return removed.withRunPath(owner.id, RunPath.removeSource(owner.path, itemId), now)
    }

    /** Moves an item to another run; bends it added to its old run's path leave that path. */
    fun moveItem(itemId: String, toRunId: String, toIndex: Int, now: Long): Project {
        val (from, item) = item(itemId) ?: return this
        if (from.id != toRunId && RunPath.hasSource(from.path, itemId)) {
            return withRunPath(from.id, RunPath.removeSource(from.path, itemId), now).moveItem(itemId, toRunId, toIndex, now)
        }
        val without = runs.map { run -> run.copy(items = run.items.filterNot { it.id == itemId }) }
        return copy(
            runs = without.map { run ->
                if (run.id != toRunId) run else {
                    val items = run.items.toMutableList()
                    items.add(toIndex.coerceIn(0, items.size), item)
                    run.copy(items = items)
                }
            },
            updatedAt = now,
        )
    }

    /** A copy with fresh ids everywhere, e.g. when importing a project that already exists. */
    fun duplicated(name: String = this.name, now: Long) = copy(
        id = newId(),
        name = name,
        createdAt = now,
        updatedAt = now,
        archived = false,
        archivedAt = null,
        runs = runs.map { run ->
            val ids = run.items.associate { it.id to newId() }
            run.copy(
                items = run.items.map { it.copy(id = ids.getValue(it.id)) },
                // Path bends follow their source item to its new id.
                path = run.path.map { s -> if (s is PathSegment.Bend && s.sourceItemId != null) s.copy(sourceItemId = ids[s.sourceItemId]) else s },
            )
        },
        // Run, panel and circuit ids are kept: they're only referenced inside this project.
    )

    companion object {
        /** A new project with one empty run, so there's always somewhere to save to. */
        fun create(name: String, now: Long, firstRunName: String = "General") =
            Project(name = name, createdAt = now, runs = listOf(Run(name = firstRunName)))
    }
}

/**
 * A run inside a project. Usually a conduit run ([raceway] set) whose circuits, path, bends, fill and
 * pull results all live together; or just a group of saved results (a wall, a room) when
 * [raceway] is null.
 */
@Serializable
data class Run(
    val id: String = newId(),
    val name: String,
    val note: String = "",
    val items: List<ProjectItem> = emptyList(),
    val raceway: Raceway? = null,
    /**
     * The conduit's route from start to end: straights, bends and pull points. Shared by Conduit
     * Bending, Panels and the Wire Pull Planner. When it isn't empty, [Raceway.length] is its
     * routed length (see [withRunPath]).
     */
    val path: List<PathSegment> = emptyList(),
    /** Pull settings for the Wire Pull Planner (friction, tensions, ratings, extra cables); null until set. */
    val pull: PullSetup? = null,
) {
    init {
        require(path.size <= RunPath.MAX_SEGMENTS) { "Run path has too many segments" }
    }
}

/**
 * A saved result from a tool.
 *
 * @property toolId which tool made it (see `ToolModule.id`); used to reopen it in that tool.
 * @property inputs the tool's own inputs, in whatever shape that tool defines. Tools must
 *   tolerate missing or unknown fields so files from other app versions still open.
 * @property results the outputs, readable by any tool and by people.
 * @property summary a few human-readable lines describing the calculation, used in shared text
 *   and by app versions that don't know [toolId].
 */
@Serializable
data class ProjectItem(
    val id: String = newId(),
    val toolId: String,
    val title: String,
    val note: String = "",
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val inputs: JsonObject = JsonObject(emptyMap()),
    val results: List<ResultValue> = emptyList(),
    val summary: String = "",
)

/** A result plus the item and run it lives in. */
data class SourcedResult(val value: ResultValue, val item: ProjectItem, val run: Run)

private fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from !in indices) return this
    val list = toMutableList()
    val element = list.removeAt(from)
    list.add(to.coerceIn(0, list.size), element)
    return list
}

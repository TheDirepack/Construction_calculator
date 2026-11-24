package com.thedirepack.constructioncalc.core.data

import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.ProjectItem
import com.thedirepack.constructioncalc.core.model.ProjectMerge
import com.thedirepack.constructioncalc.core.model.ProjectMergeMode
import com.thedirepack.constructioncalc.core.model.Run
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * All projects, kept in memory and written through to [store]. Every edit goes through [edit],
 * which applies a pure function from :core:model and persists the result.
 *
 * Writes are "sync this project id" jobs: each one takes the write lock and then saves whatever the
 * in-memory state holds for that id *at that moment* (or deletes the file if the project is gone).
 * Jobs may run in any order, but the last one always writes the latest state, so a slow write can
 * never overwrite a newer one, and delete-then-restore can never leave the file deleted.
 */
class ProjectRepository(
    private val store: ProjectStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    /** Where file reads and writes run (tests pass one that reorders tasks). */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val state = MutableStateFlow<List<Project>>(emptyList())
    private val _loaded = MutableStateFlow(false)
    private val writeLock = Mutex()
    private val pendingWrites = MutableStateFlow(0)

    /** Most recently updated first. */
    val projects: StateFlow<List<Project>> = state.asStateFlow()
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _damaged = MutableStateFlow<List<String>>(emptyList())

    /** Project files that couldn't be read when the app started (set aside by the store). */
    val damaged: StateFlow<List<String>> = _damaged.asStateFlow()

    init {
        scope.launch(io) {
            writeLock.withLock {
                val all = store.loadAll()
                _damaged.value = store.damaged()
                state.update { current -> (current + all).distinctBy { it.id }.sortedByDescending { it.updatedAt } }
                _loaded.value = true
            }
        }
    }

    fun get(projectId: String?): Project? = projectId?.let { id -> state.value.firstOrNull { it.id == id } }

    fun create(name: String): Project {
        val project = Project.create(name.trim().ifEmpty { "Untitled project" }, clock())
        put(project)
        return project
    }

    /**
     * Applies [change] (given the current time) to a project and saves it. Archived projects are
     * read-only: the change is ignored and the project is returned unchanged.
     */
    fun edit(projectId: String, change: (Project, Long) -> Project): Project? {
        val current = get(projectId) ?: return null
        if (current.archived) return current
        val updated = change(current, clock())
        if (updated != current) put(updated)
        return updated
    }

    /** Archives or unarchives a project (the only change allowed to an archived project). */
    fun setArchived(projectId: String, archived: Boolean): Project? {
        val current = get(projectId) ?: return null
        if (current.archived == archived) return current
        val now = clock()
        val updated = if (archived) current.archive(now) else current.unarchive(now)
        put(updated)
        return updated
    }

    /** Deletes a project. Archived projects must be unarchived first; returns false if refused. */
    fun delete(projectId: String): Boolean {
        if (get(projectId)?.archived == true) return false
        deleteUnchecked(projectId)
        return true
    }

    private fun deleteUnchecked(projectId: String) {
        state.update { list -> list.filterNot { it.id == projectId } }
        sync(projectId)
    }

    /**
     * Adds a project read from a shared file. If a project with the same id is already here, the
     * import is kept as a separate copy so nothing is overwritten.
     */
    fun import(project: Project): Project {
        val now = clock()
        val incoming = if (get(project.id) != null) {
            project.duplicated(name = "${project.name} (imported)", now = now)
        } else {
            project
        }
        put(incoming)
        return incoming
    }

    /** Writes [project] as-is, replacing any project with the same id (e.g. "Replace mine" on import). */
    fun replace(project: Project) = put(project)

    /**
     * Imports [project] as a new non-destructive revision with fresh IDs and an incremented
     * `(Rev N)` suffix that does not collide with existing project names.
     */
    fun importAsRevision(project: Project): Project {
        val now = clock()
        val existingNames = state.value.mapTo(mutableSetOf()) { it.name }
        val revision = project.asNewRevision(existingNames, now)
        put(revision)
        return revision
    }

    /**
     * Merges [incoming] into an existing project with the same id additively without deleting
     * existing projects or items. If no project with `incoming.id` exists yet, adds it; if the
     * existing project is archived, returns it unchanged.
     */
    fun mergeProject(incoming: Project): Project {
        val existing = get(incoming.id) ?: return incoming.also(::put)
        if (existing.archived) return existing
        val merged = ProjectMerge.mergeSingleProject(existing, incoming, clock())
        put(merged)
        return merged
    }

    /** Commits a backup as one store transaction before publishing the restored projects. */
    internal suspend fun restore(incoming: List<Project>, mode: ProjectMergeMode): ProjectMerge.Plan {
        loaded.first { it }
        return withContext(io + NonCancellable) {
            writeLock.withLock {
                val before = state.value
                val plan = when (mode) {
                    ProjectMergeMode.MERGE -> ProjectMerge.plan(before, incoming)
                    ProjectMergeMode.REPLACE -> ProjectMerge.Plan(incoming, incoming.size, 0, 0)
                }
                val upserts = plan.toWrite.associateBy { it.id }
                val removals = if (mode == ProjectMergeMode.REPLACE) {
                    before.mapTo(mutableSetOf()) { it.id } - upserts.keys
                } else {
                    emptySet()
                }
                store.writeBatch(upserts.values.toList(), removals)

                // An edit made during the I/O already has a sync job queued. Keep that newer
                // in-memory state so the job can persist the edit when it acquires the same lock.
                val original = before.associateBy { it.id }
                state.update { current ->
                    val latest = current.associateBy { it.id }.toMutableMap()
                    for (id in removals) {
                        if (latest[id] == original[id]) latest.remove(id)
                    }
                    for ((id, restored) in upserts) {
                        if (latest[id] == original[id]) latest[id] = restored
                    }
                    latest.values.sortedByDescending { it.updatedAt }
                }
                plan
            }
        }
    }

    /** Convenience for the common "Save to project" flow. Returns the saved item, or null if the target is gone or archived. */
    fun addItem(projectId: String, runId: String, item: ProjectItem): ProjectItem? {
        var saved: ProjectItem? = null
        if (get(projectId)?.archived != false) return null
        edit(projectId) { project, now ->
            if (project.run(runId) == null) return@edit project
            saved = item
            project.addItem(runId, item, now)
        }
        return saved
    }

    fun addRun(projectId: String, name: String): Run? {
        val run = Run(name = name.trim().ifEmpty { "Run" })
        if (get(projectId)?.archived != false) return null
        return edit(projectId) { project, now -> project.addRun(run, now) }?.let { run }
    }

    private fun put(project: Project) {
        state.update { list ->
            (listOf(project) + list.filterNot { it.id == project.id }).sortedByDescending { it.updatedAt }
        }
        sync(project.id)
    }

    /** Makes the file for [projectId] match the in-memory state, whenever this job gets the lock. */
    private fun sync(projectId: String) {
        pendingWrites.update { it + 1 }
        scope.launch(io) {
            try {
                writeLock.withLock {
                    val latest = get(projectId)
                    if (latest == null) store.delete(projectId) else store.save(latest)
                }
            } finally {
                pendingWrites.update { it - 1 }
            }
        }
    }

    /** Suspends until every write started so far has reached the store. */
    suspend fun flush() {
        pendingWrites.first { it == 0 }
    }
}

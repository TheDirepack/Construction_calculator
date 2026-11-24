package com.thedirepack.constructioncalc.core.data

import com.thedirepack.constructioncalc.core.model.Circuit
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.ProjectItem
import com.thedirepack.constructioncalc.core.model.Run
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectRepositoryTest {
    private class MemoryStore : ProjectStore {
        val saved = mutableMapOf<String, Project>()
        override fun loadAll() = saved.values.toList()
        override fun save(project: Project) { saved[project.id] = project }
        override fun delete(projectId: String) { saved.remove(projectId) }
        override fun writeBatch(upserts: List<Project>, removals: Set<String>) {
            removals.forEach(::delete)
            upserts.forEach(::save)
        }
    }

    private var now = 1_000L
    private val repo = ProjectRepository(MemoryStore(), CoroutineScope(Dispatchers.Unconfined), clock = { now++ })

    @Test fun archivedProjectsAreReadOnlyUntilUnarchived() {
        val project = repo.create("Job")
        val runId = project.runs.first().id
        repo.setArchived(project.id, true)
        assertTrue(repo.get(project.id)!!.archived)

        // Every kind of change is refused while archived.
        repo.edit(project.id) { p, t -> p.renamed("Changed", t) }
        assertEquals("Job", repo.get(project.id)!!.name)
        assertNull(repo.addItem(project.id, runId, ProjectItem(toolId = "t", title = "x", createdAt = 0)))
        assertNull(repo.addRun(project.id, "Run 2"))
        assertFalse(repo.delete(project.id))
        assertNotNull(repo.get(project.id))

        // Unarchiving makes it editable again, with all data intact.
        repo.setArchived(project.id, false)
        repo.edit(project.id) { p, t -> p.renamed("Changed", t) }
        assertEquals("Changed", repo.get(project.id)!!.name)
        assertNotNull(repo.addItem(project.id, runId, ProjectItem(toolId = "t", title = "x", createdAt = 0)))
        assertTrue(repo.delete(project.id))
    }

    @Test fun duplicateOfArchivedIsEditable() {
        val project = repo.create("Job")
        val archived = repo.setArchived(project.id, true)!!
        val copy = repo.import(archived.duplicated("Job (copy)", now))
        assertFalse(copy.archived)
        repo.edit(copy.id) { p, t -> p.renamed("Edited copy", t) }
        assertEquals("Edited copy", repo.get(copy.id)!!.name)
    }

    @Test fun importAsRevisionCreatesFreshNonCollidingRevisionWithoutTouchingExisting() {
        val original = repo.create("Smith Job")
        val runId = original.runs.first().id
        val item = repo.addItem(runId = runId, projectId = original.id, item = ProjectItem(toolId = "foot-inch", title = "Wall", createdAt = 1L))!!
        val archivedOriginal = repo.setArchived(original.id, true)!!

        val rev2 = repo.importAsRevision(archivedOriginal)
        assertNotEquals(archivedOriginal.id, rev2.id)
        assertEquals("Smith Job (Rev 2)", rev2.name)
        assertFalse(rev2.archived)
        assertNotEquals(item.id, rev2.runs.first().items.single().id)
        assertEquals(2, repo.projects.value.size)

        val rev3 = repo.importAsRevision(archivedOriginal)
        assertEquals("Smith Job (Rev 3)", rev3.name)
        assertEquals(3, repo.projects.value.size)
        // Original project and its item remain untouched.
        assertEquals("Smith Job", repo.get(original.id)!!.name)
        assertTrue(repo.get(original.id)!!.archived)
    }

    @Test fun mergeProjectMergesAdditivelyAndRespectsArchivedProjects() {
        val existing = repo.create("Main Job")
        val runId = existing.runs.first().id
        val localItem = repo.addItem(existing.id, runId, ProjectItem(toolId = "foot-inch", title = "Local Item", createdAt = 5L))!!
        val existingLatest = repo.get(existing.id)!!

        val incomingNewItem = ProjectItem(toolId = "foot-inch", title = "Remote Item", createdAt = 20L)
        val incomingRun = Run(name = "Remote Run", items = listOf(ProjectItem(toolId = "conduit-fill", title = "Run 2 Item", createdAt = 25L)))
        val incomingPanel = Panel(name = "LP-1", circuits = listOf(Circuit(slot = 1, breakerAmps = 20)))
        val incoming = existingLatest.copy(
            runs = listOf(existingLatest.runs.first().copy(items = listOf(incomingNewItem)), incomingRun),
            panels = listOf(incomingPanel),
        )

        val merged = repo.mergeProject(incoming)
        assertEquals(existing.id, merged.id)
        assertEquals(2, merged.runs.size)
        assertEquals(listOf(localItem.id, incomingNewItem.id), merged.run(runId)!!.items.map { it.id })
        assertEquals(1, merged.panels.size)
        assertEquals(1, repo.projects.value.size)

        // Merging a brand-new project id adds it directly.
        val standalone = Project.create("Standalone", now = 99L)
        assertEquals(standalone, repo.mergeProject(standalone))
        assertNotNull(repo.get(standalone.id))

        // Merging into an archived project leaves it untouched.
        repo.setArchived(existing.id, true)
        val beforeArchived = repo.get(existing.id)!!
        val extraIncoming = beforeArchived.copy(runs = beforeArchived.runs + Run(name = "Should Not Add"))
        val afterMerge = repo.mergeProject(extraIncoming)
        assertEquals(beforeArchived, afterMerge)
        assertEquals(2, repo.get(existing.id)!!.runs.size)
    }
}

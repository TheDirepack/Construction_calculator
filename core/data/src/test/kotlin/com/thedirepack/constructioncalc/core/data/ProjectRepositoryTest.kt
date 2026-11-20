package com.thedirepack.constructioncalc.core.data

import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.ProjectItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    }

    private var now = 1_000L
    private val repo = ProjectRepository(MemoryStore(), CoroutineScope(Dispatchers.Unconfined)) { now++ }

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
}

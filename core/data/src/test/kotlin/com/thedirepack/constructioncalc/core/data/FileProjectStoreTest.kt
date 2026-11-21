package com.thedirepack.constructioncalc.core.data

import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.ProjectCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for FileProjectStore atomic write-and-replace, delete, and directory resilience.
 */
class FileProjectStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun saveAndLoadAllPreservesProjectDataAtomically() {
        val dir = tempFolder.newFolder("projects_test")
        val store = FileProjectStore(dir)

        val project1 = Project.create("Downtown Office", now = 1000L)
        val project2 = Project.create("Substation B", now = 2000L)

        store.save(project1)
        store.save(project2)

        // Ensure temporary files were cleaned up after rename
        val tmpFiles = dir.listFiles { _, name -> name.endsWith(".tmp") } ?: emptyArray()
        assertEquals(0, tmpFiles.size)

        val loaded = store.loadAll()
        assertEquals(2, loaded.size)
        val loadedMap = loaded.associateBy { it.id }
        assertEquals(project1.name, loadedMap[project1.id]?.name)
        assertEquals(project2.name, loadedMap[project2.id]?.name)

        // Updating project1 modifies target file cleanly
        val updated1 = project1.renamed("Downtown Office Tower", now = 3000L)
        store.save(updated1)

        val reloaded = store.loadAll().associateBy { it.id }
        assertEquals("Downtown Office Tower", reloaded[project1.id]?.name)
        assertEquals(3000L, reloaded[project1.id]?.updatedAt)

        // Deleting project2 removes its file
        store.delete(project2.id)
        assertFalse(File(dir, "${project2.id}.json").exists())
        assertEquals(1, store.loadAll().size)
    }

    @Test
    fun loadAllSkipsUnreadableOrCorruptedFilesGracefully() {
        val dir = tempFolder.newFolder("corrupted_projects")
        val store = FileProjectStore(dir)

        val goodProject = Project.create("Clean Project", now = 1000L)
        store.save(goodProject)

        // Write junk into an existing .json file
        val badFile = File(dir, "corrupted.json")
        badFile.writeText("THIS IS CORRUPTED NOT JSON")

        val emptyJson = File(dir, "empty.json")
        emptyJson.writeText("{}")

        // loadAll must catch errors silently and return only valid projects
        val loaded = store.loadAll()
        assertEquals(1, loaded.size)
        assertEquals("Clean Project", loaded.single().name)
    }

    @Test
    fun unreadableFilesAreSetAsideAndReported() {
        val dir = tempFolder.newFolder("projects_damaged")
        val store = FileProjectStore(dir)
        val good = Project.create("Good", now = 1L)
        store.save(good)
        File(dir, "broken.json").writeText("{ not json")
        val loaded = store.loadAll()
        assertEquals(listOf(good.id), loaded.map { it.id })
        assertEquals(listOf("broken.json"), store.damaged())
        assertFalse(File(dir, "broken.json").exists())
        assertTrue(File(store.damagedDir, "broken.json").readText().startsWith("{ not json"))
        // Set aside, it no longer loads or reports.
        store.loadAll()
        assertEquals(emptyList<String>(), store.damaged())
    }
}

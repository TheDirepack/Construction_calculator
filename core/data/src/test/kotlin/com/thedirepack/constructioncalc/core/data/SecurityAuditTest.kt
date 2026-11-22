package com.thedirepack.constructioncalc.core.data

import com.thedirepack.constructioncalc.core.model.PlanDrawing
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.ProjectCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Security audit and regression-proof tests for storage and archive boundaries.
 */
class SecurityAuditTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun fileProjectStoreCurrentlyAllowsPathTraversalViaCraftedProjectId() {
        val root = tempFolder.newFolder("app_files")
        val projectsDir = File(root, "projects").apply { mkdirs() }
        val store = FileProjectStore(projectsDir)

        // Simulate a crafted .project.json imported from an external source with "../" in project.id.
        val crafted = Project.create("Crafted", now = 1_000L).copy(id = "../escaped_project")
        val decoded = ProjectCodec.decode(ProjectCodec.encode(crafted))
        assertEquals("../escaped_project", decoded.id)

        // Saving writes outside projectsDir into parent root/escaped_project.json!
        store.save(decoded)
        val escapedFile = File(root, "escaped_project.json")
        assertTrue(
            "Expected FileProjectStore.save to demonstrate path traversal outside projectsDir",
            escapedFile.isFile,
        )

        // Deleting also deletes outside projectsDir.
        store.delete(decoded.id)
        assertFalse(escapedFile.exists())
    }

    @Test
    fun projectArchiveRejectsZipSlipEntryNames() {
        val hash = "a".repeat(64)
        val project = Project.create("With Drawing", now = 1_000L).copy(
            drawings = listOf(PlanDrawing(name = "A1.pdf", sha256 = hash)),
        )
        val bytes = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zip ->
                zip.putNextEntry(ZipEntry("project.json"))
                zip.write(ProjectCodec.encode(project).encodeToByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("../evil.pdf"))
                zip.write("%PDF-1.4".encodeToByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        val stored = mutableListOf<String>()
        var threw = false
        try {
            ProjectArchive.read(ByteArrayInputStream(bytes)) { _, h -> stored += h }
        } catch (_: IOException) {
            threw = true
        }
        assertTrue("ZIP entry outside drawings/<sha256>.pdf must be rejected", threw)
        assertTrue(stored.isEmpty())
    }

    @Test
    fun projectArchiveLeavesEarlierPdfsStoredWhenLaterPdfIsMissing() {
        val hash1 = "1".repeat(64)
        val hash2 = "2".repeat(64)
        val project = Project.create("Two Drawings", now = 1_000L).copy(
            drawings = listOf(
                PlanDrawing(name = "A1.pdf", sha256 = hash1),
                PlanDrawing(name = "A2.pdf", sha256 = hash2),
            ),
        )
        // Archive includes hash1's PDF but omits hash2's PDF.
        val bytes = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zip ->
                zip.putNextEntry(ZipEntry("project.json"))
                zip.write(ProjectCodec.encode(project).encodeToByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("drawings/$hash1.pdf"))
                zip.write("%PDF-1.4".encodeToByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        val stored = mutableListOf<String>()
        var threw = false
        try {
            ProjectArchive.read(ByteArrayInputStream(bytes)) { input, h ->
                input.readBytes()
                stored += h
            }
        } catch (_: IOException) {
            threw = true
        }
        assertTrue(threw)
        // Demonstrates that hash1 was already persisted before the archive failed validation.
        assertEquals(listOf(hash1), stored)
    }
}

package com.thedirepack.constructioncalc.core.data

import com.thedirepack.constructioncalc.core.model.PlanDrawing
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.ProjectCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectArchiveTest {
    @get:Rule val temp = TemporaryFolder()
    private val one = "1".repeat(64)
    private val two = "2".repeat(64)
    private val project = Project.create("Job", 1).copy(drawings = listOf(
        PlanDrawing(name = "Floor.pdf", sha256 = one),
        PlanDrawing(name = "Floor.pdf", sha256 = two),
    ))

    @Test fun sameNamedDrawingsRetainBothOriginals() {
        val sources = mapOf(one to temp.newFile("one"), two to temp.newFile("two"))
        sources.forEach { (hash, file) -> file.writeText(hash) }
        val out = ByteArrayOutputStream()
        ProjectArchive.write(out, project, 2, sources::getValue)
        val restoredBytes = mutableMapOf<String, ByteArray>()
        val restored = ProjectArchive.read(out.toByteArray().inputStream()) { pdf, hash ->
            restoredBytes[hash] = pdf.readBytes()
        }
        assertEquals(project, restored)
        assertArrayEquals(one.encodeToByteArray(), restoredBytes.getValue(one))
        assertArrayEquals(two.encodeToByteArray(), restoredBytes.getValue(two))
    }

    @Test fun refusesIncompleteArchivesAndUnexpectedPaths() {
        fun archive(extra: String?): ByteArray {
            val bytes = ByteArrayOutputStream()
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("project.json"))
                zip.write(ProjectCodec.encode(project).encodeToByteArray())
                zip.closeEntry()
                if (extra != null) {
                    zip.putNextEntry(ZipEntry(extra))
                    zip.write(1)
                    zip.closeEntry()
                }
            }
            return bytes.toByteArray()
        }
        for (entry in listOf(null, "../Floor.pdf")) {
            assertThrows(IOException::class.java) {
                ProjectArchive.read(archive(entry).inputStream()) { _, _ -> error("Unexpected attachment") }
            }
        }
    }
}

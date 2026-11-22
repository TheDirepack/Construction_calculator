package com.thedirepack.constructioncalc.core.data

import com.thedirepack.constructioncalc.core.model.AppFileCodec
import com.thedirepack.constructioncalc.core.model.BackupDocument
import com.thedirepack.constructioncalc.core.model.PlanDrawing
import com.thedirepack.constructioncalc.core.model.Project
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveTest {
    @get:Rule val temp = TemporaryFolder()

    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)
    private val missingHash = "c".repeat(64)

    private val project1 = Project.create("Job 1", 10L).copy(
        drawings = listOf(PlanDrawing(name = "A101.pdf", sha256 = hashA)),
    )
    private val project2 = Project.create("Job 2", 20L).copy(
        drawings = listOf(
            PlanDrawing(name = "E101.pdf", sha256 = hashA), // shared hash across projects
            PlanDrawing(name = "E102.pdf", sha256 = hashB),
        ),
    )
    private val backup = BackupDocument(
        exportedAt = 100L,
        settings = buildJsonObject { put("theme", "DARK") },
        projects = listOf(project1, project2),
    )

    @Test fun roundTripsBackupWithReferencedPdfs() {
        val fileA = temp.newFile("a.pdf").apply { writeBytes("pdf-a-bytes".encodeToByteArray()) }
        val fileB = temp.newFile("b.pdf").apply { writeBytes("pdf-b-bytes".encodeToByteArray()) }
        val files = mapOf(hashA to fileA, hashB to fileB)

        val out = ByteArrayOutputStream()
        BackupArchive.write(out, backup) { hash -> files.getValue(hash) }

        val restoredPdfs = mutableMapOf<String, ByteArray>()
        val restored = BackupArchive.read(out.toByteArray().inputStream()) { pdf, hash ->
            restoredPdfs[hash] = pdf.readBytes()
        }

        assertEquals(backup, restored)
        assertEquals(setOf(hashA, hashB), restoredPdfs.keys)
        assertArrayEquals("pdf-a-bytes".encodeToByteArray(), restoredPdfs.getValue(hashA))
        assertArrayEquals("pdf-b-bytes".encodeToByteArray(), restoredPdfs.getValue(hashB))
    }

    @Test fun backupThatExportsAlsoImportsWhenManyReferencedPdfsAreMissing() {
        // 600 referenced drawings (over the 500-PDF limit) but only one available: export packs one
        // PDF, so import must count PDFs received, not hashes referenced, or the backup can't be restored.
        val fileA = temp.newFile("many-a.pdf").apply { writeBytes("a".encodeToByteArray()) }
        val missing = (0 until 600).map { i -> PlanDrawing(name = "M$i.pdf", sha256 = i.toString().padStart(64, '0')) }
        val big = backup.copy(projects = listOf(project1, Project.create("Lots", 40L).copy(drawings = missing)))
        val out = ByteArrayOutputStream()
        BackupArchive.write(out, big) { hash -> if (hash == hashA) fileA else File(temp.root, "nope.pdf") }

        val restoredPdfs = mutableListOf<String>()
        val restored = BackupArchive.read(out.toByteArray().inputStream()) { pdf, hash -> pdf.readBytes(); restoredPdfs += hash }
        assertEquals(big, restored)
        assertEquals(listOf(hashA), restoredPdfs)
    }

    @Test fun skipsUnavailablePdfsGracefully() {
        val fileA = temp.newFile("a2.pdf").apply { writeBytes("only-a".encodeToByteArray()) }
        val backupWithMissing = backup.copy(
            projects = listOf(
                project1,
                Project.create("Missing", 30L).copy(drawings = listOf(PlanDrawing(name = "Gone.pdf", sha256 = missingHash))),
            ),
        )
        val out = ByteArrayOutputStream()
        BackupArchive.write(out, backupWithMissing) { hash ->
            if (hash == hashA) fileA else File(temp.root, "does-not-exist.pdf")
        }

        val restoredPdfs = mutableMapOf<String, ByteArray>()
        val restored = BackupArchive.read(out.toByteArray().inputStream()) { pdf, hash ->
            restoredPdfs[hash] = pdf.readBytes()
        }

        assertEquals(backupWithMissing, restored)
        assertEquals(setOf(hashA), restoredPdfs.keys)
    }

    @Test fun rejectsZipSlipAndUnexpectedEntries() {
        fun archiveWith(extraEntry: String): ByteArray {
            val bytes = ByteArrayOutputStream()
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("backup.json"))
                zip.write(AppFileCodec.encodeBackup(backup).encodeToByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(extraEntry))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
            return bytes.toByteArray()
        }

        for (badPath in listOf("../evil.pdf", "drawings/../../etc/passwd", "/abs/path.pdf", "drawings/${"f".repeat(64)}.pdf")) {
            assertThrows(IOException::class.java) {
                BackupArchive.read(archiveWith(badPath).inputStream()) { _, _ -> error("Should not store unexpected entry") }
            }
        }
    }

    @Test fun rejectsWrongFirstEntry() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("project.json"))
            zip.write("{}".encodeToByteArray())
            zip.closeEntry()
        }
        assertThrows(IOException::class.java) {
            BackupArchive.read(bytes.toByteArray().inputStream()) { _, _ -> }
        }
    }
}

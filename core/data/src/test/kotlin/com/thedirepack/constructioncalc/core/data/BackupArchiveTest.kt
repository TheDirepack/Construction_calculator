package com.thedirepack.constructioncalc.core.data

import com.thedirepack.constructioncalc.core.model.AppFileCodec
import com.thedirepack.constructioncalc.core.model.BackupDocument
import com.thedirepack.constructioncalc.core.model.BackupManifest
import com.thedirepack.constructioncalc.core.model.PlanDrawing
import com.thedirepack.constructioncalc.core.model.PlanMark
import com.thedirepack.constructioncalc.core.model.PlanMarkKind
import com.thedirepack.constructioncalc.core.model.PlanPoint
import com.thedirepack.constructioncalc.core.model.Project
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
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
        BackupArchive.write(out, big.copy(incompleteMedia = true)) { hash ->
            if (hash == hashA) fileA else File(temp.root, "nope.pdf")
        }

        val restoredPdfs = mutableListOf<String>()
        val restored = BackupArchive.read(out.toByteArray().inputStream()) { pdf, hash -> pdf.readBytes(); restoredPdfs += hash }
        assertEquals(big.copy(incompleteMedia = true), restored)
        assertEquals(listOf(hashA), restoredPdfs)
    }

    @Test fun identifiesMissingPdfsAndPhotoPinsBeforeWritingAndOnlyAllowsAnExplicitPartialBackup() {
        val fileA = temp.newFile("a2.pdf").apply { writeBytes("only-a".encodeToByteArray()) }
        val pin = PlanMark(
            page = 0, kind = PlanMarkKind.PHOTO, points = listOf(PlanPoint(0.0, 0.0)),
            text = "Feeder photo", photoSha256 = hashB,
        )
        val backupWithMissing = backup.copy(
            projects = listOf(
                project1,
                Project.create("Missing", 30L).copy(
                    drawings = listOf(PlanDrawing(name = "Gone.pdf", sha256 = missingHash, marks = listOf(pin))),
                ),
            ),
        )
        assertEquals(
            listOf("Missing / Gone.pdf (original PDF)", "Missing / Gone.pdf (page 1, Feeder photo)"),
            BackupArchive.missingMedia(backupWithMissing, hasPdf = { it == hashA }, hasPhoto = { false }),
        )
        val pdfs: (String) -> File = { if (it == hashA) fileA else File(temp.root, "does-not-exist.pdf") }
        val out = ByteArrayOutputStream()
        assertThrows(IOException::class.java) { BackupArchive.write(out, backupWithMissing, pdfs) }
        assertEquals(0, out.size())

        BackupArchive.write(out, backupWithMissing.copy(incompleteMedia = true), pdfs)
        val restoredPdfs = mutableMapOf<String, ByteArray>()
        val restored = BackupArchive.read(out.toByteArray().inputStream()) { pdf, hash ->
            restoredPdfs[hash] = pdf.readBytes()
        }

        assertEquals(backupWithMissing.copy(incompleteMedia = true), restored)
        assertEquals(setOf(hashA), restoredPdfs.keys)
        assertTrue(restored.incompleteMedia)
    }

    @Test fun legacyArchiveWithUnmarkedMissingMediaCanStillBeRead() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("backup.json"))
            zip.write(AppFileCodec.encodeBackup(backup).encodeToByteArray())
            zip.closeEntry()
        }

        val restored = BackupArchive.read(out.toByteArray().inputStream()) { _, _ -> error("Unexpected PDF") }
        assertEquals(backup, restored)
        assertFalse(restored.incompleteMedia)
        assertEquals(3, BackupArchive.missingMedia(restored, hasPdf = { false }, hasPhoto = { false }).size)
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

    @Test fun backupManifestVerifiesDocumentAndMediaSha256() {
        val fileA = temp.newFile("m-a.pdf").apply { writeBytes("pdf-a".encodeToByteArray()) }
        val fileB = temp.newFile("m-b.pdf").apply { writeBytes("pdf-b".encodeToByteArray()) }
        val files = mapOf(hashA to fileA, hashB to fileB)
        val out = ByteArrayOutputStream()
        BackupArchive.write(out, backup) { files.getValue(it) }
        val bytes = out.toByteArray()

        val entries = mutableListOf<String>()
        var docBytes: ByteArray? = null
        var manifest: BackupManifest? = null
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += entry.name
                when (entry.name) {
                    "backup.json" -> docBytes = zip.readBytes()
                    AppFileCodec.MANIFEST_FILE -> manifest = AppFileCodec.decodeManifest(zip.readBytes().decodeToString())
                    else -> zip.readBytes()
                }
                zip.closeEntry()
            }
        }
        assertEquals("backup.json", entries.first())
        assertTrue(AppFileCodec.MANIFEST_FILE in entries)
        assertNotNull(manifest)
        val expectedDocSha = MessageDigest.getInstance("SHA-256").digest(docBytes!!).joinToString("") { "%02x".format(it) }
        assertEquals("backup", manifest!!.kind)
        assertEquals(expectedDocSha, manifest!!.documentSha256)
        assertEquals(2, manifest!!.mediaSha256.size)

        // Mismatched manifest documentSha256 is rejected.
        val badManifestZip = ByteArrayOutputStream()
        ZipInputStream(bytes.inputStream()).use { src ->
            ZipOutputStream(badManifestZip).use { dst ->
                while (true) {
                    val entry = src.nextEntry ?: break
                    val content = src.readBytes()
                    dst.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == AppFileCodec.MANIFEST_FILE) {
                        val bad = manifest!!.copy(documentSha256 = "0".repeat(64))
                        dst.write(AppFileCodec.encodeManifest(bad).encodeToByteArray())
                    } else {
                        dst.write(content)
                    }
                    dst.closeEntry()
                    src.closeEntry()
                }
            }
        }
        assertThrows(IOException::class.java) {
            BackupArchive.read(badManifestZip.toByteArray().inputStream()) { pdf, _ -> pdf.readBytes() }
        }
    }
}

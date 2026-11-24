package com.thedirepack.constructioncalc.core.data

import com.thedirepack.constructioncalc.core.model.AppFileCodec
import com.thedirepack.constructioncalc.core.model.BackupManifest
import com.thedirepack.constructioncalc.core.model.PlanDrawing
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.ProjectCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
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

    @Test fun writesManifestAfterFirstProjectEntryAndVerifiesSha256() {
        val sources = mapOf(one to temp.newFile("m-one"), two to temp.newFile("m-two"))
        sources.forEach { (hash, file) -> file.writeText("pdf-$hash") }
        val out = ByteArrayOutputStream()
        ProjectArchive.write(out, project, 1_700_000_000_000L, sources::getValue)
        val zipBytes = out.toByteArray()

        val entryNames = mutableListOf<String>()
        var rawProjectBytes: ByteArray? = null
        var manifest: BackupManifest? = null
        ZipInputStream(zipBytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryNames += entry.name
                when (entry.name) {
                    "project.json" -> rawProjectBytes = zip.readBytes()
                    AppFileCodec.MANIFEST_FILE -> manifest = AppFileCodec.decodeManifest(zip.readBytes().decodeToString())
                    else -> zip.readBytes()
                }
                zip.closeEntry()
            }
        }
        assertEquals("project.json", entryNames.first())
        assertTrue(AppFileCodec.MANIFEST_FILE in entryNames)
        assertNotNull(manifest)
        val sha = { bytes: ByteArray ->
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
        assertEquals("project", manifest!!.kind)
        assertEquals("Construction Calc", manifest!!.app)
        assertEquals(1_700_000_000_000L, manifest!!.createdAt)
        assertEquals("2023-11-14T22:13:20Z", manifest!!.createdIso)
        assertEquals(sha(rawProjectBytes!!), manifest!!.documentSha256)
        assertEquals(sha(sources.getValue(one).readBytes()), manifest!!.mediaSha256["drawings/$one.pdf"])
        assertEquals(sha(sources.getValue(two).readBytes()), manifest!!.mediaSha256["drawings/$two.pdf"])

        // Tampering with project.json without updating manifest.json must be rejected.
        val tampered = ByteArrayOutputStream()
        ZipInputStream(zipBytes.inputStream()).use { src ->
            ZipOutputStream(tampered).use { dst ->
                while (true) {
                    val entry = src.nextEntry ?: break
                    val bytes = src.readBytes()
                    dst.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == "project.json") {
                        dst.write(bytes.decodeToString().replace("Job", "Tampered").encodeToByteArray())
                    } else {
                        dst.write(bytes)
                    }
                    dst.closeEntry()
                    src.closeEntry()
                }
            }
        }
        assertThrows(IOException::class.java) {
            ProjectArchive.read(tampered.toByteArray().inputStream()) { pdf, _ -> pdf.readBytes() }
        }

        // Tampering with media bytes without updating manifest.json must also be rejected.
        val tamperedMedia = ByteArrayOutputStream()
        ZipInputStream(zipBytes.inputStream()).use { src ->
            ZipOutputStream(tamperedMedia).use { dst ->
                while (true) {
                    val entry = src.nextEntry ?: break
                    val bytes = src.readBytes()
                    dst.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == "drawings/$one.pdf") {
                        dst.write("corrupted-pdf".encodeToByteArray())
                    } else {
                        dst.write(bytes)
                    }
                    dst.closeEntry()
                    src.closeEntry()
                }
            }
        }
        assertThrows(IOException::class.java) {
            ProjectArchive.read(tamperedMedia.toByteArray().inputStream()) { pdf, _ -> pdf.readBytes() }
        }
    }

    @Test fun legacyProjectArchiveWithoutManifestStillReads() {
        val sources = mapOf(one to temp.newFile("l-one"), two to temp.newFile("l-two"))
        sources.forEach { (hash, file) -> file.writeText(hash) }
        val legacyZip = ByteArrayOutputStream()
        ZipOutputStream(legacyZip).use { zip ->
            zip.putNextEntry(ZipEntry("project.json"))
            zip.write(ProjectCodec.encode(project).encodeToByteArray())
            zip.closeEntry()
            sources.forEach { (hash, file) ->
                zip.putNextEntry(ZipEntry("drawings/$hash.pdf"))
                zip.write(file.readBytes())
                zip.closeEntry()
            }
        }
        val restored = ProjectArchive.read(legacyZip.toByteArray().inputStream()) { pdf, _ -> pdf.readBytes() }
        assertEquals(project, restored)
    }
}

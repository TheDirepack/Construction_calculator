package com.thedirepack.constructioncalc.core.data

import com.thedirepack.constructioncalc.core.model.BackupDocument
import com.thedirepack.constructioncalc.core.model.PlanDrawing
import com.thedirepack.constructioncalc.core.model.PlanMark
import com.thedirepack.constructioncalc.core.model.PlanMarkKind
import com.thedirepack.constructioncalc.core.model.PlanPoint
import com.thedirepack.constructioncalc.core.model.Project
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PlanPhotosTest {
    @get:Rule val temp = TemporaryFolder()

    private val jpegSample = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4)
    private val pngSample = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun validatesHeadersRejectsBadImagesAndCommitsByHash() {
        assertNull(PlanPhotos.imageHeaderProblem(jpegSample))
        assertNull(PlanPhotos.imageHeaderProblem(pngSample))
        assertEquals("This photo file is empty.", PlanPhotos.imageHeaderProblem(ByteArray(0)))
        assertEquals("This file isn't a JPEG or PNG image.", PlanPhotos.imageHeaderProblem("%PDF-1.7".encodeToByteArray()))

        val dir = temp.newFolder("plan-photos")
        val photos = PlanPhotos(dir)
        val expectedHash = sha256(jpegSample)

        val staged = photos.stage(jpegSample.inputStream(), expectedHash = expectedHash, checkHeader = true)
        assertEquals(expectedHash, staged.sha256)
        photos.commit(staged)
        assertArrayEquals(jpegSample, photos.file(expectedHash).readBytes())

        assertThrows(IOException::class.java) {
            photos.stage(jpegSample.inputStream(), expectedHash = "0".repeat(64), checkHeader = true)
        }
        assertThrows(IOException::class.java) {
            photos.stage("not an image".encodeToByteArray().inputStream(), checkHeader = true)
        }
    }

    @Test fun garbageCollectDeletesOnlyOrphanPhotos() {
        val dir = temp.newFolder("gc-photos")
        val keptHash = "a".repeat(64)
        val orphanHash = "b".repeat(64)
        val kept = File(dir, "$keptHash.jpg").apply { writeBytes(jpegSample) }
        val orphan = File(dir, "$orphanHash.jpg").apply { writeBytes(jpegSample) }
        val tmp = File(dir, "photo-123.tmp").apply { writeText("staging") }
        val other = File(dir, "custom.jpg").apply { writeBytes(jpegSample) }

        val deleted = PlanPhotos.garbageCollect(dir, setOf(keptHash))
        assertEquals(1, deleted)
        assertTrue(kept.isFile)
        assertFalse(orphan.exists())
        assertTrue(tmp.isFile)
        assertTrue(other.isFile)
    }

    @Test fun projectAndBackupArchivesRoundTripPhotosAndRejectUnreferencedOnes() {
        val pdfHash = "1".repeat(64)
        val photoHash = sha256(jpegSample)
        val pdfFile = temp.newFile("plan.pdf").apply { writeText("pdf-data") }
        val photoFile = temp.newFile("field.jpg").apply { writeBytes(jpegSample) }

        val drawing = PlanDrawing(
            name = "E101.pdf",
            sha256 = pdfHash,
            marks = listOf(
                PlanMark(page = 0, kind = PlanMarkKind.PHOTO, points = listOf(PlanPoint(10.0, 20.0)), text = "Panel", photoSha256 = photoHash),
            ),
        )
        val project = Project.create("Site", 1L).copy(drawings = listOf(drawing))

        val out = ByteArrayOutputStream()
        ProjectArchive.write(out, project, 2L, { pdfFile }, { hash -> if (hash == photoHash) photoFile else File(temp.root, "missing") })
        val restoredPhotos = mutableMapOf<String, ByteArray>()
        val restored = ProjectArchive.read(
            out.toByteArray().inputStream(),
            storePdf = { stream, _ -> stream.readBytes() },
            storePhoto = { stream, hash -> restoredPhotos[hash] = stream.readBytes() },
        )
        assertEquals(project, restored)
        assertArrayEquals(jpegSample, restoredPhotos.getValue(photoHash))

        val backup = BackupDocument(exportedAt = 5L, projects = listOf(project))
        val backupOut = ByteArrayOutputStream()
        BackupArchive.write(backupOut, backup, { pdfFile }, { hash -> if (hash == photoHash) photoFile else File(temp.root, "missing") })
        val backupPhotos = mutableMapOf<String, ByteArray>()
        val restoredBackup = BackupArchive.read(
            backupOut.toByteArray().inputStream(),
            storePdf = { stream, _ -> stream.readBytes() },
            storePhoto = { stream, hash -> backupPhotos[hash] = stream.readBytes() },
        )
        assertEquals(backup, restoredBackup)
        assertArrayEquals(jpegSample, backupPhotos.getValue(photoHash))

        // Unreferenced photo entries in ZIP must be rejected.
        val rogueZip = ByteArrayOutputStream()
        ZipOutputStream(rogueZip).use { zip ->
            zip.putNextEntry(ZipEntry("project.json"))
            zip.write(com.thedirepack.constructioncalc.core.model.ProjectCodec.encode(project).encodeToByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("drawings/$pdfHash.pdf"))
            zip.write(pdfFile.readBytes())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("photos/${"f".repeat(64)}.jpg"))
            zip.write(jpegSample)
            zip.closeEntry()
        }
        assertThrows(IOException::class.java) {
            ProjectArchive.read(rogueZip.toByteArray().inputStream(), { s, _ -> s.readBytes() }, { s, _ -> s.readBytes() })
        }
    }
}

package com.thedirepack.constructioncalc.core.model

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactShareTest {
    private fun sampleProject(): Project {
        val length = Quantity.inches(Rational.of(191, 2))
        val item = ProjectItem(
            toolId = "foot-inch",
            title = "North Wall",
            createdAt = 1_000L,
            inputs = buildJsonObject { put("expr", JsonPrimitive("5' 3 1/2\" + 2' 8\"")) },
            results = listOf(ResultValue.of("result", "Result", length, "7' 11 1/2\"")),
            summary = "5' 3 1/2\" + 2' 8\" = 7' 11 1/2\"",
        )
        return Project.create("Smith Residence", now = 1_000L)
            .let { p -> p.addItem(p.runs.first().id, item, 2_000L) }
            .copy(panels = listOf(Panel(name = "LP-1", busAmps = null, circuits = listOf(Circuit(slot = 1, breakerAmps = null)))))
    }

    @Test fun projectRoundTripsAndIsSmallerThanPrettyJson() {
        val project = sampleProject()
        val prettyJson = ProjectCodec.encode(project, exportedAt = 3_000L)
        val compact = CompactShare.encode(project, exportedAt = 3_000L)

        assertTrue(compact.startsWith(CompactShare.PREFIX))
        assertTrue("Expected compact payload (${compact.length}) to be smaller than JSON (${prettyJson.length})", compact.length < prettyJson.length)
        assertEquals(project, CompactShare.decodeProject(compact))
        assertEquals(AppFile.SingleProject(project), AppFileCodec.decode(compact))
    }

    @Test fun shareMessageWithSurroundingTextDecodesCleanly() {
        val project = sampleProject()
        val sms = "Hey check this job:\n" + CompactShare.shareMessage(project, exportedAt = 4_000L) + "\nThanks!"
        assertTrue(CompactShare.isCompactShare(sms))
        assertEquals(AppFile.SingleProject(project), AppFileCodec.decode(sms))
    }

    @Test fun backupRoundTripsThroughCompactShare() {
        val backup = BackupDocument(exportedAt = 10L, projects = listOf(sampleProject()))
        val encoded = CompactShare.encodeBackup(backup)
        assertEquals(AppFile.Backup(backup), CompactShare.decode(encoded))
        assertEquals(AppFile.Backup(backup), AppFileCodec.decode(encoded))
    }

    @Test fun jsonContainingCompactPrefixInNoteDoesNotRecurse() {
        val project = Project.create("Note Test", now = 1L).withNote("See CC1:notapayload for details", now = 2L)
        val json = ProjectCodec.encode(project)
        assertEquals(AppFile.SingleProject(project), AppFileCodec.decode(json))
    }

    @Test fun rejectsCorruptOrNonCompactInputs() {
        assertFalse(CompactShare.isCompactShare("plain text without prefix"))
        for (bad in listOf("", "plain", "CC1:", "CC1:!!!", "CC1:AAAA")) {
            assertThrows(ProjectFormatException::class.java) {
                CompactShare.decode(bad)
            }
        }
    }

    @Test fun refusesToEncodeWhatItCouldNotDecode() {
        // A note of random text compresses poorly: well over the encoded-size limit.
        val rng = java.util.Random(1)
        val noise = String(CharArray(700_000) { ('!' + rng.nextInt(90)) })
        val huge = Project.create("Huge", 1L).copy(note = noise)
        val error = assertThrows(ProjectFormatException::class.java) { CompactShare.encode(huge) }
        assertTrue(error.message!!.contains("too large"))
    }
}

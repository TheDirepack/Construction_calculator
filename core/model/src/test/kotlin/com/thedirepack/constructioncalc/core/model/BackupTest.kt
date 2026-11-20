package com.thedirepack.constructioncalc.core.model

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    private val a = Project.create("A", now = 100)
    private val b = Project.create("B", now = 200)

    @Test fun backupRoundTripsAndIsDetected() {
        val backup = BackupDocument(
            exportedAt = 5,
            settings = buildJsonObject { put("theme", "DARK") },
            toolSettings = mapOf("foot-inch" to buildJsonObject { put("x", 1) }),
            projects = listOf(a, b),
            toolData = mapOf("foot-inch" to JsonPrimitive("history")),
        )
        val file = AppFileCodec.decode(AppFileCodec.encodeBackup(backup))
        assertEquals(AppFile.Backup(backup), file)
        assertEquals(listOf("2 projects, 0 saved items", "App settings", "Tool settings: foot-inch", "Tool data: foot-inch"), backup.describe())
    }

    @Test fun singleProjectFilesAreDetected() {
        assertEquals(AppFile.SingleProject(a), AppFileCodec.decode(ProjectCodec.encode(a)))
    }

    @Test fun otherJsonIsRejected() {
        listOf("""{"name":"x"}""", "[]", "nope").forEach {
            try {
                AppFileCodec.decode(it)
                throw AssertionError("expected failure for $it")
            } catch (_: ProjectFormatException) {
            }
        }
    }

    @Test fun fileNames() {
        assertTrue(AppFileCodec.backupFileName(0).endsWith(".backup.json"))
        assertEquals("A.project.json", ProjectCodec.fileName(a))
    }

    @Test fun mergePlanAddsNewUpdatesOlderKeepsNewer() {
        val mineOld = a.renamed("A mine", now = 150)
        val theirsNewer = a.renamed("A theirs", now = 300)
        val mineNewer = b.renamed("B mine", now = 400)
        val c = Project.create("C", now = 50)
        val plan = ProjectMerge.plan(existing = listOf(mineOld, mineNewer), incoming = listOf(theirsNewer, b, c))
        assertEquals(listOf(theirsNewer, c), plan.toWrite)
        assertEquals(1, plan.added)
        assertEquals(1, plan.updated)
        assertEquals(1, plan.unchanged)
    }
}

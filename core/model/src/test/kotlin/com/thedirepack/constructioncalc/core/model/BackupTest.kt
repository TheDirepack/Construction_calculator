package com.thedirepack.constructioncalc.core.model

import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    @Test fun backupRestoresExactRecentValuesAlongsideTheirDisplayText() {
        val quantity = Quantity.inches(Rational.of(18345, 16))
        val recent = RecentResult(
            value = ResultValue.of("length", "Length", quantity, "95′ 6 9/16″"),
            toolId = "foot-inch",
            toolTitle = "Foot-Inch",
            at = 25L,
        )
        val backup = BackupDocument(projects = listOf(a), recents = listOf(recent))
        val restored = AppFileCodec.decode(AppFileCodec.encodeBackup(backup)) as AppFile.Backup
        assertEquals(backup, restored.backup)
        assertEquals(quantity, restored.backup.recents.single().value.toQuantity())
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

    @Test fun backupManifestRoundTrips() {
        val manifest = BackupManifest(
            kind = "project",
            createdAt = 1_700_000_000_000L,
            documentSha256 = "a".repeat(64),
            mediaSha256 = mapOf(
                "drawings/${"b".repeat(64)}.pdf" to "b".repeat(64),
                "photos/${"c".repeat(64)}.jpg" to "c".repeat(64),
            ),
        )
        val encoded = AppFileCodec.encodeManifest(manifest)
        assertEquals(manifest, AppFileCodec.decodeManifest(encoded))
        assertEquals("2023-11-14T22:13:20Z", manifest.createdIso)
    }

    @Test fun mergeSingleProjectMergesAdditivelyWithoutDeletingExistingData() {
        val sharedRunId = "run-shared"
        val sharedItemExisting = ProjectItem(
            id = "item-1",
            toolId = "foot-inch",
            title = "Existing Wall",
            createdAt = 10L,
            updatedAt = 20L,
        )
        val localOnlyItem = ProjectItem(
            id = "item-local",
            toolId = "foot-inch",
            title = "Local Only",
            createdAt = 15L,
            updatedAt = 15L,
        )
        val sharedCircuit = Circuit(
            id = "cir-1",
            slot = 1,
            breakerAmps = 20,
            description = "",
            runIds = listOf(sharedRunId),
            groupIds = listOf("grp-1"),
        )
        val drawingHash = "d".repeat(64)
        val existingDrawing = PlanDrawing(
            id = "drw-1",
            name = "E101.pdf",
            sha256 = drawingHash,
            marks = listOf(PlanMark(id = "mark-1", page = 0, kind = PlanMarkKind.TEXT, points = listOf(PlanPoint(1.0, 2.0)), text = "Local")),
            measurements = listOf(PlanMeasurement(id = "meas-1", page = 0, first = PlanPoint(0.0, 0.0), second = PlanPoint(10.0, 0.0))),
            sheetRevisions = mapOf(0 to SheetRevision("E101", "A", 1)),
            scaleLocked = false,
        )
        val existing = Project(
            id = "proj-1",
            name = "Main Job",
            note = "Field note",
            createdAt = 100L,
            updatedAt = 200L,
            runs = listOf(Run(id = sharedRunId, name = "Feeder 1", items = listOf(sharedItemExisting, localOnlyItem))),
            panels = listOf(Panel(id = "panel-1", name = "LP-1", spaces = 24, circuits = listOf(sharedCircuit))),
            groups = listOf(CircuitGroup(id = "grp-1", name = "Lighting")),
            drawings = listOf(existingDrawing),
        )

        val incomingUpdatedItem = sharedItemExisting.copy(title = "Updated Wall", updatedAt = 250L)
        val incomingNewItem = ProjectItem(id = "item-new", toolId = "foot-inch", title = "Incoming Item", createdAt = 30L, updatedAt = 30L)
        val incomingNewRun = Run(id = "run-new", name = "Branch 2", raceway = Raceway(ConduitType.EMT), items = listOf(
            ProjectItem(id = "item-branch", toolId = "conduit-fill", title = "EMT Run", createdAt = 40L),
        ))
        val incomingCircuitUpdated = sharedCircuit.copy(
            description = "Receptacles",
            loadVa = 1800.0,
            runIds = listOf("run-new"),
            groupIds = listOf("grp-2"),
        )
        val incomingNewCircuit = Circuit(id = "cir-2", slot = 3, breakerAmps = 20, description = "HVAC")
        val incomingNewPanel = Panel(id = "panel-2", name = "MDP", spaces = 42)
        val incomingDrawing = PlanDrawing(
            id = "drw-1",
            name = "E101.pdf",
            sha256 = drawingHash,
            marks = listOf(PlanMark(id = "mark-2", page = 0, kind = PlanMarkKind.TEXT, points = listOf(PlanPoint(5.0, 6.0)), text = "Remote")),
            measurements = listOf(PlanMeasurement(id = "meas-2", page = 1, first = PlanPoint(0.0, 0.0), second = PlanPoint(20.0, 0.0))),
            sheetRevisions = mapOf(0 to SheetRevision("E101", "B", 2), 1 to SheetRevision("E102", "A", 1)),
            scaleLocked = true,
        )
        val incomingSecondDrawing = PlanDrawing(id = "drw-2", name = "E201.pdf", sha256 = "e".repeat(64))
        val incoming = Project(
            id = "proj-1",
            name = "Main Job Remote",
            createdAt = 150L,
            updatedAt = 300L,
            runs = listOf(Run(id = sharedRunId, name = "Feeder 1", items = listOf(incomingUpdatedItem, incomingNewItem)), incomingNewRun),
            panels = listOf(Panel(id = "panel-1", name = "LP-1", spaces = 42, circuits = listOf(incomingCircuitUpdated, incomingNewCircuit)), incomingNewPanel),
            groups = listOf(CircuitGroup(id = "grp-2", name = "HVAC")),
            drawings = listOf(incomingDrawing, incomingSecondDrawing),
            codeEdition = "NEC_2023",
            codeRegion = "us-ca",
        )

        val merged = ProjectMerge.mergeSingleProject(existing, incoming, now = 500L)

        assertEquals("proj-1", merged.id)
        assertEquals("Main Job", merged.name)
        assertEquals("Field note", merged.note)
        assertEquals(100L, merged.createdAt)
        assertEquals(500L, merged.updatedAt)
        assertEquals("NEC_2023", merged.codeEdition)
        assertEquals("us-ca", merged.codeRegion)

        // Runs & items preserved and merged additively
        assertEquals(2, merged.runs.size)
        val mergedSharedRun = merged.run(sharedRunId)!!
        assertEquals(listOf("Updated Wall", "Local Only", "Incoming Item"), mergedSharedRun.items.map { it.title })
        assertEquals(1, merged.run("run-new")!!.items.size)

        // Panels & circuits merged additively
        assertEquals(listOf("panel-1", "panel-2"), merged.panels.map { it.id })
        val mergedPanel1 = merged.panel("panel-1")!!
        assertEquals(42, mergedPanel1.spaces)
        assertEquals(2, mergedPanel1.circuits.size)
        val mergedCir1 = mergedPanel1.circuits.first { it.id == "cir-1" }
        assertEquals("Receptacles", mergedCir1.description)
        assertEquals(1800.0, mergedCir1.loadVa!!, 0.0)
        assertEquals(listOf(sharedRunId, "run-new"), mergedCir1.runIds)
        assertEquals(listOf("grp-1", "grp-2"), mergedCir1.groupIds)

        // Groups merged additively
        assertEquals(listOf("grp-1", "grp-2"), merged.groups.map { it.id })

        // Drawings merged additively without losing existing page revision or marks
        assertEquals(2, merged.drawings.size)
        val mergedDrw1 = merged.drawings.first { it.id == "drw-1" }
        assertEquals(listOf("mark-1", "mark-2"), mergedDrw1.marks.map { it.id })
        assertEquals(listOf("meas-1", "meas-2"), mergedDrw1.measurements.map { it.id })
        assertEquals("A", mergedDrw1.sheetRevisions[0]?.revision)
        assertEquals("E102", mergedDrw1.sheetRevisions[1]?.sheetId)
        assertTrue(mergedDrw1.scaleLocked)
        assertNotEquals(merged.drawings[0].id, merged.drawings[1].id)
    }
}

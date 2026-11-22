package com.thedirepack.constructioncalc.core.model

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class ProjectCodecTest {
    @Serializable
    data class DemoInputs(val length: StoredQuantity, val note: String = "")

    private fun sample(): Project {
        val length = Quantity.inches(Rational.of(191, 2))
        val item = ProjectItem(
            toolId = "foot-inch",
            title = "Wall",
            createdAt = 1_000,
            inputs = ToolInputs.encode(DemoInputs(length.stored(), "north")),
            results = listOf(ResultValue.of("result", "Result", length, "7' 11 1/2\"")),
            summary = "5' 3 1/2\" + 2' 8\" = 7' 11 1/2\"",
        )
        return Project.create("Smith Residence", now = 1_000).let { p -> p.addItem(p.runs.first().id, item, 2_000) }
    }

    @Test fun roundTrips() {
        val project = sample()
        assertEquals(project, ProjectCodec.decode(ProjectCodec.encode(project, exportedAt = 3_000)))
    }

    @Test fun projectAndBackupPreserveClearedRatingsAndLegacyDefaults() {
        val project = sample().copy(
            panels = listOf(Panel(name = "LP", busAmps = null, circuits = listOf(Circuit(slot = 1, breakerAmps = null)))),
        )
        val encoded = ProjectCodec.encode(project)
        assertEquals(project, ProjectCodec.decode(encoded))
        val backup = BackupDocument(projects = listOf(project))
        assertEquals(AppFile.Backup(backup), AppFileCodec.decode(AppFileCodec.encodeBackup(backup)))

        val legacy = encoded.lineSequence().filterNot { "\"busAmps\": null" in it || "\"breakerAmps\": null" in it }.joinToString("\n")
        val panel = ProjectCodec.decode(legacy).panels.single()
        assertEquals(225, panel.busAmps)
        assertEquals(20, panel.circuits.single().breakerAmps)
    }

    @Test fun toolInputsRoundTripAndResultsKeepExactValues() {
        val decoded = ProjectCodec.decode(ProjectCodec.encode(sample()))
        val item = decoded.runs.first().items.first()
        val inputs = ToolInputs.decode<DemoInputs>(item.inputs)!!
        assertEquals(Rational.of(191, 2), inputs.length.toQuantity().value)
        assertEquals(Quantity.inches(Rational.of(191, 2)), item.results.first().toQuantity())
    }

    @Test fun ignoresUnknownFieldsFromNewerVersions() {
        val text = ProjectCodec.encode(sample())
            .replace("\"format\"", "\"future_field\": {\"x\": 1},\n  \"format\"")
            .replace("\"version\": 1", "\"version\": 7")
        assertEquals("Smith Residence", ProjectCodec.decode(text).name)
    }

    @Test fun rejectsOtherFiles() {
        listOf("{}", "not json", """{"format":"something-else","project":{"name":"x","createdAt":1}}""").forEach {
            try {
                ProjectCodec.decode(it)
                throw AssertionError("Expected failure for $it")
            } catch (_: ProjectFormatException) {
            }
        }
    }

    @Test fun undecodableInputsReturnNull() {
        val item = sample().runs.first().items.first()
        assertNull(ToolInputs.decode<Map<String, Int>>(item.inputs))
    }

    @Test fun fileNameIsSafe() {
        assertEquals("A B C.project.json", ProjectCodec.fileName(Project.create("A/B:C", 0)))
    }

    @Test fun editsAndMoves() {
        var p = sample()
        val itemId = p.runs.first().items.first().id
        val run2 = Run(name = "Panel A")
        p = p.addRun(run2, 5).moveItem(itemId, run2.id, 0, 6)
        assertTrue(p.runs.first().items.isEmpty())
        assertEquals(itemId, p.run(run2.id)!!.items.single().id)
        assertEquals(6, p.updatedAt)
        val copy = p.duplicated(now = 7)
        assertNotEquals(p.id, copy.id)
        assertNotEquals(itemId, copy.runs[1].items.single().id)
    }

    @Test fun rendersText() {
        val text = ProjectText.render(sample(), toolTitle = { "Foot-Inch" }, zone = ZoneOffset.UTC)
        assertTrue(text, text.contains("■ General"))
        assertTrue(text, text.contains("• Wall  [Foot-Inch]"))
        assertTrue(text, text.contains("5' 3 1/2\" + 2' 8\" = 7' 11 1/2\""))
    }

    @Test fun sharedTextIncludesPanelsCircuitsAndConduits() {
        val run = Run(name = "Home run", raceway = Raceway(com.thedirepack.constructioncalc.core.electrical.ConduitType.EMT, length = com.thedirepack.constructioncalc.core.measure.Quantity.feet(com.thedirepack.constructioncalc.core.math.Rational.of(50)).stored()))
        val c = Circuit(slot = 1, breakerAmps = 20, loadVa = 1200.0, description = "Kitchen", runIds = listOf(run.id))
        val project = Project(name = "Job", createdAt = 0, runs = listOf(run), panels = listOf(Panel(name = "LP-1", circuits = listOf(c))))
        val text = ProjectText.render(project, zone = ZoneOffset.UTC)
        assertTrue(text, "PANELS" in text)
        assertTrue(text, "■ LP-1 — 208Y/120 V 3φ 4W" in text)
        assertTrue(text, "1 · 20/1 · Kitchen · 1,200 VA · 2#12, 1#12G" in text)
        assertTrue(text, "Conduit: 1/2\" EMT (auto) · 50' 0\" · 1 circuit · fill" in text)
    }

    @Test fun sharedTextIncludesDrawingMeasurementSummaries() {
        val calibration = PlanCalibrationLayer(preset = Quantity.inches(Rational.of(1)).stored())
        val m1 = PlanMeasurement(page = 0, first = PlanPoint(0.0, 0.0), second = PlanPoint(120.0, 0.0))
        val m2 = PlanMeasurement(page = 1, first = PlanPoint(0.0, 0.0), second = PlanPoint(60.0, 0.0))
        val drawing = PlanDrawing(
            name = "E101.pdf",
            sha256 = "a".repeat(64),
            calibration = calibration,
            measurements = listOf(m1, m2),
        )
        val project = Project.create("Job", now = 0).copy(drawings = listOf(drawing))
        val text = ProjectText.render(project, zone = ZoneOffset.UTC)
        assertTrue(text, "DRAWINGS" in text)
        assertTrue(text, "■ E101.pdf — 2 measurements · total 15' 0\"" in text)
        assertTrue(text, "Page 1: 10' 0\"" in text)
        assertTrue(text, "Page 2: 5' 0\"" in text)
    }
}

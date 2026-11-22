package com.thedirepack.constructioncalc.core.model

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SheetRevisionTest {
    private val early = PlanDrawing(
        name = "Issue one.pdf",
        sha256 = "1".repeat(64),
        sheetRevisions = mapOf(0 to SheetRevision("E101", "P4", 1)),
    )
    private val late = PlanDrawing(
        name = "Issue two.pdf",
        sha256 = "2".repeat(64),
        sheetRevisions = mapOf(2 to SheetRevision("e101", "A", 2)),
    )
    private val project = Project.create("Job", now = 1).copy(drawings = listOf(late, early))

    @Test fun issueOrderRanksTheSameSheetAcrossPdfFilesAndPagePositions() {
        assertEquals(listOf(late.sheetSource(2), early.sheetSource(0)), project.issuedSheets(" E101 ").map { it.source })
        assertEquals(SheetRevisionStatus.LATEST, project.sheetRevisionStatus(late, 2))
        assertEquals(SheetRevisionStatus.SUPERSEDED, project.sheetRevisionStatus(early, 0))
        assertNull(project.sheetRevisionStatus(early, 1))
        assertEquals("Page 2", early.sheetSource(1).label)
    }

    @Test fun tiedIssueOrdersFromImportedMetadataAreExplicit() {
        val tied = late.copy(sheetRevisions = mapOf(2 to SheetRevision("E101", "A", 1)))
        val imported = project.copy(drawings = listOf(early, tied))
        assertEquals(SheetRevisionStatus.ISSUE_ORDER_TIED, imported.sheetRevisionStatus(tied, 2))
    }

    @Test fun projectCodecPreservesPublishedSourceAndMeasurementRevision() {
        val measurement = PlanMeasurement(page = 0, first = PlanPoint(1.0, 1.0), second = PlanPoint(2.0, 2.0))
        val old = early.copy(measurements = listOf(measurement))
        val source = old.sheetSource(0)
        val result = ResultValue.of(measurement.id, "Distance", Quantity.inches(Rational.ONE), "1 in")
            .copy(planSource = source)
        val item = ProjectItem(toolId = "drawings", title = "Plan distance", createdAt = 1, results = listOf(result))
        val stored = project.copy(drawings = listOf(late, old)).addItem(project.runs.single().id, item, 1)

        val reopened = ProjectCodec.decode(ProjectCodec.encode(stored))
        assertEquals(stored, reopened)
        assertEquals(listOf(measurement), reopened.drawings.last().measurements)
        assertEquals(source, reopened.runs.single().items.single().results.single().planSource)
        assertEquals(SheetRevisionStatus.SUPERSEDED, reopened.sheetRevisionStatus(reopened.drawings.last(), 0))
    }
}

package com.thedirepack.constructioncalc.feature.drawings

import com.thedirepack.constructioncalc.core.model.PlanMark
import com.thedirepack.constructioncalc.core.model.PlanMarkKind
import com.thedirepack.constructioncalc.core.model.PlanPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanvasEditingTest {
    @Test fun selectsTopMarkupAndHitsInkAlongItsPath() {
        val line = PlanMark(page = 0, kind = PlanMarkKind.INK, points = listOf(PlanPoint(0.0, 0.0), PlanPoint(80.0, 0.0)))
        val overlap = line.copy(id = "top")
        assertEquals(overlap, markAt(listOf(line, overlap), PlanPoint(40.0, 3.0), 3.0))
        assertNull(markAt(listOf(line), PlanPoint(40.0, 15.0), 3.0))
    }

    @Test fun draggingAnAlternateRectangleCornerKeepsTheOppositeCorner() {
        val first = PlanPoint(10.0, 20.0)
        val end = PlanPoint(50.0, 60.0)
        val mark = PlanMark(page = 0, kind = PlanMarkKind.RECTANGLE, points = listOf(first, end))
        assertEquals(PlanPoint(10.0, 60.0), mark.editHandles()[2])
        assertEquals(
            listOf(PlanPoint(5.0, 20.0), PlanPoint(50.0, 75.0)),
            mark.resize(2, PlanPoint(5.0, 75.0)).points,
        )
    }

    @Test fun movingCalloutKeepsLeaderTipOnTheDevice() {
        val anchor = PlanPoint(10.0, 20.0)
        val box = PlanPoint(50.0, 60.0)
        val mark = PlanMark(page = 0, kind = PlanMarkKind.CALLOUT, points = listOf(anchor, box), text = "5R4-B-36")
        assertEquals(listOf(anchor, PlanPoint(55.0, 56.0)), mark.move(5.0, -4.0).points)
        assertEquals(listOf(PlanPoint(3.0, 4.0), box), mark.resize(0, PlanPoint(3.0, 4.0)).points)
    }

    @Test fun findsSheetIdentifierAndTitleInPdfText() {
        val label = titleBlockLabel("ISSUED 09/08/25\nSHEET TITLE:\nPOWER\nPLAN\nSHEET NUMBER:\nEP101\n")
        assertEquals(SheetLabel("EP101", "POWER PLAN"), label)
        assertEquals(true, label?.matches("power"))
        assertNull(titleBlockLabel("No title block"))
    }

    @Test fun repeatedPdfPageLabelsDoNotHidePrintedSheetNumbers() {
        val staleLabel = "A-601 - DOOR - WINDOW & FINISH SCHEDULE"
        val title = titleBlockLabel("SHEET TITLE:\nPOWER\nPLAN\nSHEET NUMBER:\nEP101\n")
        val repeatedLabels = setOf(staleLabel)

        val ep101 = resolveSheetLabel(staleLabel, repeatedLabels, title)
        assertEquals(SheetLabel("EP101", "POWER PLAN"), ep101)
        assertEquals(true, ep101?.matches("EP101"))
        assertEquals(true, ep101?.matches("POWER PLAN"))
        assertNull(resolveSheetLabel(staleLabel, repeatedLabels, null))
        assertEquals(
            SheetLabel("A-102 - DIMENSION PLANS", "DIMENSION PLANS"),
            resolveSheetLabel("A-102 - DIMENSION PLANS", repeatedLabels, SheetLabel("A-102", "DIMENSION PLANS")),
        )
    }
}

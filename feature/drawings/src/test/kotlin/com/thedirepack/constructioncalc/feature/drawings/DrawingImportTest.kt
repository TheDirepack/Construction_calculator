package com.thedirepack.constructioncalc.feature.drawings

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.PlanCalibrationLayer
import com.thedirepack.constructioncalc.core.model.PlanDrawing
import com.thedirepack.constructioncalc.core.model.PlanMark
import com.thedirepack.constructioncalc.core.model.PlanMarkKind
import com.thedirepack.constructioncalc.core.model.PlanMeasurement
import com.thedirepack.constructioncalc.core.model.PlanPath
import com.thedirepack.constructioncalc.core.model.PlanPoint
import com.thedirepack.constructioncalc.core.model.PlanReference
import com.thedirepack.constructioncalc.core.model.PlanRunRoute
import com.thedirepack.constructioncalc.core.model.PlanZone
import com.thedirepack.constructioncalc.core.model.SheetRevision
import com.thedirepack.constructioncalc.core.model.stored
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawingImportTest {
    private val sha = "a".repeat(64)

    @Test
    fun uniqueImportedDrawingNameKeepsNonCollidingNameUnchanged() {
        assertEquals(
            "E102 Lighting.pdf",
            uniqueImportedDrawingName("E102 Lighting.pdf", listOf("E101 Power.pdf", "A101 Floor Plan.pdf")),
        )
    }

    @Test
    fun uniqueImportedDrawingNameAppendsCopyBeforePdfExtension() {
        assertEquals(
            "E101 Power (copy).pdf",
            uniqueImportedDrawingName("E101 Power.pdf", listOf("e101 power.pdf")),
        )
        assertEquals(
            "E101 Power (copy 2).pdf",
            uniqueImportedDrawingName("E101 Power.pdf", listOf("E101 Power.pdf", "e101 power (copy).pdf")),
        )
        assertEquals(
            "E101 Power (copy 3).pdf",
            uniqueImportedDrawingName(
                "E101 Power.pdf",
                listOf("E101 Power.pdf", "E101 Power (copy).pdf", "E101 Power (copy 2).pdf"),
            ),
        )
    }

    @Test
    fun uniqueImportedDrawingNamePreservesNonPdfNamesAndRespectsMaxLength() {
        assertEquals(
            "Sheet A1.1 (copy)",
            uniqueImportedDrawingName("Sheet A1.1", listOf("sheet a1.1")),
        )
        assertEquals(
            "Drawing",
            uniqueImportedDrawingName("   ", emptyList()),
        )

        val longName = "X".repeat(240)
        val result = uniqueImportedDrawingName(longName, listOf(longName))
        assertTrue(result.length <= 240)
        assertTrue(result.endsWith(" (copy)"))

        val longPdfName = "Y".repeat(236) + ".pdf"
        val pdfResult = uniqueImportedDrawingName(longPdfName, listOf(longPdfName))
        assertTrue(pdfResult.length <= 240)
        assertTrue(pdfResult.endsWith(" (copy).pdf"))
    }

    @Test
    fun drawingRowSubtitleSummarizesEmptyAndCalibratedAnnotatedDrawings() {
        val empty = PlanDrawing(name = "Blank.pdf", sha256 = sha)
        assertEquals("Uncalibrated · No markups or measurements", drawingRowSubtitle(empty))

        val checkOnly = PlanDrawing(
            name = "CheckOnly.pdf",
            sha256 = sha,
            calibration = PlanCalibrationLayer(
                references = listOf(
                    PlanReference(
                        PlanPoint(0.0, 0.0),
                        PlanPoint(100.0, 0.0),
                        Quantity.inches(Rational.of(120)).stored(),
                        check = true,
                    ),
                ),
            ),
        )
        assertEquals("Uncalibrated · No markups or measurements", drawingRowSubtitle(checkOnly))

        val rich = PlanDrawing(
            name = "Annotated.pdf",
            sha256 = sha,
            calibration = PlanCalibrationLayer(preset = Quantity.inches(Rational.of(1)).stored()),
            measurements = listOf(
                PlanMeasurement(page = 0, first = PlanPoint(0.0, 0.0), second = PlanPoint(10.0, 0.0)),
            ),
            marks = listOf(
                PlanMark(page = 0, kind = PlanMarkKind.TEXT, points = listOf(PlanPoint(5.0, 5.0)), text = "Panel A"),
                PlanMark(page = 0, kind = PlanMarkKind.TEXT, points = listOf(PlanPoint(8.0, 8.0)), text = "Panel B"),
            ),
            routes = listOf(
                PlanRunRoute(runId = "run-1", page = 0, points = listOf(PlanPoint(0.0, 0.0), PlanPoint(20.0, 0.0))),
            ),
            paths = listOf(
                PlanPath(page = 0, points = listOf(PlanPoint(1.0, 1.0), PlanPoint(9.0, 1.0))),
            ),
            zones = listOf(
                PlanZone(name = "Enlarged", page = 0, first = PlanPoint(0.0, 0.0), second = PlanPoint(50.0, 50.0)),
            ),
            sheetRevisions = mapOf(
                0 to SheetRevision("E101", "A", 1),
                1 to SheetRevision("E102", "B", 2),
            ),
        )
        assertEquals(
            "Calibrated · 1 measurement · 2 markups · 1 route · 1 path · 1 zone · 2 sheet revs",
            drawingRowSubtitle(rich),
        )
    }
}

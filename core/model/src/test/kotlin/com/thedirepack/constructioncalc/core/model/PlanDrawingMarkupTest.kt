package com.thedirepack.constructioncalc.core.model

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PlanDrawingMarkupTest {
    private val sha = "a".repeat(64)
    private val photoHash = "b".repeat(64)
    private fun p(x: Double, y: Double) = PlanPoint(x, y)
    private fun inches(n: Long) = Quantity.inches(Rational.of(n)).stored()

    @Test fun decodesLegacyPlanMarkWithoutNewFields() {
        val legacyJson = """
            {
              "format": "construction-calc/project",
              "version": 1,
              "project": {
                "id": "p1",
                "name": "Job",
                "createdAt": 1,
                "drawings": [
                  {
                    "id": "d1",
                    "name": "E1.pdf",
                    "sha256": "$sha",
                    "marks": [
                      {
                        "id": "m1",
                        "page": 0,
                        "kind": "LINE",
                        "points": [{"x": 0.0, "y": 0.0}, {"x": 10.0, "y": 0.0}]
                      }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()
        val decoded = ProjectCodec.decode(legacyJson)
        val mark = decoded.drawings.single().marks.single()
        assertEquals(PlanMarkKind.LINE, mark.kind)
        assertEquals(1f, mark.opacity, 1e-6f)
        assertEquals(PlanDashStyle.SOLID, mark.dash)
        assertNull(mark.arrowStyle)
        assertNull(mark.fillColor)
        assertEquals(0f, mark.fillOpacity, 1e-6f)
        assertEquals("", mark.areaTag)
        assertNull(mark.photoSha256)
        assertNull(mark.targetPage)
        assertNull(mark.targetDrawingId)
    }

    @Test fun roundTripsExtendedMarkupFieldsAndPhotoHashes() {
        val marks = listOf(
            PlanMark(
                id = "dim",
                page = 0,
                kind = PlanMarkKind.DIMENSION,
                points = listOf(p(0.0, 0.0), p(50.0, 0.0)),
                text = "Clearance",
                width = 2.5,
                color = 0xFFC62828L,
                opacity = 0.85f,
                dash = PlanDashStyle.DASHED,
                arrowStyle = PlanArrowStyle.FILLED_BOTH,
            ),
            PlanMark(
                id = "cloud",
                page = 0,
                kind = PlanMarkKind.CLOUD,
                points = listOf(p(10.0, 10.0), p(80.0, 60.0)),
                color = 0xFFE65100L,
                opacity = 0.9f,
                dash = PlanDashStyle.SOLID,
                fillColor = 0xFFE65100L,
                fillOpacity = 0.2f,
            ),
            PlanMark(
                id = "cc",
                page = 0,
                kind = PlanMarkKind.CLOUD_CALLOUT,
                points = listOf(p(10.0, 10.0), p(80.0, 60.0), p(110.0, 20.0)),
                text = "RFI-12",
                width = 13.0,
                fillColor = 0xFFFFFFFFL,
                fillOpacity = 0.95f,
            ),
            PlanMark(
                id = "sym",
                page = 0,
                kind = PlanMarkKind.SYMBOL,
                points = listOf(p(20.0, 20.0)),
                symbol = PlanSymbolPlacement(
                    deviceId = "receptacle.duplex_gfci",
                    size = 18.0,
                    rotationDegrees = 90.0,
                    source = PlanSymbolSource.DETECTED,
                    confidence = 0.82f,
                    reviewState = PlanReviewState.PENDING,
                ),
                circuitTag = "5R4-B-36",
                areaTag = "Room 104",
            ),
            PlanMark(
                id = "ph",
                page = 1,
                kind = PlanMarkKind.PHOTO,
                points = listOf(p(30.0, 40.0)),
                text = "Main gear panel",
                photoSha256 = photoHash,
            ),
            PlanMark(
                id = "det",
                page = 1,
                kind = PlanMarkKind.DETAIL_LINK,
                points = listOf(p(50.0, 60.0)),
                text = "Detail E501",
                targetPage = 4,
                targetDrawingId = "d2",
            ),
        )
        val drawing = PlanDrawing(id = "d1", name = "Plans.pdf", sha256 = sha, marks = marks)
        assertEquals(setOf(photoHash), drawing.referencedPhotoHashes())

        val project = Project.create("Test", 10L).copy(drawings = listOf(drawing))
        val restored = ProjectCodec.decode(ProjectCodec.encode(project, 20L))
        assertEquals(drawing, restored.drawings.single())
    }

    @Test fun validatesExtendedMarkupBounds() {
        assertThrows(IllegalArgumentException::class.java) {
            PlanMark(page = 0, kind = PlanMarkKind.INK, points = listOf(p(0.0, 0.0)), opacity = 0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlanMark(page = 0, kind = PlanMarkKind.INK, points = listOf(p(0.0, 0.0)), opacity = 1.5f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlanMark(page = 0, kind = PlanMarkKind.RECTANGLE, points = listOf(p(0.0, 0.0), p(10.0, 10.0)), fillOpacity = 1.1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlanMark(page = 0, kind = PlanMarkKind.PHOTO, points = listOf(p(0.0, 0.0)), photoSha256 = "../bad")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlanMark(page = 0, kind = PlanMarkKind.DETAIL_LINK, points = listOf(p(0.0, 0.0)), targetPage = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlanMark(page = 0, kind = PlanMarkKind.DETAIL_LINK, points = listOf(p(0.0, 0.0)), targetDrawingId = "  ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlanMark(page = 0, kind = PlanMarkKind.SYMBOL, points = listOf(p(0.0, 0.0)), symbol = PlanSymbolPlacement("switch.dimmer"), areaTag = "x".repeat(81))
        }
        // A symbol mark always carries its placement, and nothing else does.
        assertThrows(IllegalArgumentException::class.java) {
            PlanMark(page = 0, kind = PlanMarkKind.SYMBOL, points = listOf(p(0.0, 0.0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlanMark(page = 0, kind = PlanMarkKind.TEXT, points = listOf(p(0.0, 0.0)), symbol = PlanSymbolPlacement("switch.dimmer"))
        }
        assertThrows(IllegalArgumentException::class.java) { PlanSymbolPlacement("switch.dimmer", rotationDegrees = 360.0) }
        assertThrows(IllegalArgumentException::class.java) { PlanSymbolPlacement("switch.dimmer", confidence = 1.5f) }
        assertThrows(IllegalArgumentException::class.java) { PlanSymbolPlacement("switch.dimmer", size = 0.0) }
    }

    @Test fun dimensionLengthUsesCalibrationAndRejectsBoundaryCrossings() {
        val zone = PlanZone(id = "z", name = "Enlarged", page = 0, first = p(200.0, 200.0), second = p(300.0, 300.0), preset = inches(2))
        val drawing = PlanDrawing(
            name = "E1.pdf",
            sha256 = sha,
            calibration = PlanCalibrationLayer(preset = inches(1)),
            zones = listOf(zone),
        )
        val docDim = PlanMark(page = 0, kind = PlanMarkKind.DIMENSION, points = listOf(p(0.0, 0.0), p(30.0, 40.0)))
        assertEquals(Rational.of(50), drawing.dimensionLength(docDim)!!.value)

        val zoneDim = PlanMark(page = 0, kind = PlanMarkKind.DIMENSION, points = listOf(p(210.0, 210.0), p(250.0, 210.0)))
        assertEquals(Rational.of(80), drawing.dimensionLength(zoneDim)!!.value)

        val crossingDim = PlanMark(page = 0, kind = PlanMarkKind.DIMENSION, points = listOf(p(100.0, 250.0), p(250.0, 250.0)))
        assertNull(drawing.dimensionLength(crossingDim))

        val lineMark = PlanMark(page = 0, kind = PlanMarkKind.LINE, points = listOf(p(0.0, 0.0), p(30.0, 40.0)))
        assertNull(drawing.dimensionLength(lineMark))
    }
}

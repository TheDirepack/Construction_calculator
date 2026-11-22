package com.thedirepack.constructioncalc.core.plans

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nu.pattern.OpenCV
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class LineMapTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            OpenCV.loadLocally()
        }
    }

    @Test
    fun snapsToCenterOrNearestEdgeAndCorners() {
        val horizontal = PlanLine(10.0, 50.0, 190.0, 50.0, width = 8.0)
        val vertical = PlanLine(50.0, 10.0, 50.0, 190.0, width = 6.0)
        val map = LineMap(200.0, 200.0, listOf(horizontal, vertical), LineSource.VECTOR, 8.0)

        // Tap below horizontal wall -> center is y=50, nearest edge is y=54.
        val center = map.snap(120.0, 60.0, radius = 16.0, target = SnapTarget.CENTER)!!
        assertEquals(SnapKind.LINE, center.kind)
        assertEquals(120.0, center.x, 1e-6)
        assertEquals(50.0, center.y, 1e-6)

        val lowerEdge = map.snap(120.0, 60.0, radius = 16.0, target = SnapTarget.EDGE)!!
        assertEquals(SnapKind.LINE, lowerEdge.kind)
        assertEquals(54.0, lowerEdge.y, 1e-6)

        // Tap above horizontal wall -> nearest edge is y=46.
        val upperEdge = map.snap(120.0, 42.0, radius = 16.0, target = SnapTarget.EDGE)!!
        assertEquals(46.0, upperEdge.y, 1e-6)

        // Tap near the lower-right inner corner -> center corner is (50, 50), edge corner is (53, 54).
        val centerCorner = map.snap(57.0, 58.0, radius = 20.0, target = SnapTarget.CENTER)!!
        assertEquals(SnapKind.CORNER, centerCorner.kind)
        assertEquals(50.0, centerCorner.x, 1e-6)
        assertEquals(50.0, centerCorner.y, 1e-6)

        val edgeCorner = map.snap(57.0, 58.0, radius = 20.0, target = SnapTarget.EDGE)!!
        assertEquals(SnapKind.CORNER, edgeCorner.kind)
        assertEquals(53.0, edgeCorner.x, 1e-6)
        assertEquals(54.0, edgeCorner.y, 1e-6)
    }

    @Test
    fun mergesCollinearPiecesFarFromOriginAndDropsShortStrokes() {
        val merged = mergeLines(
            listOf(
                PlanLine(1000.0, 1000.0, 1100.0, 1000.0, 1.0),
                PlanLine(1100.0, 1000.0, 1200.0, 1000.04, 1.0),
                PlanLine(500.0, 500.0, 504.0, 500.0, 1.0), // shorter than minimumLength
            ),
            minimumLength = 8.0,
        )
        assertEquals(1, merged.size)
        assertEquals(200.0, merged[0].length, 0.2)
    }

    @Test
    fun roundTripsThroughJsonWithSpatialIndexRebuilt() {
        val map = LineMap(
            612.0,
            792.0,
            listOf(PlanLine(20.0, 100.0, 400.0, 100.0, 2.0)),
            LineSource.VECTOR,
            LineMap.DEFAULT_MINIMUM_LENGTH,
        )
        val encoded = Json.encodeToString(map)
        val decoded = Json.decodeFromString<LineMap>(encoded)
        val snap = decoded.snap(150.0, 105.0, radius = 12.0, target = SnapTarget.EDGE)
        assertNotNull(snap)
        assertEquals(101.0, snap!!.y, 1e-6)
    }

    @Test
    fun vectorCollectorHandlesCropBoxRotationAndThinFilledRectangles() {
        // 100x200 crop box rotated 90° clockwise -> displayed size is 200x100.
        val collector = VectorPathCollector(doubleArrayOf(10.0, 20.0, 110.0, 220.0), rotate = 90)
        assertEquals(200.0, collector.width, 1e-6)
        assertEquals(100.0, collector.height, 1e-6)
        // Thin filled rectangle along y (length 80, thickness 2) in PDF space -> horizontal in rotated display.
        collector.rectangle(30.0, 40.0, 32.0, 40.0, 32.0, 120.0, 30.0, 120.0)
        collector.fill()
        val map = collector.lineMap(minimumLength = 8.0)
        assertEquals(1, map.lines.size)
        assertEquals(80.0, map.lines[0].length, 0.2)
        assertEquals(2.0, map.lines[0].width, 0.2)
    }

    @Test
    fun rasterLinesFindThinThickAngledAndCurvedWallsAndIgnoreTextAndFills() {
        val image = draw(width = 320, height = 320) {
            stroke = BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)
            draw(Line2D.Double(20.0, 50.4, 290.0, 50.4))
            stroke = BasicStroke(7.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)
            draw(Line2D.Double(60.5, 90.0, 60.5, 290.0))
            // Solid fill box: should not produce a line on its interior.
            fillRect(200, 180, 90, 90)
            // Small text: should not produce snappable wall lines.
            font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
            drawString("12'-6\" TYP", 110f, 130f)
        }
        val map = RasterLines.find(image, pointsPerPixel = 0.5, minimumLength = 12.0)
        assertEquals(LineSource.RASTER, map.source)
        // Thin horizontal line at y = 50.4 px = 25.2 pt.
        val thinCenter = map.snap(75.0, 28.0, radius = 8.0, target = SnapTarget.CENTER)
        assertNotNull("thin line not found", thinCenter)
        assertEquals(25.2, thinCenter!!.y, 0.2)

        // Thick vertical wall at x = 60.5 px (30.25 pt), width 7 px (3.5 pt) -> right edge = 32.0 pt.
        val thickCenter = map.snap(35.0, 90.0, radius = 10.0, target = SnapTarget.CENTER)
        assertNotNull("thick wall not found", thickCenter)
        assertEquals(30.25, thickCenter!!.x, 0.25)
        val thickEdge = map.snap(35.0, 90.0, radius = 10.0, target = SnapTarget.EDGE)!!
        assertEquals(32.0, thickEdge.x, 0.4)

        // Interior of solid fill does not snap.
        assertNull(map.snap(122.0, 112.0, radius = 8.0, target = SnapTarget.CENTER))

        // Small lettering does not snap.
        assertNull(map.snap(65.0, 63.0, radius = 4.0, target = SnapTarget.CENTER))
    }

    @Test
    fun rasterLinesHandleLightLinesOnDarkSheetAndCurvedPhotoLines() {
        val darkSheet = draw(width = 240, height = 240, paper = Color(20, 35, 85), ink = Color.WHITE) {
            stroke = BasicStroke(2.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)
            draw(Line2D.Double(20.0, 120.3, 220.0, 120.3))
        }
        val darkMap = RasterLines.find(darkSheet, pointsPerPixel = 1.0, minimumLength = 12.0)
        val darkSnap = darkMap.snap(120.0, 126.0, radius = 12.0, target = SnapTarget.CENTER)
        assertNotNull(darkSnap)
        assertEquals(120.3, darkSnap!!.y, 0.3)

        // Gently curved wall (simulating paper curl across a sheet).
        val curve = { x: Double -> 120.0 + 3.0 * sin(x / 110.0) }
        val curved = draw(width = 300, height = 240) {
            val path = Path2D.Double()
            path.moveTo(20.0, curve(20.0))
            for (x in 21..280) path.lineTo(x.toDouble(), curve(x.toDouble()))
            stroke = BasicStroke(2.5f)
            draw(path)
        }
        val curvedMap = RasterLines.find(curved, pointsPerPixel = 1.0, minimumLength = 12.0)
        for (x in listOf(70.0, 150.0, 230.0)) {
            val snap = curvedMap.snap(x, curve(x) + 6.0, radius = 14.0, target = SnapTarget.CENTER)
            assertNotNull("curved snap at x=$x", snap)
            assertEquals("curved y at x=$x", curve(snap!!.x), snap.y, 0.75)
        }
    }
}

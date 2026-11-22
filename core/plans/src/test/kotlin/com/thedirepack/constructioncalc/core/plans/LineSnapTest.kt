package com.thedirepack.constructioncalc.core.plans

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class LineSnapTest {
    @Test
    fun thinLineSnapsToItsCenter() {
        val image = draw { line(10.0, 40.3, 190.0, 40.3, 1f) }
        val snap = snap(image, 100.0, 47.0)
        assertEquals(SnapKind.LINE, snap.kind)
        assertEquals(40.3, snap.y, .15)
        assertEquals(100.0, snap.x, .5)
    }

    @Test
    fun thickLineAtAnAngleSnapsToTheFootOnItsCenterline() {
        val angle = 30 * PI / 180
        val (ax, ay, bx, by) = segment(100.0, 100.0, angle, 90.0)
        val image = draw { line(ax, ay, bx, by, 6f) }
        val tapX = 100 - 9 * sin(angle)
        val tapY = 100 + 9 * cos(angle)
        val snap = snap(image, tapX, tapY, 20.0)
        assertEquals(0.0, distanceToLine(snap.x, snap.y, ax, ay, bx, by), .25)
        assertEquals(100.0, snap.x, 1.0)
        assertEquals(100.0, snap.y, 1.0)
        assertEquals(6.0, snap.width, 1.0)
    }

    @Test
    fun everyDirectionSnapsWithinAThirdOfAPixel() {
        for (degrees in 0 until 180 step 7) {
            val angle = degrees * PI / 180
            val (ax, ay, bx, by) = segment(100.0, 100.0, angle, 80.0)
            val image = draw { line(ax, ay, bx, by, 2f) }
            val snap = snap(image, 100 - 5 * sin(angle), 100 + 5 * cos(angle))
            assertEquals("at $degrees°", 0.0, distanceToLine(snap.x, snap.y, ax, ay, bx, by), .3)
        }
    }

    @Test
    fun picksTheNearerOfTwoParallelLines() {
        val image = draw {
            line(10.0, 50.0, 190.0, 50.0, 1.5f)
            line(10.0, 62.0, 190.0, 62.0, 1.5f)
        }
        assertEquals(50.0, snap(image, 100.0, 53.0).y, .2)
        assertEquals(62.0, snap(image, 100.0, 59.0).y, .2)
    }

    @Test
    fun snapsToTheCornerWhereTwoLinesMeet() {
        val image = draw {
            line(60.0, 60.0, 180.0, 60.0, 2f)
            line(60.0, 60.0, 60.0, 180.0, 2f)
        }
        val snap = snap(image, 64.0, 63.0)
        assertEquals(SnapKind.CORNER, snap.kind)
        assertEquals(60.0, snap.x, .5)
        assertEquals(60.0, snap.y, .5)
        assertEquals(SnapKind.LINE, snap(image, 120.0, 66.0).kind)
    }

    @Test
    fun blankPaperAndSolidFillDoNotSnap() {
        assertNull(LineSnapper.snap(draw { }, 100.0, 100.0, 16.0))
        val filled = draw { fill(40.0, 40.0, 160.0, 160.0) }
        assertNull(LineSnapper.snap(filled, 100.0, 100.0, 16.0))
    }

    @Test
    fun lightLinesOnADarkSheet() {
        val image = draw(paper = Color(20, 30, 90), ink = Color.WHITE) { line(10.0, 80.6, 190.0, 80.6, 2f) }
        assertEquals(80.6, snap(image, 100.0, 86.0).y, .2)
    }

    @Test
    fun faintLineOnNoisyPaper() {
        val image = draw(paper = Color(235, 235, 235), ink = Color(170, 170, 170), noise = 14) {
            line(10.0, 70.4, 190.0, 70.4, 2f)
        }
        assertEquals(70.4, snap(image, 100.0, 76.0).y, .4)
    }

    @Test
    fun followsTheCurveOfAWarpedSheet() {
        val curve = { x: Double -> 100 + 4 * sin(x / 35) }
        val image = draw {
            val path = Path2D.Double()
            path.moveTo(0.0, curve(0.0))
            for (x in 1..200) path.lineTo(x.toDouble(), curve(x.toDouble()))
            stroke = BasicStroke(2f)
            draw(path)
        }
        for (x in listOf(40.0, 80.0, 120.0, 160.0)) {
            val snap = snap(image, x, curve(x) + 6)
            assertEquals("at x=$x", curve(snap.x), snap.y, .35)
        }
    }

    @Test
    fun textBesideALineDoesNotPullTheSnap() {
        val image = draw {
            line(10.0, 60.0, 190.0, 60.0, 1.5f)
            font = Font(Font.SANS_SERIF, Font.PLAIN, 10)
            drawString("12'-6\" TYP", 80f, 76f)
        }
        val snap = snap(image, 100.0, 64.0)
        assertEquals(60.0, snap.y, .25)
    }

    @Test
    fun thickWallSnapsToItsMiddleWithoutTilting() {
        val image = draw { line(60.5, 10.0, 60.5, 190.0, 8f) }
        val snap = snap(image, 74.0, 100.0)
        assertEquals(60.5, snap.x, .2)
        assertEquals(100.0, snap.y, .5)
        assertEquals(PI / 2, snap.angle, 1.0 * PI / 180)
        assertEquals(8.0, snap.width, 1.0)
    }

    @Test
    fun thickFaintLineOnNoisyPaper() {
        val image = draw(paper = Color(230, 230, 230), ink = Color(130, 130, 130), noise = 12) {
            line(10.0, 90.3, 190.0, 90.3, 5f)
        }
        val snap = snap(image, 100.0, 80.0)
        assertEquals(90.3, snap.y, .35)
        assertEquals(0.0, snap.angle.let { if (it > PI / 2) it - PI else it }, 1.5 * PI / 180)
    }

    @Test
    fun argbConversionTreatsTransparencyAsPaper() {
        val gray = GrayImage.fromArgb(3, 1, intArrayOf(0xFF000000.toInt(), 0x00000000, 0xFFFFFFFF.toInt()))
        assertEquals(0, gray[0, 0])
        assertEquals(255, gray[1, 0])
        assertEquals(255, gray[2, 0])
    }

    private fun snap(image: GrayImage, x: Double, y: Double, radius: Double = 16.0): LineSnap {
        val snap = LineSnapper.snap(image, x, y, radius)
        assertNotNull("no snap at ($x, $y)", snap)
        assertTrue(snap!!.strength > 0)
        return snap
    }
}

internal data class Segment(val ax: Double, val ay: Double, val bx: Double, val by: Double)

internal fun segment(cx: Double, cy: Double, angle: Double, half: Double) =
    Segment(cx - half * cos(angle), cy - half * sin(angle), cx + half * cos(angle), cy + half * sin(angle))

internal fun distanceToLine(x: Double, y: Double, ax: Double, ay: Double, bx: Double, by: Double): Double =
    abs((bx - ax) * (ay - y) - (ax - x) * (by - ay)) / hypot(bx - ax, by - ay)

internal fun draw(
    width: Int = 200,
    height: Int = 200,
    paper: Color = Color.WHITE,
    ink: Color = Color.BLACK,
    noise: Int = 0,
    content: Graphics2D.() -> Unit,
): GrayImage {
    val image = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.color = paper
    g.fillRect(0, 0, width, height)
    g.color = ink
    g.content()
    g.dispose()
    val pixels = (image.raster.dataBuffer as DataBufferByte).data.copyOf()
    if (noise > 0) {
        val random = Random(7)
        for (i in pixels.indices) {
            val value = (pixels[i].toInt() and 0xff) + random.nextInt(2 * noise + 1) - noise
            pixels[i] = value.coerceIn(0, 255).toByte()
        }
    }
    return GrayImage(width, height, pixels)
}

private fun Graphics2D.line(ax: Double, ay: Double, bx: Double, by: Double, width: Float) {
    stroke = BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)
    draw(Line2D.Double(ax, ay, bx, by))
}

private fun Graphics2D.fill(ax: Double, ay: Double, bx: Double, by: Double) {
    fill(java.awt.geom.Rectangle2D.Double(ax, ay, bx - ax, by - ay))
}


package com.thedirepack.constructioncalc.core.plans

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Collects the paths a PDF page draws and turns them into a [LineMap]. A PDF library walks the
 * page's content (PDFBox's graphics stream engine, in the app and in tests) and feeds this the
 * path operations in PDF user space with the current transform already applied. Text never
 * arrives here, so it never snaps.
 *
 * Strokes become lines; long, thin filled shapes (CAD programs often draw lines as thin filled
 * rectangles) become their center line with their thickness as the width.
 *
 * [box] is the page's crop box (lower left x, lower left y, upper right x, upper right y) and
 * [rotate] its rotation in degrees; output is in page points as displayed, y down.
 */
class VectorPathCollector(box: DoubleArray, rotate: Int) {
    private val left = min(box[0], box[2])
    private val top = max(box[1], box[3])
    private val boxWidth = kotlin.math.abs(box[2] - box[0])
    private val boxHeight = kotlin.math.abs(box[3] - box[1])
    private val turn = ((rotate % 360) + 360) % 360
    val width = if (turn == 90 || turn == 270) boxHeight else boxWidth
    val height = if (turn == 90 || turn == 270) boxWidth else boxHeight

    private val lines = mutableListOf<PlanLine>()
    private val subpaths = mutableListOf<MutableList<DoubleArray>>()
    private var current: DoubleArray? = null
    private var start: DoubleArray? = null

    /** Images drawn on the page, in page points, with their own pixel size. */
    val images = mutableListOf<PageImage>()

    /** Stop collecting past this many lines (a hostile or enormous page). */
    var limit = 2_000_000

    fun moveTo(x: Double, y: Double) {
        val p = map(x, y)
        subpaths += mutableListOf(p)
        current = p
        start = p
    }

    fun lineTo(x: Double, y: Double) {
        val p = map(x, y)
        if (subpaths.isEmpty()) subpaths += mutableListOf(current ?: p)
        subpaths.last() += p
        current = p
    }

    fun curveTo(x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double) {
        val from = current ?: map(x1, y1)
        val a = map(x1, y1)
        val b = map(x2, y2)
        val c = map(x3, y3)
        if (subpaths.isEmpty()) subpaths += mutableListOf(from)
        for (i in 1..8) {
            val t = i / 8.0
            val u = 1 - t
            subpaths.last() += doubleArrayOf(
                u * u * u * from[0] + 3 * u * u * t * a[0] + 3 * u * t * t * b[0] + t * t * t * c[0],
                u * u * u * from[1] + 3 * u * u * t * a[1] + 3 * u * t * t * b[1] + t * t * t * c[1],
            )
        }
        current = c
    }

    fun closePath() {
        val s = start ?: return
        if (subpaths.isNotEmpty()) subpaths.last() += s
        current = s
    }

    /** A rectangle given by its four corners (already transformed). */
    fun rectangle(x0: Double, y0: Double, x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double) {
        val first = map(x0, y0)
        subpaths += mutableListOf(first, map(x1, y1), map(x2, y2), map(x3, y3), first)
        current = first
        start = first
    }

    /** Strokes the current path, [width] in points after the transform (0 for hairlines). */
    fun stroke(width: Double) {
        for (path in subpaths) {
            for (i in 1 until path.size) {
                val a = path[i - 1]
                val b = path[i]
                if (a[0] == b[0] && a[1] == b[1]) continue
                if (lines.size < limit) lines += PlanLine(a[0], a[1], b[0], b[1], width)
            }
        }
        endPath()
    }

    fun fill() {
        for (path in subpaths) thinShape(path)?.let { if (lines.size < limit) lines += it }
        endPath()
    }

    fun fillAndStroke(width: Double) {
        for (path in subpaths) thinShape(path)?.let { if (lines.size < limit) lines += it }
        stroke(width)
    }

    fun endPath() {
        subpaths.clear()
    }

    /** An image's unit square under the current transform (a, b, c, d, e, f), and its pixel size. */
    fun image(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double, pixelWidth: Int, pixelHeight: Int) {
        val corners = listOf(0.0 to 0.0, 1.0 to 0.0, 0.0 to 1.0, 1.0 to 1.0).map { (u, v) -> map(a * u + c * v + e, b * u + d * v + f) }
        images += PageImage(
            corners.minOf { it[0] }, corners.minOf { it[1] },
            corners.maxOf { it[0] }, corners.maxOf { it[1] }, pixelWidth, pixelHeight,
        )
    }

    fun lineMap(minimumLength: Double = LineMap.DEFAULT_MINIMUM_LENGTH): LineMap =
        LineMap(width, height, mergeLines(lines, minimumLength), LineSource.VECTOR, minimumLength)

    /** How many line pieces were drawn before joining: tells a drawing from a picture of one. */
    val pieces: Int get() = lines.size

    private fun map(x: Double, y: Double): DoubleArray {
        val ux = x - left
        val uy = top - y
        return when (turn) {
            90 -> doubleArrayOf(boxHeight - uy, ux)
            180 -> doubleArrayOf(boxWidth - ux, boxHeight - uy)
            270 -> doubleArrayOf(uy, boxWidth - ux)
            else -> doubleArrayOf(ux, uy)
        }
    }

    /** A long, thin filled polygon as its center line, or null. */
    private fun thinShape(path: List<DoubleArray>): PlanLine? {
        val closed = path.size >= 2 && path.first()[0] == path.last()[0] && path.first()[1] == path.last()[1]
        val pts = if (closed) path.dropLast(1) else path
        if (pts.size < 4 || pts.size > 12) return null
        val n = pts.size
        val mx = pts.sumOf { it[0] } / n
        val my = pts.sumOf { it[1] } / n
        var sxx = 0.0
        var sxy = 0.0
        var syy = 0.0
        for (p in pts) {
            val dx = p[0] - mx
            val dy = p[1] - my
            sxx += dx * dx
            sxy += dx * dy
            syy += dy * dy
        }
        val theta = .5 * atan2(2 * sxy, sxx - syy)
        val ux = cos(theta)
        val uy = sin(theta)
        var alongMin = Double.MAX_VALUE
        var alongMax = -Double.MAX_VALUE
        var acrossMin = Double.MAX_VALUE
        var acrossMax = -Double.MAX_VALUE
        for (p in pts) {
            val dx = p[0] - mx
            val dy = p[1] - my
            val along = dx * ux + dy * uy
            val across = -dx * uy + dy * ux
            alongMin = min(alongMin, along)
            alongMax = max(alongMax, along)
            acrossMin = min(acrossMin, across)
            acrossMax = max(acrossMax, across)
        }
        val length = alongMax - alongMin
        val thickness = acrossMax - acrossMin
        if (length <= 0 || thickness > MAX_FILLED_LINE || length < thickness * 4) return null
        val middle = (acrossMin + acrossMax) / 2
        val cx = mx - middle * uy
        val cy = my + middle * ux
        return PlanLine(cx + alongMin * ux, cy + alongMin * uy, cx + alongMax * ux, cy + alongMax * uy, thickness)
    }

    private companion object {
        /** Thickest filled shape still read as a line, points (about 1/12 in). */
        const val MAX_FILLED_LINE = 6.0
    }
}

/** An image drawn on a page: where it lands (page points) and its own pixel size. */
data class PageImage(val left: Double, val top: Double, val right: Double, val bottom: Double, val pixelWidth: Int, val pixelHeight: Int) {
    /** Image pixels per page point: the resolution worth reading this image at. */
    val pixelsPerPoint: Double
        get() = max(pixelWidth / max(right - left, 1e-6), pixelHeight / max(bottom - top, 1e-6))

    val area: Double get() = (right - left) * (bottom - top)
}

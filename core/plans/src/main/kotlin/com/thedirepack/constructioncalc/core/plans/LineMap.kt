package com.thedirepack.constructioncalc.core.plans

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A drawn line on a page, in page points (origin at the top left of the page as shown, y down):
 * its center line from (x1, y1) to (x2, y2) and its stroke [width] (0 for a hairline).
 */
@Serializable
data class PlanLine(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val width: Double) {
    val length: Double get() = hypot(x2 - x1, y2 - y1)

    /** Direction, 0 to π. */
    val angle: Double get() = atan2(y2 - y1, x2 - x1).let { if (it < 0) it + PI else if (it >= PI) it - PI else it }

    /** Distance from a point to the drawn ink (0 inside the stroke). */
    fun inkDistance(x: Double, y: Double): Double = max(0.0, centerDistance(x, y) - width / 2)

    fun centerDistance(x: Double, y: Double): Double {
        val (fx, fy) = foot(x, y, clamp = true)
        return hypot(x - fx, y - fy)
    }

    /** The nearest point on the center line, optionally kept within the line's ends. */
    fun foot(x: Double, y: Double, clamp: Boolean): Pair<Double, Double> {
        val dx = x2 - x1
        val dy = y2 - y1
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0.0) return x1 to y1
        var t = ((x - x1) * dx + (y - y1) * dy) / lengthSquared
        if (clamp) t = t.coerceIn(0.0, 1.0)
        return (x1 + t * dx) to (y1 + t * dy)
    }
}

/** Where a snap lands on a line: its center, or the edge nearest the tap. */
enum class SnapTarget { CENTER, EDGE }

/** How the page's lines were found. */
@Serializable
enum class LineSource { VECTOR, RASTER }

/** A snapped point in page points, and what it snapped to. */
data class PlanSnap(val x: Double, val y: Double, val kind: SnapKind, val line: PlanLine)

/**
 * The lines on one page, found once from the source (the PDF's own paths for vector sheets,
 * contrast for scans) and kept with the drawing. Snapping reads only this, so it doesn't depend
 * on zoom or on what is on screen. Text and small symbols are not in it: only lines at least
 * [minimumLength] points long.
 */
@Serializable
class LineMap(
    val width: Double,
    val height: Double,
    val lines: List<PlanLine>,
    val source: LineSource,
    val minimumLength: Double,
    val version: Int = VERSION,
) {
    @Transient
    private val index = Grid(lines)

    /** Lines whose ink is within [radius] points of ([x], [y]), nearest first. */
    fun near(x: Double, y: Double, radius: Double): List<PlanLine> =
        index.around(x, y, radius).filter { it.inkDistance(x, y) <= radius }.sortedBy { it.inkDistance(x, y) }

    /**
     * Snaps a tap at ([x], [y]) to the nearest line within [radius] points: to its center line or
     * to the edge facing the tap. Within [cornerRadius] of where two lines meet (at 35° or more),
     * it snaps to that corner instead: of the center lines, or of the edges facing the tap.
     */
    fun snap(x: Double, y: Double, radius: Double, target: SnapTarget, cornerRadius: Double = radius * .6): PlanSnap? {
        val candidates = near(x, y, radius)
        val line = candidates.firstOrNull() ?: return null
        val first = guide(line, x, y, target)
        var bestCorner: PlanSnap? = null
        var bestDistance = cornerRadius
        for (other in candidates.drop(1).take(12)) {
            if (angleBetween(line.angle, other.angle) < MIN_CORNER_ANGLE) continue
            val second = guide(other, x, y, target)
            val corner = first.intersect(second) ?: continue
            // Both lines must actually reach the corner, not just their extensions.
            if (line.centerDistance(corner.first, corner.second) > other.width / 2 + line.width / 2 + REACH_TOLERANCE) continue
            if (other.centerDistance(corner.first, corner.second) > line.width / 2 + other.width / 2 + REACH_TOLERANCE) continue
            val distance = hypot(corner.first - x, corner.second - y)
            if (distance <= bestDistance) {
                bestDistance = distance
                bestCorner = PlanSnap(corner.first, corner.second, SnapKind.CORNER, line)
            }
        }
        if (bestCorner != null) return bestCorner
        val (fx, fy) = first.foot(x, y)
        return PlanSnap(fx, fy, SnapKind.LINE, line)
    }

    /** The line a snap lands on: the center line, or the edge on the tap's side. */
    private fun guide(line: PlanLine, x: Double, y: Double, target: SnapTarget): Guide {
        val length = line.length
        val ux = if (length > 0) (line.x2 - line.x1) / length else 1.0
        val uy = if (length > 0) (line.y2 - line.y1) / length else 0.0
        if (target == SnapTarget.CENTER || line.width <= 0) return Guide(line.x1, line.y1, ux, uy, length)
        val nx = -uy
        val ny = ux
        val side = if ((x - line.x1) * nx + (y - line.y1) * ny >= 0) 1.0 else -1.0
        val offset = side * line.width / 2
        return Guide(line.x1 + offset * nx, line.y1 + offset * ny, ux, uy, length)
    }

    private class Guide(val px: Double, val py: Double, val ux: Double, val uy: Double, val length: Double) {
        fun foot(x: Double, y: Double): Pair<Double, Double> {
            val t = ((x - px) * ux + (y - py) * uy).coerceIn(0.0, length)
            return (px + t * ux) to (py + t * uy)
        }

        fun intersect(other: Guide): Pair<Double, Double>? {
            val cross = ux * other.uy - uy * other.ux
            if (abs(cross) < 1e-9) return null
            val dx = other.px - px
            val dy = other.py - py
            val a = (dx * other.uy - dy * other.ux) / cross
            return (px + a * ux) to (py + a * uy)
        }
    }

    /** A uniform grid of line references, for finding the lines near a point quickly. */
    private class Grid(lines: List<PlanLine>) {
        private val cells = HashMap<Long, MutableList<PlanLine>>()

        init {
            for (line in lines) {
                val pad = line.width / 2
                val x0 = cell(min(line.x1, line.x2) - pad)
                val x1 = cell(max(line.x1, line.x2) + pad)
                val y0 = cell(min(line.y1, line.y2) - pad)
                val y1 = cell(max(line.y1, line.y2) + pad)
                if ((x1 - x0 + 1L) * (y1 - y0 + 1L) > 4096) {
                    // A long diagonal: only the cells it passes near.
                    val steps = (line.length / CELL * 2).toInt() + 1
                    val seen = HashSet<Long>()
                    for (k in 0..steps) {
                        val t = k.toDouble() / steps
                        val cx = cell(line.x1 + t * (line.x2 - line.x1))
                        val cy = cell(line.y1 + t * (line.y2 - line.y1))
                        for (i in cx - 1..cx + 1) for (j in cy - 1..cy + 1) if (seen.add(key(i, j))) add(i, j, line)
                    }
                } else {
                    for (i in x0..x1) for (j in y0..y1) add(i, j, line)
                }
            }
        }

        private fun add(i: Int, j: Int, line: PlanLine) {
            cells.getOrPut(key(i, j)) { mutableListOf() } += line
        }

        fun around(x: Double, y: Double, radius: Double): Set<PlanLine> {
            val out = LinkedHashSet<PlanLine>()
            for (i in cell(x - radius)..cell(x + radius)) {
                for (j in cell(y - radius)..cell(y + radius)) cells[key(i, j)]?.let { out += it }
            }
            return out
        }

        private fun cell(v: Double) = floor(v / CELL).toInt()
        private fun key(i: Int, j: Int) = (i.toLong() shl 32) or (j.toLong() and 0xffffffffL)
    }

    companion object {
        /** Bumped when line finding changes, so cached maps are rebuilt. */
        const val VERSION = 1

        /** Shortest line kept by default, in points: walls, not lettering. */
        const val DEFAULT_MINIMUM_LENGTH = 8.0

        private const val CELL = 24.0
        private const val REACH_TOLERANCE = 1.0
        private const val MIN_CORNER_ANGLE = 35.0 * PI / 180
    }
}

/**
 * Joins pieces of the same straight line (CAD programs often split a wall at every crossing) and
 * drops duplicates, then keeps lines at least [minimumLength] points long. Pieces join when they
 * point the same way within [angleTolerance] radians, lie on the same line within
 * [offsetTolerance] points, have similar widths and touch or overlap (gaps up to [gap]).
 */
fun mergeLines(
    lines: List<PlanLine>,
    minimumLength: Double,
    angleTolerance: Double = .3 * PI / 180,
    offsetTolerance: Double = .05,
    gap: Double = .05,
): List<PlanLine> {
    class Piece(val line: PlanLine, val angle: Double, val length: Double)
    val pieces = lines.filter { it.length > 0 }
        .map { Piece(it, it.angle, it.length) }
        .sortedByDescending { it.length }
    // Bucket by angle (bin width >= angleTolerance) so each seed only checks neighboring angle bins.
    val binWidth = max(angleTolerance, 1e-4)
    val binCount = max(1, kotlin.math.ceil(PI / binWidth).toInt())
    val bins = Array(binCount) { mutableListOf<Int>() }
    for (i in pieces.indices) {
        val bin = floor(pieces[i].angle / binWidth).toInt().coerceIn(0, binCount - 1)
        bins[bin] += i
    }
    val out = mutableListOf<PlanLine>()
    val used = BooleanArray(pieces.size)
    data class Span(val from: Double, val to: Double, val width: Double, val offset: Double, val length: Double)
    for (i in pieces.indices) {
        if (used[i]) continue
        val seed = pieces[i]
        val ux = kotlin.math.cos(seed.angle)
        val uy = kotlin.math.sin(seed.angle)
        val seedOffset = -seed.line.x1 * uy + seed.line.y1 * ux
        val offTol = max(offsetTolerance, seed.line.width * .25)
        val widthTol = max(.05, seed.line.width * .5)
        val seedBin = floor(seed.angle / binWidth).toInt().coerceIn(0, binCount - 1)
        val spans = mutableListOf<Span>()
        val candidates = mutableListOf<Int>()
        for (delta in -1..1) {
            val b = (seedBin + delta + binCount) % binCount
            for (idx in bins[b]) {
                if (used[idx]) continue
                val p = pieces[idx]
                if (angleBetween(seed.angle, p.angle) > angleTolerance) continue
                if (abs(p.line.width - seed.line.width) > widthTol) continue
                val d1 = -p.line.x1 * uy + p.line.y1 * ux - seedOffset
                val d2 = -p.line.x2 * uy + p.line.y2 * ux - seedOffset
                if (abs(d1) <= offTol && abs(d2) <= offTol) {
                    val a = p.line.x1 * ux + p.line.y1 * uy
                    val c = p.line.x2 * ux + p.line.y2 * uy
                    spans += Span(min(a, c), max(a, c), p.line.width, (d1 + d2) / 2, p.length)
                    candidates += idx
                }
            }
        }
        // Only consume candidates that actually connect to a contiguous merged interval with the seed,
        // or group all collinear pieces into their own non-overlapping intervals.
        for (idx in candidates) used[idx] = true
        spans.sortBy { it.from }
        var from = 0.0
        var to = 0.0
        var weightSum = 0.0
        var offsetSum = 0.0
        var widthSum = 0.0
        var hasCurrent = false
        fun flush() {
            if (!hasCurrent) return
            if (to - from >= minimumLength) {
                val avgOffset = seedOffset + offsetSum / weightSum
                val avgWidth = widthSum / weightSum
                val ox = -avgOffset * uy
                val oy = avgOffset * ux
                out += PlanLine(ox + from * ux, oy + from * uy, ox + to * ux, oy + to * uy, avgWidth)
            }
            hasCurrent = false
        }
        for (s in spans) {
            if (hasCurrent && s.from <= to + gap) {
                to = max(to, s.to)
                weightSum += s.length
                offsetSum += s.offset * s.length
                widthSum += s.width * s.length
            } else {
                flush()
                from = s.from
                to = s.to
                weightSum = s.length
                offsetSum = s.offset * s.length
                widthSum = s.width * s.length
                hasCurrent = true
            }
        }
        flush()
    }
    return out
}

internal fun angleBetween(a: Double, b: Double): Double {
    var d = abs(a - b) % PI
    if (d > PI / 2) d = PI - d
    return d
}

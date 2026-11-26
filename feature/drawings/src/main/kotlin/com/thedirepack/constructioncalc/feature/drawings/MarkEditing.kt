package com.thedirepack.constructioncalc.feature.drawings

import com.thedirepack.constructioncalc.core.model.PlanMark
import com.thedirepack.constructioncalc.core.model.PlanMarkKind
import com.thedirepack.constructioncalc.core.model.PlanPoint
import com.thedirepack.constructioncalc.core.model.PlanSymbolPlacement
import com.thedirepack.constructioncalc.core.model.newId
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.hypot
import kotlin.math.min

internal fun PlanPoint.offset(dx: Double, dy: Double) = PlanPoint((x + dx).coerceIn(-1e7, 1e7), (y + dy).coerceIn(-1e7, 1e7))

internal fun markAt(marks: List<PlanMark>, point: PlanPoint, radius: Double): PlanMark? =
    marks.asReversed().firstOrNull { mark ->
        val first = mark.points.first()
        val last = mark.points.last()
        when (mark.kind) {
            PlanMarkKind.RECTANGLE, PlanMarkKind.CLOUD -> contains(point, first, last, radius)
            PlanMarkKind.SYMBOL -> mark.symbol?.let { symbol ->
                val (lx, ly) = symbolLocal(first, symbol.rotationDegrees, point)
                abs(lx) <= symbol.size / 2 + radius && abs(ly) <= symbol.size / 2 + radius
            } ?: false
            PlanMarkKind.TEXT, PlanMarkKind.CALLOUT -> {
                val box = if (mark.kind == PlanMarkKind.CALLOUT) last else first
                val lines = mark.text.lines()
                val inset = if (mark.kind == PlanMarkKind.CALLOUT) mark.width * 0.35 else 0.0
                val right = box.x + mark.width * 0.65 * (lines.maxOf { it.length }.coerceAtLeast(1)) + inset * 2
                contains(point, box, box.offset(right - box.x, mark.width * 1.18 * lines.size + inset * 2), radius) ||
                    (mark.kind == PlanMarkKind.CALLOUT && EditorActions.segmentDistance(point, first, last) <= radius)
            }
            PlanMarkKind.CLOUD_CALLOUT -> {
                val p1 = mark.points.getOrElse(1) { first }
                contains(point, first, p1, radius)
            }
            PlanMarkKind.PHOTO, PlanMarkKind.DETAIL_LINK -> {
                val half = maxOf(14.0, min(mark.width, 48.0) * 0.65)
                val extraRight = if (mark.text.isNotBlank()) {
                    maxOf(10.0, mark.width * 0.45) * mark.text.length * 0.65 + 8.0
                } else {
                    0.0
                }
                contains(point, first.offset(-half, -half), first.offset(half + extraRight, half), radius)
            }
            PlanMarkKind.DIMENSION -> {
                EditorActions.segmentDistance(point, first, last) <= radius + maxOf(4.0, mark.width)
            }
            PlanMarkKind.INK, PlanMarkKind.HIGHLIGHT, PlanMarkKind.LINE, PlanMarkKind.ARROW -> {
                val tolerance = radius + mark.width / 2
                if (mark.points.size == 1) EditorActions.segmentDistance(point, first, first) <= tolerance
                else (0 until mark.points.lastIndex).any { i ->
                    EditorActions.segmentDistance(point, mark.points[i], mark.points[i + 1]) <= tolerance
                }
            }
        }
    }

private fun contains(point: PlanPoint, a: PlanPoint, b: PlanPoint, margin: Double) =
    point.x >= minOf(a.x, b.x) - margin && point.x <= maxOf(a.x, b.x) + margin &&
        point.y >= minOf(a.y, b.y) - margin && point.y <= maxOf(a.y, b.y) + margin

/** [point] in a symbol's own frame: origin at its center, axes turned with it. */
private fun symbolLocal(center: PlanPoint, rotationDegrees: Double, point: PlanPoint): Pair<Double, Double> {
    val a = Math.toRadians(rotationDegrees)
    val dx = point.x - center.x
    val dy = point.y - center.y
    return (dx * cos(a) + dy * sin(a)) to (-dx * sin(a) + dy * cos(a))
}

private fun symbolPoint(center: PlanPoint, rotationDegrees: Double, lx: Double, ly: Double): PlanPoint {
    val a = Math.toRadians(rotationDegrees)
    return center.offset(lx * cos(a) - ly * sin(a), lx * sin(a) + ly * cos(a))
}

/** Gap between a symbol's top edge and its rotate handle, as a share of its size. */
private const val ROTATE_HANDLE_GAP = 0.45

/**
 * Edit handles. A symbol has two: 0 resizes from its corner (it stays square and centered),
 * 1 turns it from above its top edge.
 */
internal fun PlanMark.editHandles(): List<PlanPoint> {
    symbol?.let { s ->
        val center = points.first()
        val half = s.size / 2
        return listOf(
            symbolPoint(center, s.rotationDegrees, half, half),
            symbolPoint(center, s.rotationDegrees, 0.0, -half - s.size * ROTATE_HANDLE_GAP),
        )
    }
    if (points.size < 2) return emptyList()
    val first = points.first()
    val p1 = points[1]
    val end = points.last()
    return when (kind) {
        PlanMarkKind.RECTANGLE, PlanMarkKind.CLOUD, PlanMarkKind.CLOUD_CALLOUT ->
            listOf(first, p1, PlanPoint(first.x, p1.y), PlanPoint(p1.x, first.y))
        PlanMarkKind.LINE, PlanMarkKind.ARROW, PlanMarkKind.CALLOUT, PlanMarkKind.DIMENSION -> listOf(first, end)
        else -> emptyList()
    }
}

internal fun PlanMark.resize(handle: Int, point: PlanPoint): PlanMark {
    symbol?.let { s ->
        val center = points.first()
        return if (handle == 0) {
            val (lx, ly) = symbolLocal(center, s.rotationDegrees, point)
            val size = (2 * max(abs(lx), abs(ly))).coerceIn(PlanSymbolPlacement.MIN_SIZE, PlanSymbolPlacement.MAX_SIZE)
            copy(symbol = s.copy(size = size))
        } else {
            copy(symbol = s.copy(rotationDegrees = rotationToward(center, point)))
        }
    }
    val first = points.first()
    val p1 = points.getOrElse(1) { first }
    val end = points.last()
    if (kind == PlanMarkKind.CLOUD_CALLOUT) {
        return copy(
            points = when (handle) {
                0 -> listOf(point, p1)
                1 -> listOf(first, point)
                2 -> listOf(PlanPoint(point.x, first.y), PlanPoint(p1.x, point.y))
                else -> listOf(PlanPoint(first.x, point.y), PlanPoint(point.x, p1.y))
            },
        )
    }
    return copy(
        points = when (handle) {
            0 -> listOf(point, end)
            1 -> listOf(first, point)
            2 -> listOf(PlanPoint(point.x, first.y), PlanPoint(end.x, point.y))
            else -> listOf(PlanPoint(first.x, point.y), PlanPoint(point.x, end.y))
        },
    )
}

/**
 * The rotation that points a symbol's top at [point], in whole 5° steps, snapping to the nearest
 * right angle within 4° so plans' usual orientations are easy to hit.
 */
internal fun rotationToward(center: PlanPoint, point: PlanPoint): Double {
    if (point == center) return 0.0
    val degrees = Math.toDegrees(atan2(point.y - center.y, point.x - center.x)) + 90.0
    val normalized = PlanSymbolPlacement.normalizeDegrees(degrees)
    val right = (normalized / 90.0).roundToInt() * 90.0
    val snapped = if (abs(normalized - right) <= 4.0) right else (normalized / 5.0).roundToInt() * 5.0
    return PlanSymbolPlacement.normalizeDegrees(snapped)
}

internal fun PlanMark.move(dx: Double, dy: Double): PlanMark =
    copy(
        points = if (kind == PlanMarkKind.CALLOUT && points.size >= 2) {
            listOf(points.first(), points.last().offset(dx, dy))
        } else {
            points.map { it.offset(dx, dy) }
        },
    )

/**
 * Splits or trims an [PlanMarkKind.INK] or [PlanMarkKind.HIGHLIGHT] mark wherever [eraserPoints] passes
 * within [radius] + half the stroke width. Returns `[mark]` unchanged when nothing is touched,
 * `emptyList()` when the entire stroke is erased, or replacement fragments preserving style and pressures.
 */
internal fun eraseStrokePartially(mark: PlanMark, eraserPoints: List<PlanPoint>, radius: Double): List<PlanMark> {
    if (mark.kind != PlanMarkKind.INK && mark.kind != PlanMarkKind.HIGHLIGHT) return listOf(mark)
    if (mark.points.isEmpty() || eraserPoints.isEmpty()) return listOf(mark)
    val hitRadius = radius + mark.width * 0.5

    fun hitsEraser(point: PlanPoint): Boolean {
        if (eraserPoints.size == 1) {
            val e = eraserPoints.first()
            return hypot(point.x - e.x, point.y - e.y) <= hitRadius
        }
        for (i in 0 until eraserPoints.lastIndex) {
            if (EditorActions.segmentDistance(point, eraserPoints[i], eraserPoints[i + 1]) <= hitRadius) return true
        }
        return false
    }

    if (mark.points.size == 1) {
        return if (hitsEraser(mark.points.first())) emptyList() else listOf(mark)
    }

    val step = maxOf(1.5, radius * 0.45)
    val hasPressures = mark.pressures.size == mark.points.size
    val sampledPoints = ArrayList<PlanPoint>(mark.points.size)
    val sampledPressures = if (hasPressures) ArrayList<Float>(mark.points.size) else null

    for (i in 0 until mark.points.lastIndex) {
        val a = mark.points[i]
        val b = mark.points[i + 1]
        val pa = if (hasPressures) mark.pressures[i] else 1f
        val pb = if (hasPressures) mark.pressures[i + 1] else 1f
        val dist = hypot(b.x - a.x, b.y - a.y)
        val steps = ceil(dist / step).toInt().coerceIn(1, 200)
        val limit = if (sampledPoints.size + steps > 5_000) 1 else steps
        for (s in 0 until limit) {
            val t = s.toDouble() / limit
            sampledPoints += PlanPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            if (sampledPressures != null) sampledPressures += (pa + (pb - pa) * t.toFloat())
        }
    }
    sampledPoints += mark.points.last()
    if (sampledPressures != null) sampledPressures += mark.pressures.last()

    val kept = BooleanArray(sampledPoints.size)
    var anyErased = false
    for (i in sampledPoints.indices) {
        val erased = hitsEraser(sampledPoints[i])
        kept[i] = !erased
        if (erased) anyErased = true
    }
    if (!anyErased) return listOf(mark)

    val out = mutableListOf<PlanMark>()
    var start = -1
    for (i in sampledPoints.indices) {
        if (kept[i]) {
            if (start < 0) start = i
        } else if (start >= 0) {
            if (i - start >= 2) {
                out += mark.copy(
                    id = newId(),
                    points = sampledPoints.subList(start, i).toList(),
                    pressures = sampledPressures?.subList(start, i)?.toList().orEmpty(),
                )
            }
            start = -1
        }
    }
    if (start >= 0 && sampledPoints.size - start >= 2) {
        out += mark.copy(
            id = newId(),
            points = sampledPoints.subList(start, sampledPoints.size).toList(),
            pressures = sampledPressures?.subList(start, sampledPressures.size)?.toList().orEmpty(),
        )
    }
    return out
}

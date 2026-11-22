package com.thedirepack.constructioncalc.feature.drawings

import com.thedirepack.constructioncalc.core.model.PlanMark
import com.thedirepack.constructioncalc.core.model.PlanMarkKind
import com.thedirepack.constructioncalc.core.model.PlanPoint

internal fun PlanPoint.offset(dx: Double, dy: Double) = PlanPoint((x + dx).coerceIn(-1e7, 1e7), (y + dy).coerceIn(-1e7, 1e7))

internal fun markAt(marks: List<PlanMark>, point: PlanPoint, radius: Double): PlanMark? =
    marks.asReversed().firstOrNull { mark ->
        val first = mark.points.first()
        val last = mark.points.last()
        when (mark.kind) {
            PlanMarkKind.RECTANGLE -> contains(point, first, last, radius)
            PlanMarkKind.SYMBOL -> contains(point, first, first.offset(mark.width, mark.width), radius)
            PlanMarkKind.TEXT, PlanMarkKind.CALLOUT -> {
                val box = if (mark.kind == PlanMarkKind.CALLOUT) last else first
                val lines = mark.text.lines()
                val right = box.x + mark.width * (lines.maxOf { it.length }.coerceAtLeast(1))
                contains(point, box, box.offset(right - box.x, mark.width * (lines.size + if (mark.kind == PlanMarkKind.CALLOUT) 1.25 else 0.0)), radius) ||
                    (mark.kind == PlanMarkKind.CALLOUT && EditorActions.segmentDistance(point, first, last) <= radius)
            }
            else -> {
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

internal fun PlanMark.editHandles(): List<PlanPoint> {
    if (points.size < 2) return emptyList()
    val first = points.first()
    val end = points.last()
    return when (kind) {
        PlanMarkKind.RECTANGLE -> listOf(first, end, PlanPoint(first.x, end.y), PlanPoint(end.x, first.y))
        PlanMarkKind.LINE, PlanMarkKind.ARROW, PlanMarkKind.CALLOUT -> listOf(first, end)
        else -> emptyList()
    }
}

internal fun PlanMark.resize(handle: Int, point: PlanPoint): PlanMark {
    val first = points.first()
    val end = points.last()
    return copy(points = when (handle) {
        0 -> listOf(point, end)
        1 -> listOf(first, point)
        2 -> listOf(PlanPoint(point.x, first.y), PlanPoint(end.x, point.y))
        else -> listOf(PlanPoint(first.x, point.y), PlanPoint(point.x, end.y))
    })
}

internal fun PlanMark.move(dx: Double, dy: Double): PlanMark =
    copy(points = if (kind == PlanMarkKind.CALLOUT && points.size >= 2) {
        listOf(points.first(), points.last().offset(dx, dy))
    } else points.map { it.offset(dx, dy) })

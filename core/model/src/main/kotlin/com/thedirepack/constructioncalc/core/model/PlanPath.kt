package com.thedirepack.constructioncalc.core.model

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import kotlinx.serialization.Serializable

/** An editable path in rendered page coordinates. A polygon closes implicitly. */
@Serializable
data class PlanPath(
    val id: String = newId(),
    val page: Int,
    val points: List<PlanPoint>,
    val closed: Boolean = false,
) {
    val edgeCount: Int get() = if (closed) points.size else points.lastIndex

    init {
        require(page >= 0 && validPath(points, closed))
    }

    fun edgeEnd(index: Int): PlanPoint = points[(index + 1) % points.size]

    fun insertVertex(edge: Int, point: PlanPoint): PlanPath? {
        if (edge !in 0 until edgeCount) return null
        return withVertices(points.toMutableList().apply { add(edge + 1, point) })
    }

    fun moveVertex(index: Int, point: PlanPoint): PlanPath? {
        if (index !in points.indices) return null
        return withVertices(points.toMutableList().apply { set(index, point) })
    }

    fun removeVertex(index: Int): PlanPath? {
        if (index !in points.indices) return null
        return withVertices(points.toMutableList().apply { removeAt(index) })
    }

    private fun withVertices(vertices: List<PlanPoint>): PlanPath? =
        if (validPath(vertices, closed)) copy(points = vertices) else null

    companion object {
        const val MAX_VERTICES = 512

        /** Converts a trace into a saved path, accepting an explicit closing tap for polygons. */
        fun fromTrace(page: Int, points: List<PlanPoint>, closed: Boolean = false): PlanPath? {
            val vertices = mutableListOf<PlanPoint>()
            for (point in points) {
                if (vertices.lastOrNull() != point) vertices.add(point)
                if (vertices.size > MAX_VERTICES + 1) return null
            }
            if (closed && vertices.size > 1 && vertices.first() == vertices.last()) vertices.removeAt(vertices.lastIndex)
            return if (page >= 0 && validPath(vertices, closed)) PlanPath(page = page, points = vertices.toList(), closed = closed)
            else null
        }
    }
}

sealed interface PlanPathMeasurement {
    data class Measured(
        /** Total edge length, including the closing edge for a polygon. */
        val length: Quantity,
        val area: Quantity?,
        val zoneId: String?,
    ) : PlanPathMeasurement

    data object CrossesZones : PlanPathMeasurement
    data object Uncalibrated : PlanPathMeasurement
}

/**
 * Re-measures against the current sheet scale or one override. A boundary contact from the
 * sheet side is ambiguous; a path contained by one zone can use its boundary.
 */
fun PlanDrawing.measurePath(path: PlanPath): PlanPathMeasurement {
    val pageZones = zones.filter { it.page == path.page }
    val containing = pageZones.filter { candidate -> path.points.all(candidate::contains) }
    val zone = containing.singleOrNull()
    if (pageZones.any { other -> (zone == null || other != zone && other.overlaps(zone)) && path.intersects(other) }) {
        return PlanPathMeasurement.CrossesZones
    }
    val fit = (if (zone == null) calibration.fit(path.page) else zone.fit())
        ?: return PlanPathMeasurement.Uncalibrated
    val inches = (0 until path.edgeCount).sumOf { fit.distanceInches(path.points[it], path.edgeEnd(it)) }
    val area = if (path.closed) fit.area(path.points) ?: return PlanPathMeasurement.Uncalibrated else null
    if (!inches.isFinite() || inches <= 0.0) return PlanPathMeasurement.Uncalibrated
    return PlanPathMeasurement.Measured(Quantity.inches(Rational.of(inches)), area, zone?.id)
}

private fun validPath(points: List<PlanPoint>, closed: Boolean): Boolean {
    val minimum = if (closed) 3 else 2
    if (points.size !in minimum..PlanPath.MAX_VERTICES) return false
    if ((1..points.lastIndex).any { points[it - 1] == points[it] }) return false
    if (!closed) return true
    if (points.toSet().size != points.size) return false
    val origin = points.first()
    if ((1 until points.lastIndex).sumOf { cross(origin, points[it], points[it + 1]) } == 0.0) return false
    // Adjacent edges may share an endpoint, but may not double back over each other.
    for (i in points.indices) {
        val a = points[(i + points.lastIndex) % points.size]
        val b = points[i]
        val c = points[(i + 1) % points.size]
        if (cross(a, b, c) == 0.0 && ((a.x - b.x) * (c.x - b.x) + (a.y - b.y) * (c.y - b.y)) > 0) return false
        for (j in i + 2 until points.size) {
            if (i == 0 && j == points.lastIndex) continue
            if (segmentsIntersect(b, c, points[j], points[(j + 1) % points.size])) return false
        }
    }
    return true
}

internal fun cross(a: PlanPoint, b: PlanPoint, c: PlanPoint): Double =
    (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

private fun segmentsIntersect(a: PlanPoint, b: PlanPoint, c: PlanPoint, d: PlanPoint): Boolean {
    if (maxOf(a.x, b.x) < minOf(c.x, d.x) || maxOf(c.x, d.x) < minOf(a.x, b.x) ||
        maxOf(a.y, b.y) < minOf(c.y, d.y) || maxOf(c.y, d.y) < minOf(a.y, b.y)
    ) return false
    return cross(a, b, c) * cross(a, b, d) <= 0.0 && cross(c, d, a) * cross(c, d, b) <= 0.0
}

private fun PlanPath.intersects(zone: PlanZone): Boolean {
    if (points.any(zone::contains)) return true
    val corners = listOf(zone.first, PlanPoint(zone.first.x, zone.second.y), zone.second, PlanPoint(zone.second.x, zone.first.y))
    var inside = false
    for (i in 0 until edgeCount) {
        val a = points[i]
        val b = edgeEnd(i)
        for (j in corners.indices) {
            if (segmentsIntersect(a, b, corners[j], corners[(j + 1) % corners.size])) return true
        }
        // If there is no boundary intersection, one zone corner is enough to detect enclosure.
        if (closed && (a.y > zone.first.y) != (b.y > zone.first.y) &&
            zone.first.x < a.x + (zone.first.y - a.y) * (b.x - a.x) / (b.y - a.y)
        ) inside = !inside
    }
    return inside
}

package com.thedirepack.constructioncalc.feature.drawings

import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.PlanPath
import com.thedirepack.constructioncalc.core.model.PlanPathMeasurement
import com.thedirepack.constructioncalc.core.model.PlanPoint

internal data class CanvasPath(val path: PlanPath, val label: String)

internal fun PlanPath.nearestEdge(point: PlanPoint, radius: Double): Int? =
    (0 until edgeCount).minByOrNull { EditorActions.segmentDistance(point, points[it], edgeEnd(it)) }
        ?.takeIf { EditorActions.segmentDistance(point, points[it], edgeEnd(it)) <= radius }

internal fun PlanPath.nearestVertex(point: PlanPoint, radius: Double): Int? =
    points.indices.minByOrNull { EditorActions.segmentDistance(point, points[it], points[it]) }
        ?.takeIf { EditorActions.segmentDistance(point, points[it], points[it]) <= radius }

internal fun PlanPathMeasurement.label(format: (Quantity) -> String): String = when (this) {
    PlanPathMeasurement.CrossesZones -> "Spans multiple scales"
    PlanPathMeasurement.Uncalibrated -> "Uncalibrated"
    is PlanPathMeasurement.Measured ->
        area?.let { "Perimeter ≈ ${format(length)} · Area ≈ ${format(it)}" } ?: "Length ≈ ${format(length)}"
}

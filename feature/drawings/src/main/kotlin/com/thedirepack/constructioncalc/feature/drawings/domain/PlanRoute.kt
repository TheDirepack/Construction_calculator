package com.thedirepack.constructioncalc.feature.drawings.domain

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.BendPlane
import com.thedirepack.constructioncalc.core.model.PathSegment
import com.thedirepack.constructioncalc.core.model.PlanDrawing
import com.thedirepack.constructioncalc.core.model.PlanPoint
import com.thedirepack.constructioncalc.core.model.RunPath
import com.thedirepack.constructioncalc.core.model.stored
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.roundToInt

data class RouteLeg(
    val from: PlanPoint,
    val to: PlanPoint,
    val lengthInches: Double,
    val zoneId: String?,
)

data class RouteCorner(
    val point: PlanPoint,
    val bendDegrees: Double,
)

data class RouteSummary(
    val points: List<PlanPoint>,
    val legs: List<RouteLeg>,
    val corners: List<RouteCorner>,
    val totalHorizontalInches: Double,
    val totalBendDegrees: Double,
) {
    val totalHorizontalQuantity: Quantity
        get() = Quantity.inches(Rational.of(totalHorizontalInches))
}

/**
 * Pure math for multi-segment polyline routes traced on a calibrated drawing, and conversion
 * into [PathSegment]s for a project run ([com.thedirepack.constructioncalc.core.model.withRunPath]).
 */
object PlanRoute {
    /** Ignore tiny angle deflections below this threshold (in degrees) when counting bends. */
    const val MIN_BEND_DEGREES = 1.0

    /**
     * Measures a connected polyline [points] on [page] of [drawing].
     * Returns null if there are fewer than 2 points, any leg crosses an override zone boundary,
     * or any leg is uncalibrated.
     */
    fun measure(drawing: PlanDrawing, page: Int, points: List<PlanPoint>): RouteSummary? {
        if (points.size < 2) return null
        val legs = mutableListOf<RouteLeg>()
        for (i in 0 until points.lastIndex) {
            val a = points[i]
            val b = points[i + 1]
            if (a == b) continue
            if (drawing.crossesZoneBoundary(page, a, b)) return null
            val fit = drawing.calibrationFor(page, a, b) ?: return null
            val zone = drawing.zoneContaining(page, a, b)
            val inches = fit.distance(a, b).value.toDouble()
            if (!inches.isFinite() || inches <= 0.0) return null
            legs += RouteLeg(from = a, to = b, lengthInches = inches, zoneId = zone?.id)
        }
        if (legs.isEmpty()) return null
        val corners = mutableListOf<RouteCorner>()
        for (i in 0 until legs.lastIndex) {
            val deg = turnAngleDegrees(legs[i].from, legs[i].to, legs[i + 1].to)
            if (deg >= MIN_BEND_DEGREES) {
                corners += RouteCorner(legs[i].to, deg)
            }
        }
        return RouteSummary(
            points = points,
            legs = legs,
            corners = corners,
            totalHorizontalInches = legs.sumOf { it.lengthInches },
            totalBendDegrees = corners.sumOf { it.bendDegrees },
        )
    }

    /**
     * Turning angle in degrees at vertex [mid] going from [prev] → [mid] → [next].
     * 0° means straight ahead; 90° is a right-angle turn; 180° is a U-turn.
     */
    fun turnAngleDegrees(prev: PlanPoint, mid: PlanPoint, next: PlanPoint): Double {
        val v1x = mid.x - prev.x
        val v1y = mid.y - prev.y
        val v2x = next.x - mid.x
        val v2y = next.y - mid.y
        val len1 = hypot(v1x, v1y)
        val len2 = hypot(v2x, v2y)
        if (len1 == 0.0 || len2 == 0.0) return 0.0
        val cosTheta = ((v1x * v2x + v1y * v2y) / (len1 * len2)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cosTheta))
    }

    /**
     * Converts a measured [RouteSummary] into [PathSegment]s suitable for `Project.withRunPath`.
     *
     * Optional vertical transitions at the start and/or end add a vertical straight (`±90°` slope)
     * plus a 90° vertical transition bend with the physically matching [BendPlane]:
     * - Start rising into level: [BendPlane.VERTICAL_DOWN] (hump)
     * - Start dropping into level: [BendPlane.VERTICAL_UP] (sag)
     * - Level into end rising: [BendPlane.VERTICAL_UP] (sag)
     * - Level into end dropping: [BendPlane.VERTICAL_DOWN] (hump)
     */
    fun toRunPath(
        summary: RouteSummary,
        startVerticalInches: Double = 0.0,
        startVerticalUp: Boolean = true,
        endVerticalInches: Double = 0.0,
        endVerticalUp: Boolean = false,
    ): List<PathSegment> {
        val out = mutableListOf<PathSegment>()
        val startVert = abs(startVerticalInches)
        if (startVert > 1e-6) {
            out += PathSegment.Straight(
                length = Quantity.inches(Rational.of(startVert)).stored(),
                slopeDegrees = if (startVerticalUp) 90.0 else -90.0,
            )
            out += PathSegment.Bend(
                angleDegrees = 90.0,
                plane = if (startVerticalUp) BendPlane.VERTICAL_DOWN else BendPlane.VERTICAL_UP,
            )
        }

        for (i in summary.legs.indices) {
            val leg = summary.legs[i]
            out += PathSegment.Straight(
                length = Quantity.inches(Rational.of(leg.lengthInches)).stored(),
                slopeDegrees = 0.0,
            )
            if (i < summary.legs.lastIndex) {
                val deg = turnAngleDegrees(leg.from, leg.to, summary.legs[i + 1].to)
                if (deg >= MIN_BEND_DEGREES) {
                    val rounded = (deg * 2.0).roundToInt() / 2.0
                    val angle = rounded.coerceIn(MIN_BEND_DEGREES, 180.0)
                    out += PathSegment.Bend(
                        angleDegrees = angle,
                        plane = BendPlane.HORIZONTAL,
                    )
                }
            }
        }

        val endVert = abs(endVerticalInches)
        if (endVert > 1e-6) {
            out += PathSegment.Bend(
                angleDegrees = 90.0,
                plane = if (endVerticalUp) BendPlane.VERTICAL_UP else BendPlane.VERTICAL_DOWN,
            )
            out += PathSegment.Straight(
                length = Quantity.inches(Rational.of(endVert)).stored(),
                slopeDegrees = if (endVerticalUp) 90.0 else -90.0,
            )
        }

        require(out.size <= RunPath.MAX_SEGMENTS) { "Route has too many segments for a run path." }
        return out
    }
}

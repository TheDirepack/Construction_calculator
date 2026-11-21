package com.thedirepack.constructioncalc.feature.pullplanner.domain

import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.model.BendPlane
import com.thedirepack.constructioncalc.core.model.PathSegment
import com.thedirepack.constructioncalc.core.model.RunPath
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A point of the profile, in feet: [x] along the ground, [y] up. */
data class ProfilePoint(val x: Double, val y: Double)

/** A labelled spot on the profile: a horizontal bend or a pull point. [pathIndex] is its segment. */
data class ProfileMarker(val at: ProfilePoint, val label: String, val pullPoint: Boolean, val pathIndex: Int)

/** The route drawn as a side view (elevation profile) for the planner's static diagram. */
data class PathProfile(val points: List<ProfilePoint>, val markers: List<ProfileMarker>) {
    val minX get() = points.minOf { it.x }
    val maxX get() = points.maxOf { it.x }
    val minY get() = points.minOf { it.y }
    val maxY get() = points.maxOf { it.y }

    companion object {
        /**
         * The side view of [path]: straights at their slope, vertical bends as arcs that change the
         * slope, horizontal bends and pull points as markers (a side view can't show a turn, so a
         * horizontal bend is a labelled dot). Straights shorter than 1/25 of the route are drawn at
         * that length so every piece stays visible; the drawing is a schematic, not to scale.
         */
        fun of(path: List<PathSegment>, type: ConduitType?, size: TradeSize?): PathProfile {
            val total = RunPath.lengthInches(path, type, size) / 12.0
            val minimum = (total / 25.0).coerceAtLeast(1.0)
            var x = 0.0
            var y = 0.0
            var slope = 0.0
            val points = mutableListOf(ProfilePoint(0.0, 0.0))
            val markers = mutableListOf<ProfileMarker>()
            path.forEachIndexed { i, segment ->
                when (segment) {
                    is PathSegment.Straight -> {
                        slope = segment.slopeDegrees
                        val l = (segment.lengthInches / 12.0).coerceAtLeast(minimum)
                        x += l * cos(slope * PI / 180)
                        y += l * sin(slope * PI / 180)
                        points += ProfilePoint(x, y)
                    }
                    is PathSegment.Bend -> when (segment.plane) {
                        BendPlane.HORIZONTAL -> markers += ProfileMarker(ProfilePoint(x, y), "${PullReport.num(segment.angleDegrees)}°", false, i)
                        BendPlane.VERTICAL_UP, BendPlane.VERTICAL_DOWN -> {
                            val r = ((RunPath.radiusInches(segment, type, size) ?: 12.0) / 12.0).coerceAtLeast(minimum / 2)
                            val sign = if (segment.plane == BendPlane.VERTICAL_UP) 1 else -1
                            val steps = 6
                            repeat(steps) {
                                val step = segment.angleDegrees / steps
                                val mid = slope + sign * step / 2
                                val chord = r * step * PI / 180
                                x += chord * cos(mid * PI / 180)
                                y += chord * sin(mid * PI / 180)
                                slope += sign * step
                                points += ProfilePoint(x, y)
                            }
                        }
                    }
                    is PathSegment.PullPoint -> markers += ProfileMarker(ProfilePoint(x, y), segment.label.ifBlank { "PB" }, true, i)
                }
            }
            return PathProfile(points, markers)
        }
    }
}

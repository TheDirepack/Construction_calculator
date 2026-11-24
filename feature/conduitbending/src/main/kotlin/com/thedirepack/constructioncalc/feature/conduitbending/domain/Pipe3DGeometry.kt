package com.thedirepack.constructioncalc.feature.conduitbending.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Immutable 3D vector in inches. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3): Double = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3): Vec3 = Vec3(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x,
    )
    fun length(): Double = sqrt(x * x + y * y + z * z)
    fun normalized(): Vec3 {
        val len = length()
        return if (len > 1e-12) Vec3(x / len, y / len, z / len) else this
    }

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val UNIT_X = Vec3(1.0, 0.0, 0.0)
        val UNIT_Y = Vec3(0.0, 1.0, 0.0)
        val UNIT_Z = Vec3(0.0, 0.0, 1.0)
    }
}

/**
 * Geometric metadata for one physical bend arc on the 3D centerline.
 */
data class BendArc3D(
    val bendItemIndex: Int,
    val markLabel: String,
    val positionOnStickIn: Double,
    val startPoint: Vec3,
    val centerPoint: Vec3,
    val endPoint: Vec3,
    val angleDeg: Double,
    val axialRotationDeg: Double,
    val radiusIn: Double,
)

/**
 * 3D centerline representation of a bent conduit stick from End A (`points.first()`) to the cut
 * end (`points.last()`), plus optional [remainingPoints] representing unconsumed straight stick up
 * to the stock stick length.
 */
data class Pipe3DPath(
    val points: List<Vec3>,
    val remainingPoints: List<Vec3> = emptyList(),
    val bends: List<BendArc3D> = emptyList(),
    val totalLengthIn: Double = 0.0,
    val minBounds: Vec3 = Vec3.ZERO,
    val maxBounds: Vec3 = Vec3.ZERO,
)

/**
 * Builds 3D conduit centerline paths in world coordinates (inches) using a torsion-free
 * parallel-transport frame `(T, U, W)` where:
 * - `T` starts along `+X` (End A → End B)
 * - `U` starts along `+Z` (0° axial rotation bends upward)
 * - `W = U × T` starts along `+Y` (90° axial rotation bends sideways)
 *
 * Arbitrary axial rotations `φ ∈ [0°, 360°)` rotate the bend direction `B = cos(φ)·U + sin(φ)·W`
 * around the pipe axis `T`, modeling 3D turns and doglegs without gimbal lock or artificial roll drift.
 */
object Pipe3DGeometry {

    private const val ARC_STEPS_PER_90 = 12

    fun build(plan: StickPlan): Pipe3DPath {
        if (!plan.hasResults || plan.physicalBends.isEmpty()) {
            val len = plan.stickLengthDouble
            val pts = listOf(Vec3.ZERO, Vec3(len, 0.0, 0.0))
            return Pipe3DPath(
                points = pts,
                remainingPoints = emptyList(),
                bends = emptyList(),
                totalLengthIn = len,
                minBounds = Vec3.ZERO,
                maxBounds = Vec3(len, 0.0, 0.0),
            )
        }

        val sortedBends = plan.physicalBends.sortedBy { it.arcStartIn.toDouble() }
        val points = mutableListOf(Vec3.ZERO)
        val arcs = mutableListOf<BendArc3D>()

        var pos = Vec3.ZERO
        var tangent = Vec3.UNIT_X
        var up = Vec3.UNIT_Z
        var side = Vec3.UNIT_Y // up × tangent = +Z × +X = +Y
        // If mark 1 is at 0" (no End A distance entered), start slightly before 0 so its tangent offset preserves exact geometry.
        var cursorS = min(0.0, sortedBends.first().arcStartIn.toDouble())
        var totalLen = 0.0

        for (bend in sortedBends) {
            val arcStart = max(cursorS, bend.arcStartIn.toDouble())
            val straightLen = (arcStart - cursorS).coerceAtLeast(0.0)
            if (straightLen > 1e-6) {
                pos += tangent * straightLen
                points += pos
                totalLen += straightLen
            }
            cursorS = arcStart

            val alpha = Math.toRadians(bend.angleDeg.coerceIn(0.0, 180.0))
            val phi = Math.toRadians(normalizeRotationDeg(bend.axialRotationDeg))
            val r = bend.radiusIn.coerceAtLeast(0.5)

            // Bend plane direction B and hinge axis N_perp = B × T
            val cosPhi = cos(phi)
            val sinPhi = sin(phi)
            val bendDir = (up * cosPhi + side * sinPhi).normalized()
            val nPerp = (side * cosPhi - up * sinPhi).normalized()

            val startPt = pos
            val steps = max(4, ((bend.angleDeg / 90.0) * ARC_STEPS_PER_90).toInt())
            var midPt = startPt
            for (step in 1..steps) {
                val t = alpha * step / steps
                val arcPt = startPt + tangent * (r * sin(t)) + bendDir * (r * (1.0 - cos(t)))
                points += arcPt
                if (step == steps / 2) midPt = arcPt
            }

            val endPt = startPt + tangent * (r * sin(alpha)) + bendDir * (r * (1.0 - cos(alpha)))
            pos = endPt
            val arcLen = r * alpha
            totalLen += arcLen
            cursorS = max(arcStart + arcLen, bend.layoutEndIn.toDouble())

            // Parallel-transport (T, U, W) through the bend angle alpha
            val newTangent = (tangent * cos(alpha) + bendDir * sin(alpha)).normalized()
            val newBendDir = (bendDir * cos(alpha) - tangent * sin(alpha)).normalized()
            val newUp = (newBendDir * cosPhi - nPerp * sinPhi).normalized()
            val newSide = (newBendDir * sinPhi + nPerp * cosPhi).normalized()

            tangent = newTangent
            up = newUp
            side = newSide

            arcs += BendArc3D(
                bendItemIndex = bend.bendItemIndex,
                markLabel = bend.markLabel,
                positionOnStickIn = bend.markPosIn.toDouble(),
                startPoint = startPt,
                centerPoint = midPt,
                endPoint = endPt,
                angleDeg = bend.angleDeg,
                axialRotationDeg = normalizeRotationDeg(bend.axialRotationDeg),
                radiusIn = r,
            )
        }

        // Trailing cut segment up to cutLengthIn
        val cutEnd = plan.cutLengthDouble
        val trailingCut = (cutEnd - cursorS).coerceAtLeast(0.0)
        if (trailingCut > 1e-6) {
            pos += tangent * trailingCut
            points += pos
            totalLen += trailingCut
        }

        val remainingPts = if (!plan.overflowsStick && plan.remainingStraightDouble > 1e-6) {
            listOf(pos, pos + tangent * plan.remainingStraightDouble)
        } else {
            emptyList()
        }

        val allPts = points + remainingPts
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var minZ = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        var maxZ = Double.NEGATIVE_INFINITY
        for (p in allPts) {
            minX = min(minX, p.x); minY = min(minY, p.y); minZ = min(minZ, p.z)
            maxX = max(maxX, p.x); maxY = max(maxY, p.y); maxZ = max(maxZ, p.z)
        }

        return Pipe3DPath(
            points = points,
            remainingPoints = remainingPts,
            bends = arcs,
            totalLengthIn = totalLen,
            minBounds = Vec3(minX, minY, minZ),
            maxBounds = Vec3(maxX, maxY, maxZ),
        )
    }
}

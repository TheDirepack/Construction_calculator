package com.thedirepack.constructioncalc.feature.conduitbending.domain

import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Dimension
import com.thedirepack.constructioncalc.core.model.StoredQuantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class Pipe3DGeometryTest {
    private fun q(v: Long) = StoredQuantity(Rational.of(v).toString(), Dimension.LENGTH)

    @Test
    fun zeroRollOffsetMovesInPositiveZOnly() {
        val stick = ConduitStick(
            conduit = ConduitType.EMT,
            conduitSize = TradeSize.S_1_2,
            stickLengthIn = 120.0,
            bends = listOf(
                StickBendItem(
                    id = "o1",
                    type = BendType.OFFSET,
                    angle = "30",
                    offsetDepth = q(6),
                    distance = q(20),
                    axialRotationDeg = 0.0,
                ),
            ),
        )
        val plan = StickPlanner.plan(stick)
        val path = Pipe3DGeometry.build(plan)
        val end = path.points.last()

        assertEquals(Vec3.ZERO, path.points.first())
        assertEquals(0.0, end.y, 1e-4)
        assertEquals(6.0, end.z, 1e-3)
        assertTrue(path.remainingPoints.isNotEmpty())
        assertEquals(6.0, path.remainingPoints.last().z, 1e-3)
    }

    @Test
    fun ninetyDegreeRollOffsetMovesInPositiveYOnly() {
        val stick = ConduitStick(
            bends = listOf(
                StickBendItem(
                    id = "o1",
                    type = BendType.OFFSET,
                    angle = "30",
                    offsetDepth = q(6),
                    distance = q(20),
                    axialRotationDeg = 90.0,
                ),
            ),
        )
        val path = Pipe3DGeometry.build(StickPlanner.plan(stick))
        val end = path.points.last()
        assertEquals(6.0, end.y, 1e-3)
        assertEquals(0.0, end.z, 1e-4)
    }

    @Test
    fun arbitrary45And225And315DegreeRollsProduceExactRotatedDisplacement() {
        for (rollDeg in listOf(45.0, 135.0, 225.0, 315.0)) {
            val stick = ConduitStick(
                bends = listOf(
                    StickBendItem(
                        id = "o1",
                        type = BendType.OFFSET,
                        angle = "30",
                        offsetDepth = q(8),
                        distance = q(20),
                        axialRotationDeg = rollDeg,
                    ),
                ),
            )
            val path = Pipe3DGeometry.build(StickPlanner.plan(stick))
            val end = path.points.last()
            val rad = Math.toRadians(rollDeg)
            assertEquals("Z mismatch at $rollDeg°", 8.0 * cos(rad), end.z, 1e-2)
            assertEquals("Y mismatch at $rollDeg°", 8.0 * sin(rad), end.y, 1e-2)
        }
    }

    @Test
    fun doglegStubUpFollowedBy90DegreeRolledOffsetTurnsIntoYAxis() {
        // Bend 1: 90° stub-up at 0° roll -> turns from +X into +Z.
        // Bend 2: 30° offset of 4" at 90° roll after the stub -> offsets +4" along +Y while climbing +Z.
        val stick = ConduitStick(
            conduit = ConduitType.EMT,
            conduitSize = TradeSize.S_1_2,
            stickLengthIn = 120.0,
            bends = listOf(
                StickBendItem(
                    id = "s1",
                    type = BendType.STUB_UP,
                    stubHeight = q(14),
                    axialRotationDeg = 0.0,
                ),
                StickBendItem(
                    id = "o2",
                    type = BendType.OFFSET,
                    angle = "30",
                    offsetDepth = q(4),
                    distance = q(20),
                    distanceAnchor = DistanceAnchor.FROM_PREVIOUS,
                    axialRotationDeg = 90.0,
                ),
            ),
        )
        val plan = StickPlanner.plan(stick)
        val path = Pipe3DGeometry.build(plan)
        val end = path.points.last()
        val remEnd = path.remainingPoints.last()

        // After Bend 1 (along +Z), the offset moves +4" in +Y while remaining parallel to +Z
        assertEquals(4.0, end.y, 1e-2)
        assertEquals(4.0, remEnd.y, 1e-2)
        // Remaining straight stick climbs strictly along +Z (so X and Y stay constant along remainingPoints)
        assertEquals(end.x, remEnd.x, 1e-3)
        assertTrue(remEnd.z > end.z + 10.0)
        // Curved centerline length equals layout cut length minus the two 30° bends' small-angle arc gains (~0.114").
        assertEquals(plan.cutLengthDouble, path.totalLengthIn, 0.15)
        assertTrue(path.totalLengthIn <= plan.cutLengthDouble + 1e-6)
    }

    @Test
    fun rollingOffsetCombinesRiseAndRollIn3D() {
        // Rise = 3", Roll = 4" -> trueOffset = 5" at atan2(4, 3) ≈ 53.13° from +Z toward +Y
        val stick = ConduitStick(
            bends = listOf(
                StickBendItem(
                    id = "r1",
                    type = BendType.ROLLING_OFFSET,
                    angle = "30",
                    rise = q(3),
                    roll = q(4),
                    distance = q(20),
                    axialRotationDeg = 0.0,
                ),
            ),
        )
        val path = Pipe3DGeometry.build(StickPlanner.plan(stick))
        val end = path.points.last()
        assertEquals(3.0, end.z, 1e-2)
        assertEquals(4.0, end.y, 1e-2)
    }

    @Test
    fun fourPointSaddleReturnsToOriginalLine() {
        val stick = ConduitStick(
            bends = listOf(
                StickBendItem(
                    id = "s4",
                    type = BendType.SADDLE_4,
                    angle = "30",
                    saddleHeight = q(3),
                    saddleWidth = q(10),
                    distance = q(20),
                    axialRotationDeg = 0.0,
                ),
            ),
        )
        val path = Pipe3DGeometry.build(StickPlanner.plan(stick))
        val end = path.points.last()
        assertEquals(0.0, end.y, 1e-3)
        assertEquals(0.0, end.z, 1e-2)
        assertTrue(path.maxBounds.z > 2.95)
    }
}

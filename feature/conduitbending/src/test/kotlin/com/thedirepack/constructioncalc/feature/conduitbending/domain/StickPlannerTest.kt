package com.thedirepack.constructioncalc.feature.conduitbending.domain

import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Dimension
import com.thedirepack.constructioncalc.core.measure.LengthFormat
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import com.thedirepack.constructioncalc.core.model.StoredQuantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StickPlannerTest {
    private fun q(n: Long, d: Long = 1) = StoredQuantity(Rational.of(n, d).toString(), Dimension.LENGTH)
    private fun r(n: Long, d: Long = 1) = Rational.of(n, d)

    @Test
    fun singleOffsetMatchesLegacyMarksAndCutStats() {
        val stick = ConduitStick(
            conduit = ConduitType.EMT,
            conduitSize = TradeSize.S_1_2,
            stickLengthIn = 120.0,
            bends = listOf(
                StickBendItem(
                    id = "b1",
                    type = BendType.OFFSET,
                    angle = "30",
                    offsetDepth = q(6),
                    distance = q(20),
                    distanceAnchor = DistanceAnchor.FROM_END_A,
                    axialRotationDeg = 0.0,
                    hookDirection = HookDirection.TOWARD_END_A,
                ),
            ),
        )
        val plan = StickPlanner.plan(stick)
        assertTrue(plan.hasResults)
        assertEquals(2, plan.marks.size)
        assertEquals(BenderAlignment.ARROW, plan.marks[0].alignment)
        assertEquals(BenderAlignment.ARROW, plan.marks[1].alignment)
        assertEquals(0.0, plan.marks[0].axialRotationDeg, 1e-9)
        assertEquals(180.0, plan.marks[1].axialRotationDeg, 1e-9)
        assertEquals(HookDirection.TOWARD_END_A, plan.marks[0].hookDirection)

        val expectedShrink = Bends.offset(r(6), 30.0)!!.shrink
        assertEquals(r(20) + expectedShrink, plan.marks[0].positionFromEndAIn)
        assertEquals(r(20) + expectedShrink + r(12), plan.marks[1].positionFromEndAIn)
        assertEquals(60.0, plan.totalDegrees, 1e-9)
        assertFalse(plan.exceeds360Degrees)
        assertFalse(plan.overflowsStick)
        assertFalse(plan.hasShoeClearanceWarning)
    }

    @Test
    fun multiBendStubPlusOffsetPlusSaddleComputesCumulativeTapeMarksFromEndA() {
        // 1/2" EMT: takeUp = 5", gain = 2-3/4" (11/4)
        // Bend 1: 90° Stub-up 14" -> Mark 1 at 14 - 5 = 9" (Arrow, Toward End A). Back exit ref = 14 - 2.75 = 11.25".
        // Bend 2: 30° Offset 4" at 20" from previous bend (From prev):
        //   shrink = 4 * tan(15°) ≈ 1.0718", spacing = 8".
        //   Mark 2a = 11.25 + 20 + 1.0718 = 32.3218"
        //   Mark 2b = 32.3218 + 8 = 40.3218"
        // Bend 3: 45° 3-pt saddle 2" at 20" from previous bend (From prev):
        //   outerDist = 5", shrink = 3/8" (0.375").
        //   center = 40.3218 + 20 + 0.375 = 60.6968" (Center notch!)
        //   near = 55.6968" (Arrow), far = 65.6968" (Arrow)
        val stick = ConduitStick(
            conduit = ConduitType.EMT,
            conduitSize = TradeSize.S_1_2,
            stickLengthIn = 120.0,
            bends = listOf(
                StickBendItem(
                    id = "stub1",
                    type = BendType.STUB_UP,
                    stubHeight = q(14),
                    axialRotationDeg = 0.0,
                    hookDirection = HookDirection.TOWARD_END_A,
                ),
                StickBendItem(
                    id = "off2",
                    type = BendType.OFFSET,
                    angle = "30",
                    offsetDepth = q(4),
                    distance = q(20),
                    distanceAnchor = DistanceAnchor.FROM_PREVIOUS,
                    axialRotationDeg = 90.0,
                    hookDirection = HookDirection.TOWARD_END_A,
                ),
                StickBendItem(
                    id = "sad3",
                    type = BendType.SADDLE_3,
                    saddleAngle = "45",
                    saddleHeight = q(2),
                    distance = q(20),
                    distanceAnchor = DistanceAnchor.FROM_PREVIOUS,
                    axialRotationDeg = 0.0,
                    hookDirection = HookDirection.TOWARD_END_B,
                ),
            ),
        )
        val plan = StickPlanner.plan(stick)
        assertTrue(plan.hasResults)
        assertEquals(6, plan.marks.size)

        // Marks strictly ascending along End A tape
        for (i in 0 until plan.marks.size - 1) {
            assertTrue(plan.marks[i].positionIn < plan.marks[i + 1].positionIn)
        }

        // Stub mark
        assertEquals(r(9), plan.marks[0].positionFromEndAIn)
        assertEquals(BenderAlignment.ARROW, plan.marks[0].alignment)
        assertEquals(HookDirection.TOWARD_END_A, plan.marks[0].hookDirection)

        // Offset marks (90° roll and 270° return roll)
        val offShrink = Bends.offset(r(4), 30.0)!!.shrink
        val backExit = r(14) - r(11, 4) // 11.25"
        assertEquals(backExit + r(20) + offShrink, plan.marks[1].positionFromEndAIn)
        assertEquals(backExit + r(20) + offShrink + r(8), plan.marks[2].positionFromEndAIn)
        assertEquals(90.0, plan.marks[1].axialRotationDeg, 1e-9)
        assertEquals(270.0, plan.marks[2].axialRotationDeg, 1e-9)

        // 3-pt saddle marks: Arrow, Center notch, Arrow
        val sadCenter = plan.marks[2].positionFromEndAIn + r(20) + r(3, 8)
        assertEquals(sadCenter - r(5), plan.marks[3].positionFromEndAIn)
        assertEquals(sadCenter, plan.marks[4].positionFromEndAIn)
        assertEquals(sadCenter + r(5), plan.marks[5].positionFromEndAIn)
        assertEquals(BenderAlignment.ARROW, plan.marks[3].alignment)
        assertEquals(BenderAlignment.CENTER_NOTCH, plan.marks[4].alignment)
        assertEquals(BenderAlignment.ARROW, plan.marks[5].alignment)
        assertEquals(HookDirection.TOWARD_END_B, plan.marks[4].hookDirection)

        // Total degrees: 90 + 60 + 90 = 240°
        assertEquals(240.0, plan.totalDegrees, 1e-9)
        assertFalse(plan.exceeds360Degrees)
        assertFalse(plan.overflowsStick)
        assertFalse(plan.hasShoeClearanceWarning)
        assertTrue(plan.remainingStraightDouble > 40.0)
    }

    @Test
    fun twoSequentialNinetyStubsMatchBackToBackStarAndCutLength() {
        val stick = ConduitStick(
            conduit = ConduitType.EMT,
            conduitSize = TradeSize.S_1_2,
            stickLengthIn = 120.0,
            bends = listOf(
                StickBendItem(
                    id = "s1",
                    type = BendType.STUB_UP,
                    stubHeight = q(10),
                    hookDirection = HookDirection.TOWARD_END_A,
                ),
                StickBendItem(
                    id = "s2",
                    type = BendType.STUB_UP,
                    distance = q(30),
                    distanceAnchor = DistanceAnchor.FROM_PREVIOUS,
                    stubHeight = q(12),
                    hookDirection = HookDirection.TOWARD_END_B,
                ),
            ),
        )
        val plan = StickPlanner.plan(stick)
        // 1/2" EMT: takeUp = 5", gain = 2-3/4" (11/4)
        // Mark 1 (Arrow, End A): 10 - 5 = 5"
        // Mark 2 (Star, End B): 10 + 30 - 2.75 = 37.25"
        // Cut length = 10 + 30 + 12 - 2*2.75 = 46.5"
        assertEquals(r(5), plan.marks[0].positionFromEndAIn)
        assertEquals(BenderAlignment.ARROW, plan.marks[0].alignment)
        assertEquals(r(37) + r(1, 4), plan.marks[1].positionFromEndAIn)
        assertEquals(BenderAlignment.STAR, plan.marks[1].alignment)
        assertEquals(r(46) + r(1, 2), plan.cutLengthIn)
        assertEquals(r(120) - (r(46) + r(1, 2)), plan.remainingStraightIn)
        assertEquals(180.0, plan.totalDegrees, 1e-9)
        assertFalse(plan.hasShoeClearanceWarning)
    }

    @Test
    fun detectsStickOverflowOver10FootStick() {
        val stick = ConduitStick(
            conduit = ConduitType.EMT,
            conduitSize = TradeSize.S_1_2,
            stickLengthIn = 120.0,
            tailLengthIn = 18.0,
            bends = listOf(
                StickBendItem(
                    id = "s1",
                    type = BendType.STUB_UP,
                    stubHeight = q(24),
                ),
                StickBendItem(
                    id = "o2",
                    type = BendType.OFFSET,
                    angle = "30",
                    offsetDepth = q(6),
                    distance = q(80),
                    distanceAnchor = DistanceAnchor.FROM_PREVIOUS,
                ),
            ),
        )
        val plan = StickPlanner.plan(stick)
        assertTrue(plan.overflowsStick)
        assertTrue(plan.overflowAmountIn.signum > 0)
        assertEquals(Rational.ZERO, plan.remainingStraightIn)
        assertTrue(plan.issues.any { it.severity == Severity.WARNING && it.text.contains("coupling needed", ignoreCase = true) })
    }

    @Test
    fun warnsWhenTotalDegreesExceed360() {
        val stick = ConduitStick(
            conduit = ConduitType.EMT,
            conduitSize = TradeSize.S_1_2,
            stickLengthIn = 120.0,
            bends = listOf(
                StickBendItem(id = "b1", type = BendType.STUB_UP, stubHeight = q(12)), // 90
                StickBendItem(id = "b2", type = BendType.STUB_UP, distance = q(20), distanceAnchor = DistanceAnchor.FROM_PREVIOUS, hookDirection = HookDirection.TOWARD_END_B), // 90
                StickBendItem(id = "b3", type = BendType.SADDLE_4, saddleHeight = q(2), saddleWidth = q(4), angle = "30", distance = q(18), distanceAnchor = DistanceAnchor.FROM_PREVIOUS), // 120
                StickBendItem(id = "b4", type = BendType.OFFSET, offsetDepth = q(4), angle = "45", distance = q(18), distanceAnchor = DistanceAnchor.FROM_PREVIOUS), // 90 -> total 390°
            ),
        )
        val plan = StickPlanner.plan(stick)
        assertEquals(390.0, plan.totalDegrees, 1e-9)
        assertTrue(plan.exceeds360Degrees)
        assertTrue(plan.issues.any { it.severity == Severity.WARNING && it.text.contains("360") })
    }

    @Test
    fun warnsOnInsufficientShoeClearanceBetweenConsecutiveBends() {
        // 1/2" EMT takeUp = 5" min shoe clearance. Put Bend 2 only 2" after Bend 1 exit.
        val stick = ConduitStick(
            conduit = ConduitType.EMT,
            conduitSize = TradeSize.S_1_2,
            stickLengthIn = 120.0,
            bends = listOf(
                StickBendItem(
                    id = "b1",
                    type = BendType.OFFSET,
                    angle = "30",
                    offsetDepth = q(4),
                    distance = q(15),
                ),
                StickBendItem(
                    id = "b2",
                    type = BendType.KICK,
                    angle = "30",
                    kickHeight = q(3),
                    distance = q(8), // kick run ~5.2", mark ~2.8" past b1 exit -> < 5" shoe clearance
                    distanceAnchor = DistanceAnchor.FROM_PREVIOUS,
                ),
            ),
        )
        val plan = StickPlanner.plan(stick)
        assertTrue(plan.hasShoeClearanceWarning)
        assertTrue(plan.issues.any { it.text.contains("clearance", ignoreCase = true) || it.text.contains("overlap", ignoreCase = true) })
    }

    @Test
    fun warnsWhenSingleCompoundBendMarksCollideOnLargeShoe() {
        // 1-1/4" EMT has takeUp = 11", R = 10.245", arc30 = 5.36".
        // A 1" offset at 30° has spacing = 2", so the two 30° arcs collide!
        val stick = ConduitStick(
            conduit = ConduitType.EMT,
            conduitSize = TradeSize.S_1_1_4,
            bends = listOf(
                StickBendItem(
                    id = "tiny",
                    type = BendType.OFFSET,
                    angle = "30",
                    offsetDepth = q(1),
                    distance = q(20),
                ),
            ),
        )
        val plan = StickPlanner.plan(stick)
        assertTrue(plan.hasShoeClearanceWarning)
        assertTrue(plan.issues.any { it.text.contains("too close", ignoreCase = true) })
    }

    @Test
    fun fromEndACumulativeShrinkAdjustsSecondBendTapeMark() {
        // Bend 1: 30° Offset of 6" at 20" (shrink ≈ 1.6077", Mark 1 = 21.6077", Mark 2 = 33.6077").
        // Bend 2: 30° Offset of 4" anchored FROM_END_A at 50" on the finished run.
        // Because Bend 1 consumed shrink1, Mark 1 of Bend 2 = 50 + shrink1 + shrink2!
        val stick = ConduitStick(
            bends = listOf(
                StickBendItem(id = "b1", type = BendType.OFFSET, angle = "30", offsetDepth = q(6), distance = q(20), distanceAnchor = DistanceAnchor.FROM_END_A),
                StickBendItem(id = "b2", type = BendType.OFFSET, angle = "30", offsetDepth = q(4), distance = q(50), distanceAnchor = DistanceAnchor.FROM_END_A),
            ),
        )
        val plan = StickPlanner.plan(stick)
        val s1 = Bends.offset(r(6), 30.0)!!.shrink
        val s2 = Bends.offset(r(4), 30.0)!!.shrink
        assertEquals(r(50) + s1 + s2, plan.marks[2].positionFromEndAIn)
        assertFalse(plan.hasShoeClearanceWarning)
    }

    @Test
    fun metricMessagesUseMillimeters() {
        val stick = ConduitStick(
            conduit = ConduitType.EMT,
            conduitSize = TradeSize.S_1_2,
            stickLengthIn = 120.0,
            tailLengthIn = 30.0,
            format = LengthFormat(system = UnitSystem.METRIC),
            bends = listOf(
                StickBendItem(id = "b1", type = BendType.STUB_UP, stubHeight = q(100)),
            ),
        )
        val plan = StickPlanner.plan(stick)
        assertTrue(plan.overflowsStick)
        val overflowMsg = plan.issues.first { it.text.contains("coupling needed", ignoreCase = true) }.text
        assertTrue(overflowMsg, overflowMsg.contains("mm") && !overflowMsg.contains("\""))
    }
}

package com.thedirepack.constructioncalc.feature.conduitbending.domain

import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.math.Rational
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.tan

class BendsTest {
    private fun r(n: Long, d: Long = 1) = Rational.of(n, d)
    private fun assertRat(expected: Rational, actual: Rational?) = assertEquals(expected, actual)
    private fun assertNear(expected: Double, actual: Rational?, tol: Double = 0.0005) {
        assertNotNull(actual)
        assertEquals(expected, actual!!.toDouble(), tol)
    }

    // ---- offsets ----------------------------------------------------------------------

    @Test fun offset30DegreesIsTwiceTheDepth() {
        val o = Bends.offset(r(6), 30.0)!!
        assertRat(r(2), o.multiplier)
        assertRat(r(12), o.spacing)
    }

    @Test fun offsetShrinkIsExactTanHalfAngle() {
        val o = Bends.offset(r(6), 30.0)!!
        assertNear(6 * tan(Math.toRadians(15.0)), o.shrink) // 1.6077
        assertNear(1.608, o.shrink, 0.001)
    }

    @Test fun offsetFieldConstantIsAQuarterInchPerInchAt30() {
        val o = Bends.offset(r(6), 30.0)!!
        assertRat(r(3, 2), o.fieldShrink) // 1-1/2"
        assertEquals(r(1, 4), o.field!!.shrinkPerInch)
    }

    @Test fun offsetMultipliersMatchCosecant() {
        assertNear(1.414, Bends.offset(r(1), 45.0)!!.multiplier, 0.001)
        assertNear(2.613, Bends.offset(r(1), 22.5)!!.multiplier, 0.001)
        assertNear(1.155, Bends.offset(r(1), 60.0)!!.multiplier, 0.001)
        assertNear(5.759, Bends.offset(r(1), 10.0)!!.multiplier, 0.001)
    }

    @Test fun offsetFirstMarkAddsShrinkToTheDistance() {
        val o = Bends.offset(r(6), 30.0, distance = r(20))!!
        assertNear(21.608, o.firstMark, 0.001)
        assertNear(33.608, o.secondMark, 0.001)
        assertRat(o.firstMark!! + o.spacing, o.secondMark)
    }

    @Test fun offsetWithoutDistanceHasNoAbsoluteMarks() {
        val o = Bends.offset(r(6), 30.0)!!
        assertNull(o.firstMark)
        assertNull(o.secondMark)
    }

    @Test fun customAngleHasNoFieldConstant() {
        val o = Bends.offset(r(4), 35.0)!!
        assertNull(o.field)
        assertNull(o.fieldShrink)
    }

    @Test fun offsetRejectsBadInput() {
        assertNull(Bends.offset(r(0), 30.0))
        assertNull(Bends.offset(r(-2), 30.0))
        assertNull(Bends.offset(r(6), 0.0))
        assertNull(Bends.offset(r(6), 90.0))
        assertNull(Bends.offset(r(6), Double.NaN))
    }

    @Test fun fractionalDepthStaysExactForRationalMath() {
        val o = Bends.offset(r(9, 2), 30.0)!! // 4-1/2" at 30°
        assertRat(r(9), o.spacing)
    }

    // ---- rolling offset --------------------------------------------------------------

    @Test fun rollingOffsetThreeFourFive() {
        val res = Bends.rollingOffset(r(3), r(4), 30.0)!!
        assertRat(r(5), res.trueOffset)
        assertRat(r(10), res.offset.spacing)
        assertNear(5 * tan(Math.toRadians(15.0)), res.offset.shrink)
    }

    @Test fun rollingOffsetWithZeroRollIsAPlainOffset() {
        val res = Bends.rollingOffset(r(6), r(0), 45.0)!!
        assertRat(r(6), res.trueOffset)
    }

    @Test fun rollingOffsetIrrationalTrueOffset() {
        val res = Bends.rollingOffset(r(6), r(6), 45.0)!!
        assertNear(8.485, res.trueOffset, 0.001)
    }

    @Test fun rollingOffsetNeedsRiseOrRoll() {
        assertNull(Bends.rollingOffset(r(0), r(0), 30.0))
        assertNull(Bends.rollingOffset(r(-1), r(2), 30.0))
    }

    // ---- 3-point saddle -------------------------------------------------------------

    @Test fun saddle45DegreesTwoInchHeight() {
        val s = Bends.saddle3(r(2), 45.0)!!
        assertRat(r(5), s.outerDistance)        // 2.5 × 2"
        assertRat(r(3, 8), s.shrink)            // 3/16" per inch × 2"
        assertEquals(22.5, s.outerAngleDeg, 1e-9)
    }

    @Test fun saddle60DegreesUsesTwoTimesHeight() {
        val s = Bends.saddle3(r(3), 60.0)!!
        assertRat(r(6), s.outerDistance)
        assertRat(r(3, 4), s.shrink) // 30° offset constant 1/4" per inch
    }

    @Test fun saddleCenterMarkAddsTheFieldShrink() {
        // 2" saddle, 3/16" per inch: center mark = 30 + 3/8.
        val s = Bends.saddle3(r(2), 45.0, distanceToCenter = r(30))!!
        assertRat(r(30) + r(3, 8), s.centerMark)
        assertRat(s.centerMark!! - r(5), s.nearMark)
        assertRat(s.centerMark + r(5), s.farMark)
    }

    @Test fun saddleIdealShrinkIsTwoOffsetLegs() {
        val s = Bends.saddle3(r(2), 45.0)!!
        assertNear(2 * 2 * tan(Math.toRadians(11.25)), s.idealShrink)
    }

    @Test fun saddleRejectsBadInput() {
        assertNull(Bends.saddle3(r(0), 45.0))
        assertNull(Bends.saddle3(r(2), 95.0))
    }

    // ---- 4-point saddle -------------------------------------------------------------

    @Test fun fourPointSaddleMarks() {
        val s = Bends.saddle4(r(3), r(10), 30.0)!!
        assertEquals(listOf(r(0), r(6), r(16), r(22)), s.marks)
        assertFalse(s.fromEnd)
        assertNear(2 * 3 * tan(Math.toRadians(15.0)), s.totalShrink)
    }

    @Test fun fourPointSaddleFromTheEndAddsFirstShrink() {
        val s = Bends.saddle4(r(3), r(10), 30.0, distance = r(24))!!
        val shrink = s.offset.shrink
        assertRat(r(24) + shrink, s.marks[0])
        assertRat(r(24) + shrink + r(6), s.marks[1])
        assertRat(r(24) + shrink + r(16), s.marks[2])
        assertRat(r(24) + shrink + r(22), s.marks[3])
        assertTrue(s.fromEnd)
    }

    @Test fun fourPointSaddleAllowsZeroWidthButNotNegative() {
        assertNotNull(Bends.saddle4(r(3), r(0), 45.0))
        assertNull(Bends.saddle4(r(3), r(-1), 45.0))
    }

    // ---- stub-up and back-to-back --------------------------------------------------

    @Test fun stubUpSubtractsTakeUp() {
        val spec = BenderSpec(r(5), r(9, 4))
        assertRat(r(5), Bends.stubUp(r(10), spec)!!.mark)
    }

    @Test fun stubUpTooShortIsNull() {
        val spec = BenderSpec(r(5), r(9, 4))
        assertNull(Bends.stubUp(r(4), spec))
        assertRat(r(0), Bends.stubUp(r(5), spec)!!.mark)
    }

    @Test fun backToBackSecondMarkIsMeasuredFromTheBack() {
        val spec = BenderSpec(r(5), r(9, 4))
        val b = Bends.backToBack(r(10), spec, r(30))!!
        assertRat(r(5), b.first.mark)
        assertRat(r(30), b.secondMarkFromBack)
        assertNull(b.cutLength)
    }

    @Test fun backToBackCutLengthSubtractsGainPerBend() {
        val spec = BenderSpec(r(5), r(9, 4))
        val b = Bends.backToBack(r(10), spec, r(30), secondStub = r(12))!!
        assertRat(r(10) + r(30) + r(12) - r(9, 2), b.cutLength) // 52 − 2×2-1/4
    }

    // ---- kick ----------------------------------------------------------------------

    @Test fun kickTravelRunAndShrink() {
        val k = Bends.kick(r(4), 30.0)!!
        assertRat(r(8), k.travel)
        assertNear(4 * 1.7320508, k.run, 0.001)
        assertNear(4 * tan(Math.toRadians(15.0)), k.shrink)
        assertNull(k.bendMark)
    }

    @Test fun kickBendMarkIsDistanceMinusRun() {
        val k = Bends.kick(r(4), 30.0, distance = r(20))!!
        assertNear(20 - 4 * 1.7320508, k.bendMark, 0.001)
    }

    // ---- bender defaults ----------------------------------------------------------

    @Test fun defaultTakeUpsAreTheCommonHandBenderValues() {
        assertRat(r(5), BenderDefaults.spec(ConduitType.EMT, TradeSize.S_1_2)!!.takeUp)
        assertRat(r(6), BenderDefaults.spec(ConduitType.EMT, TradeSize.S_3_4)!!.takeUp)
        assertRat(r(8), BenderDefaults.spec(ConduitType.EMT, TradeSize.S_1)!!.takeUp)
        assertRat(r(11), BenderDefaults.spec(ConduitType.EMT, TradeSize.S_1_1_4)!!.takeUp)
        assertRat(r(6), BenderDefaults.spec(ConduitType.RMC, TradeSize.S_1_2)!!.takeUp)
        assertNull(BenderDefaults.spec(ConduitType.RMC, TradeSize.S_1_1_4))
    }

    @Test fun defaultGainsAreDerivedAndPlausible() {
        // 2T − (π/2)(T − OD/2), nearest 1/8: 1/2" EMT → 2.70 → 2-3/4"
        assertRat(r(11, 4), BenderDefaults.spec(ConduitType.EMT, TradeSize.S_1_2)!!.gain)
        val gains = Bendable.sizes(ConduitType.EMT).map { BenderDefaults.spec(ConduitType.EMT, it)!!.gain.toDouble() }
        assertEquals(gains.sorted(), gains) // gain grows with size
    }

    @Test fun customTableOverridesDefaults() {
        val table = BenderTable(mapOf(BenderTable.key(ConduitType.EMT, TradeSize.S_1_2) to BenderSpec(r(51, 10), r(2))))
        assertRat(r(51, 10), table.spec(ConduitType.EMT, TradeSize.S_1_2)!!.takeUp)
        assertRat(r(6), table.spec(ConduitType.EMT, TradeSize.S_3_4)!!.takeUp)
    }

    // ---- plans ---------------------------------------------------------------------

    @Test fun offsetPlanListsMarksFromTheEndWhenADistanceIsGiven() {
        val plan = BendPlanner.plan(BendRequest(BendType.OFFSET, size = r(6), angleDeg = 30.0, distance = r(20)))
        assertTrue(plan.marksFromEnd)
        assertEquals(listOf("Mark 1", "Mark 2"), plan.marks.map { it.label })
        assertNear(21.608, plan.marks[0].position, 0.001)
        assertEquals("spacing", plan.figures.first().key)
    }

    @Test fun offsetPlanFlagsSpaceThatIsTooSmall() {
        val plan = BendPlanner.plan(BendRequest(BendType.OFFSET, size = r(6), angleDeg = 30.0, space = r(10)))
        assertTrue(plan.issues.any { it.severity == Severity.ERROR })
        val fits = BendPlanner.plan(BendRequest(BendType.OFFSET, size = r(6), angleDeg = 30.0, space = r(12)))
        assertFalse(fits.issues.any { it.severity == Severity.ERROR })
    }

    @Test fun planWithMissingInputsIsEmptyWithoutErrors() {
        val plan = BendPlanner.plan(BendRequest(BendType.OFFSET, angleDeg = 30.0))
        assertFalse(plan.hasResults)
        assertTrue(plan.issues.isEmpty())
    }

    @Test fun planRejectsBadAngle() {
        val plan = BendPlanner.plan(BendRequest(BendType.OFFSET, size = r(6), angleDeg = 120.0))
        assertFalse(plan.hasResults)
        assertEquals(Severity.ERROR, plan.issues.single().severity)
    }

    @Test fun saddlePlanFlagsAnObstructionTooCloseToTheEnd() {
        val plan = BendPlanner.plan(BendRequest(BendType.SADDLE_3, size = r(4), angleDeg = 45.0, distance = r(6)))
        assertTrue(plan.issues.any { it.severity == Severity.ERROR })
    }

    @Test fun stubPlanUsesTheBenderTable() {
        val plan = BendPlanner.plan(BendRequest(BendType.STUB_UP, size = r(10), conduit = ConduitType.EMT, conduitSize = TradeSize.S_1_2))
        assertRat(r(5), plan.marks.single().position)
        assertTrue(plan.figures.first().isMark)
    }

    @Test fun stubPlanErrorsWhenShorterThanTakeUp() {
        val plan = BendPlanner.plan(BendRequest(BendType.STUB_UP, size = r(4), conduit = ConduitType.EMT, conduitSize = TradeSize.S_1_2))
        assertFalse(plan.hasResults)
        assertEquals(Severity.ERROR, plan.issues.single().severity)
    }

    @Test fun backToBackPlanAddsSecondMarkAndCutLength() {
        val plan = BendPlanner.plan(
            BendRequest(BendType.STUB_UP, size = r(10), conduitSize = TradeSize.S_1_2, backToBack = true, between = r(30), secondStub = r(12)),
        )
        assertTrue(plan.figures.any { it.key == "second_from_back" && it.value == r(30) })
        assertTrue(plan.figures.any { it.key == "cut_length" })
    }

    @Test fun saddleCenterMarkMatchesGeometry() {
        // Ideal bends: the conduit up to the center bend is longer than the floor distance by
        // height × (csc − cot) of the outer angle = height × tan(outer/2). The field figure must agree
        // to within 1/32" per inch, and the whole saddle's ideal shrink is twice that.
        for ((center, outer) in listOf(45.0 to 22.5, 60.0 to 30.0)) {
            val h = 4.0
            val s = Bends.saddle3(r(4), center, distanceToCenter = r(0))!!
            val geometry = h * tan(Math.toRadians(outer / 2))
            assertEquals(geometry, s.centerMark!!.toDouble(), h / 32)
            assertEquals(2 * geometry, s.idealShrink.toDouble(), 1e-6)
        }
    }

    @Test fun eachPlanReportsItsTotalBendDegrees() {
        assertEquals(60.0, BendPlanner.plan(BendRequest(BendType.OFFSET, size = r(4), angleDeg = 30.0)).totalDegrees!!, 1e-9)
        assertEquals(90.0, BendPlanner.plan(BendRequest(BendType.SADDLE_3, size = r(2), angleDeg = 45.0)).totalDegrees!!, 1e-9)
        assertEquals(120.0, BendPlanner.plan(BendRequest(BendType.SADDLE_4, size = r(3), width = r(6), angleDeg = 30.0)).totalDegrees!!, 1e-9)
        assertEquals(90.0, BendPlanner.plan(BendRequest(BendType.STUB_UP, size = r(10))).totalDegrees!!, 1e-9)
        assertEquals(180.0, BendPlanner.plan(BendRequest(BendType.STUB_UP, size = r(10), backToBack = true, between = r(30))).totalDegrees!!, 1e-9)
        assertEquals(22.5, BendPlanner.plan(BendRequest(BendType.KICK, size = r(4), angleDeg = 22.5)).totalDegrees!!, 1e-9)
    }
}

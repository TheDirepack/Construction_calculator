package com.thedirepack.constructioncalc.feature.pullplanner.domain

import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.FrictionPreset
import com.thedirepack.constructioncalc.core.electrical.InsulationType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import com.thedirepack.constructioncalc.core.model.EquipmentRating
import com.thedirepack.constructioncalc.core.model.PathSegment
import com.thedirepack.constructioncalc.core.model.PullCable
import com.thedirepack.constructioncalc.core.model.PullSetup
import com.thedirepack.constructioncalc.core.model.Raceway
import com.thedirepack.constructioncalc.core.model.Run
import com.thedirepack.constructioncalc.core.model.RunPath
import com.thedirepack.constructioncalc.core.model.design.PullAnalysis
import com.thedirepack.constructioncalc.core.model.design.PullDirection
import com.thedirepack.constructioncalc.core.model.stored
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickPullEstimateTest {
    private fun ft(n: Long) = Quantity.feet(Rational.of(n))

    @Test
    fun quickCheckMatchesPullAnalysisOnSynthesizedDetailedSegments() {
        val raceway = Raceway(ConduitType.EMT, TradeSize.S_2)
        val setup = PullSetup(
            friction = FrictionPreset.LUBRICATED,
            slackPerEnd = ft(3).stored(),
            cablesFromCircuits = false,
            cables = listOf(PullCable(3, ConductorSize.AWG_4_0, InsulationType.THHN, weightLbPerFt = 0.7, diameterIn = 0.6)),
            equipment = listOf(EquipmentRating("Rope", 3000.0)),
        )

        val quick = QuickPullEstimate.evaluate(
            totalLength = ft(150),
            bends90 = 2,
            bends45 = 1,
            raceway = raceway,
            setup = setup,
            units = UnitSystem.IMPERIAL,
        )
        val segments = QuickPullEstimate.toDetailedSegments(
            totalLength = ft(150),
            bends90 = 2,
            bends45 = 1,
            raceway = raceway,
        )
        assertEquals(segments, quick.segments)
        assertEquals(7, segments.size) // 3 bends + 4 straights
        assertEquals(1800.0, RunPath.lengthInches(segments, raceway.conduitType, raceway.tradeSize), 1e-6)

        val detailed = PullAnalysis.analyze(
            Run(name = "Detailed", raceway = raceway, path = segments, pull = setup),
            runDesign = null,
            units = UnitSystem.IMPERIAL,
        )

        assertEquals(detailed.forward!!.peakTension, quick.analysis.forward!!.peakTension, 1e-9)
        assertEquals(detailed.reverse!!.peakTension, quick.analysis.reverse!!.peakTension, 1e-9)
        assertEquals(detailed.maxPressure!!, quick.analysis.maxPressure!!, 1e-9)
        assertEquals(detailed.recommended, quick.analysis.recommended)
        assertEquals(detailed.tensionLimit, quick.analysis.tensionLimit)
        assertEquals(detailed.pressureLimit, quick.analysis.pressureLimit)
        assertEquals(detailed.jamRatio!!, quick.analysis.jamRatio!!, 1e-9)
        assertEquals(detailed.clearanceIn!!, quick.analysis.clearanceIn!!, 1e-9)
        assertEquals(detailed.routedLengthIn!!, quick.analysis.routedLengthIn!!, 1e-9)
        assertEquals(detailed.cableLengthIn!!, quick.analysis.cableLengthIn!!, 1e-9)
        assertEquals(225.0, quick.analysis.totalDegrees, 1e-9)
        assertEquals(detailed.notes, quick.analysis.notes)
        assertEquals(PullCheckStatus.PASS, quick.verdict!!.status)
    }

    @Test
    fun straightOnlyPullEmitsSingleStraightAndZeroBends() {
        val raceway = Raceway(ConduitType.EMT, TradeSize.S_1)
        val setup = PullSetup(
            cablesFromCircuits = false,
            cables = listOf(PullCable(3, ConductorSize.AWG_10, InsulationType.THHN)),
        )
        val quick = QuickPullEstimate.evaluate(totalLength = ft(100), bends90 = 0, raceway = raceway, setup = setup)
        assertEquals(1, quick.segments.size)
        val straight = quick.segments.single() as PathSegment.Straight
        assertEquals(1200.0, straight.lengthInches, 1e-9)
        assertNull(quick.verdict!!.peakSwbpLbPerFt)
        assertEquals(0.0, quick.verdict.totalBendDegrees, 0.0)
        assertEquals(PullCheckStatus.PASS, quick.verdict.status)
    }

    @Test
    fun missingOrZeroLengthYieldsEmptySegmentsAndNoVerdict() {
        val setup = PullSetup(
            cablesFromCircuits = false,
            cables = listOf(PullCable(3, ConductorSize.AWG_12)),
        )
        assertTrue(QuickPullEstimate.toDetailedSegments(totalLength = null, bends90 = 2).isEmpty())
        assertTrue(QuickPullEstimate.toDetailedSegments(totalLengthIn = 0.0, bends90 = 2).isEmpty())
        val quick = QuickPullEstimate.evaluate(
            totalLength = null,
            bends90 = 2,
            raceway = Raceway(ConduitType.EMT, TradeSize.S_1, length = ft(100).stored()),
            setup = setup,
        )
        assertTrue(quick.segments.isEmpty())
        assertNull(quick.analysis.forward)
        assertNull(quick.analysis.cableLengthIn)
        assertNull(quick.verdict)
    }

    @Test
    fun shortLengthWhereBendArcsExceedTotalOmitsNegativeStraights() {
        val raceway = Raceway(ConduitType.EMT, TradeSize.S_2)
        val segments = QuickPullEstimate.toDetailedSegments(totalLength = ft(1), bends90 = 4, raceway = raceway)
        assertEquals(4, segments.size)
        assertTrue(segments.all { it is PathSegment.Bend })
    }

    @Test
    fun extraBendsAreNormalizedAndOver360MarksVerdictFail() {
        val raceway = Raceway(ConduitType.EMT, TradeSize.S_2)
        val setup = PullSetup(
            cablesFromCircuits = false,
            cables = listOf(PullCable(3, ConductorSize.AWG_4_0, weightLbPerFt = 0.7, diameterIn = 0.6)),
        )
        val normalized = QuickPullEstimate.normalizeExtraBends(extraBends = listOf(30.0, -10.0), extraBendDegrees = 210.0)
        assertEquals(listOf(30.0, 90.0, 90.0, 30.0), normalized)

        val quick = QuickPullEstimate.evaluate(
            totalLength = ft(200),
            bends90 = 2,
            bends45 = 1,
            extraBendDegrees = 150.0,
            raceway = raceway,
            setup = setup,
        )
        assertEquals(375.0, quick.verdict!!.totalBendDegrees, 1e-6)
        assertTrue(quick.verdict.overBendDegrees)
        assertEquals(PullCheckStatus.FAIL, quick.verdict.status)
    }

    @Test
    fun tensionAndSwbpLimitsSetFailStatusAndFlipInspectionWorks() {
        val raceway = Raceway(ConduitType.EMT, TradeSize.S_2)
        val path = listOf(
            PathSegment.Straight(ft(200).stored()),
            PathSegment.Bend(90.0),
            PathSegment.Bend(90.0),
        )
        val setup = PullSetup(
            cablesFromCircuits = false,
            cables = listOf(PullCable(3, ConductorSize.AWG_4_0, weightLbPerFt = 0.7, diameterIn = 0.6)),
            equipment = listOf(EquipmentRating("Rope", 300.0)),
            pressureLimit = 200.0,
        )
        val analysis = PullAnalysis.analyze(Run(name = "Asymmetric", raceway = raceway, path = path, pull = setup), null)
        val recommendedVerdict = PullVerdict.of(analysis)!!
        assertEquals(PullDirection.REVERSE, recommendedVerdict.recommendedDirection)
        assertFalse(recommendedVerdict.isComparingReverse)

        val forwardVerdict = PullVerdict.of(analysis, inspectedDirection = PullDirection.FORWARD)!!
        assertTrue(forwardVerdict.isComparingReverse)
        assertEquals(PullDirection.FORWARD, forwardVerdict.inspectedDirection)
        assertTrue(forwardVerdict.peakTensionLb > recommendedVerdict.peakTensionLb)
        assertTrue(forwardVerdict.overTension)
        assertEquals(PullCheckStatus.FAIL, forwardVerdict.status)
    }

    @Test
    fun suggestedTuggerCapacitySelectsSmallestClassAtOrAboveTension() {
        assertNull(QuickPullEstimate.suggestedTuggerCapacityLb(0.0))
        assertEquals(1000, QuickPullEstimate.suggestedTuggerCapacityLb(450.0))
        assertEquals(2000, QuickPullEstimate.suggestedTuggerCapacityLb(1840.0))
        assertEquals(4000, QuickPullEstimate.suggestedTuggerCapacityLb(2001.0))
        assertEquals(10000, QuickPullEstimate.suggestedTuggerCapacityLb(9500.0))
        assertNull(QuickPullEstimate.suggestedTuggerCapacityLb(25000.0))
    }

    @Test
    fun summarizePathExtractsCountsAndRoutedLength() {
        val raceway = Raceway(ConduitType.EMT, TradeSize.S_1)
        val segments = QuickPullEstimate.toDetailedSegments(
            totalLength = ft(120),
            bends90 = 2,
            bends45 = 1,
            extraBendDegrees = 30.0,
            raceway = raceway,
        )
        val summary = QuickPullEstimate.summarizePath(segments, raceway.conduitType, raceway.tradeSize)
        assertEquals(2, summary.bends90)
        assertEquals(1, summary.bends45)
        assertEquals(30.0, summary.extraBendDegrees, 1e-6)
        assertEquals(1440.0, summary.totalLength!!.toQuantity().value.toDouble(), 1e-6)
        assertNotNull(summary.totalLength)
    }
}

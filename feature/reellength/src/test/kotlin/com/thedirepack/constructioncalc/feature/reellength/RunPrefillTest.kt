package com.thedirepack.constructioncalc.feature.reellength

import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.PathSegment
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.PullCable
import com.thedirepack.constructioncalc.core.model.PullSetup
import com.thedirepack.constructioncalc.core.model.Raceway
import com.thedirepack.constructioncalc.core.model.Run
import com.thedirepack.constructioncalc.core.model.design.PullAnalysis
import com.thedirepack.constructioncalc.core.model.stored
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunPrefillTest {
    private fun ft(n: Long) = Quantity.feet(Rational.of(n)).stored()

    private val run = Run(
        name = "Feeder", raceway = Raceway(ConduitType.EMT, TradeSize.S_2),
        path = listOf(PathSegment.Straight(ft(120)), PathSegment.Bend(90.0), PathSegment.Straight(ft(30))),
        pull = PullSetup(cablesFromCircuits = false, slackPerEnd = ft(3), cables = listOf(PullCable(3, ConductorSize.AWG_4_0, diameterIn = 0.6))),
    )
    private val project = Project(name = "Job", createdAt = 0, runs = listOf(run))

    @Test fun takesCableNeededAndOdFromTheRunsPullAndKeepsTheReel() {
        val reel = ReelLengthInputs(flange = Quantity.inches(Rational.of(36L)).stored())
        val filled = reelInputsForRun(reel, project, run.id)!!
        val pull = PullAnalysis.analyze(project, run.id)!!
        assertEquals(pull.cableLengthIn!!, filled.cableNeeded!!.toQuantity().value.toDouble(), 1e-6)
        assertEquals(0.6, filled.cableDiameter!!.toQuantity().value.toDouble(), 1e-9)
        assertEquals(reel.flange, filled.flange)
    }

    @Test fun missingRunGivesNothing() {
        assertNull(reelInputsForRun(ReelLengthInputs(), project, "nope"))
    }

    @Test fun typedDiameterCanBeSavedAsANamedCable() {
        assertNull(namedCableFor(ReelLengthInputs()))
        val named = namedCableFor(ReelLengthInputs(cableDiameter = Quantity.inches(Rational.of(3L, 4L)).stored()))!!
        assertEquals(0.75, named.diameterIn, 1e-12)
    }
}

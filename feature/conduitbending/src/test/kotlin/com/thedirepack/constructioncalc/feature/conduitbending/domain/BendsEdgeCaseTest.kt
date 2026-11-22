package com.thedirepack.constructioncalc.feature.conduitbending.domain

import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.math.Rational
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Edge-case tests for negative marks and impossible stub heights in Bends. */
class BendsEdgeCaseTest {

    @Test
    fun kickCanProduceNegativeBendMarkWhenDistanceIsLessThanRun() {
        // 6" kick at 30° has run = 6 * cot(30°) ≈ 10.39". Distance 5" gives negative bendMark.
        val result = Bends.kick(height = Rational.of(6), angleDeg = 30.0, distance = Rational.of(5))
        assertNotNull(result)
        assertTrue("bendMark is negative when distance < run", result!!.bendMark!!.signum < 0)
    }

    @Test
    fun backToBackAllowsSecondStubShorterThanTakeUpAndCanProduceNegativeCutLength() {
        val spec = BenderDefaults.spec(ConduitType.EMT, TradeSize.S_3_4)!!
        // takeUp is 6", gain is ~3.25"; secondStub of 1" is unbendable (< takeUp).
        val result = Bends.backToBack(
            height = spec.takeUp,
            spec = spec,
            between = Rational.of(1),
            secondStub = Rational.of(1),
        )
        assertNotNull(result)
        assertTrue("secondStub < takeUp was not rejected", result!!.secondStub!! < spec.takeUp)
    }
}

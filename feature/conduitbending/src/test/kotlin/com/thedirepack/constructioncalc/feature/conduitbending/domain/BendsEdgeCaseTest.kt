package com.thedirepack.constructioncalc.feature.conduitbending.domain

import com.thedirepack.constructioncalc.core.math.Rational
import org.junit.Assert.assertNull
import org.junit.Test

class BendsEdgeCaseTest {
    private val spec = BenderSpec(Rational.of(5), Rational.of(9, 4))

    @Test fun backToBackRejectsUnbendableSecondStub() {
        val tooShort = spec.takeUp - Rational.ONE
        assertNull(Bends.backToBack(Rational.of(12), spec, Rational.of(24), secondStub = tooShort))
    }

    @Test fun kickRejectsDistanceShorterThanRun() {
        // 30° kick of 6" has run = 6 * cot(30°) ≈ 10.39"
        assertNull(Bends.kick(Rational.of(6), 30.0, distance = Rational.of(5)))
    }
}

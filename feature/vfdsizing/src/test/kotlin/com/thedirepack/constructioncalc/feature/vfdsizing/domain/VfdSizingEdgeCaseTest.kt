package com.thedirepack.constructioncalc.feature.vfdsizing.domain

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VfdSizingEdgeCaseTest {
    private val base = VfdRequest(hp = 10.0, volts = 460)

    @Test fun rejectsInfiniteCurrentOverrides() {
        assertTrue(VfdSizing.problems(base.copy(nameplateFla = Double.POSITIVE_INFINITY)).isNotEmpty())
        assertTrue(VfdSizing.problems(base.copy(driveInputAmps = Double.POSITIVE_INFINITY)).isNotEmpty())
        assertNull(VfdSizing.calculate(base.copy(nameplateFla = Double.POSITIVE_INFINITY)))
    }
}

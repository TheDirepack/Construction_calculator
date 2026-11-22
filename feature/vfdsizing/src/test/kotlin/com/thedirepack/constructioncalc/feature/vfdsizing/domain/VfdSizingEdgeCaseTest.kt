package com.thedirepack.constructioncalc.feature.vfdsizing.domain

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Edge-case tests for non-finite inputs in VfdSizing. */
class VfdSizingEdgeCaseTest {

    @Test
    fun nonFiniteNameplateFlaIsCurrentlyAcceptedAndProducesInfiniteOutputs() {
        val request = VfdRequest(
            hp = 10.0,
            volts = 460,
            nameplateFla = Double.POSITIVE_INFINITY,
        )
        assertTrue("problems() does not currently check isFinite()", VfdSizing.problems(request).isEmpty())
        val result = VfdSizing.calculate(request)
        assertNotNull(result)
        assertTrue(result!!.minDriveOutputAmps.isInfinite())
        assertTrue(result.overloadMaxAmps!!.isInfinite())
    }
}

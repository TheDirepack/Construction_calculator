package com.thedirepack.constructioncalc.feature.voltagedrop.domain

import com.thedirepack.constructioncalc.core.electrical.AcPhases
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Edge-case tests for non-finite inputs in VoltageDrop. */
class VoltageDropEdgeCaseTest {

    @Test
    fun nonFinitePositiveInfinityIsCurrentlyAcceptedByProblemsAndProducesInfiniteDrop() {
        val request = VoltageDropRequest(
            system = AcPhases.SINGLE,
            volts = 120.0,
            material = ConductorMaterial.COPPER,
            amps = Double.POSITIVE_INFINITY,
            lengthFeet = 100.0,
            size = ConductorSize.AWG_12,
        )
        assertTrue("problems() does not currently check isFinite()", VoltageDrop.problems(request).isEmpty())
        val result = VoltageDrop.calculate(request)
        assertNotNull(result)
        assertTrue(result!!.dropVolts.isInfinite())
        assertTrue(result.voltsAtLoad.isInfinite())
    }
}

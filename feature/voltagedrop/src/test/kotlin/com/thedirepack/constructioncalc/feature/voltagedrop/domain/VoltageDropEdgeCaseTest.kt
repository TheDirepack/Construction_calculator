package com.thedirepack.constructioncalc.feature.voltagedrop.domain

import com.thedirepack.constructioncalc.core.electrical.AcPhases
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.Table9Conduit
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoltageDropEdgeCaseTest {
    private val base = VoltageDropRequest(
        system = AcPhases.SINGLE,
        volts = 120.0,
        material = ConductorMaterial.COPPER,
        amps = 20.0,
        lengthFeet = 100.0,
        size = ConductorSize.AWG_12,
        method = Method.AC_TABLE_9,
        conduit = Table9Conduit.PVC,
        powerFactor = 0.85,
        parallelSets = 1,
        targetPercent = 3.0,
    )

    @Test fun rejectsInfiniteInputs() {
        assertTrue(VoltageDrop.problems(base.copy(volts = Double.POSITIVE_INFINITY)).isNotEmpty())
        assertTrue(VoltageDrop.problems(base.copy(amps = Double.POSITIVE_INFINITY)).isNotEmpty())
        assertTrue(VoltageDrop.problems(base.copy(lengthFeet = Double.POSITIVE_INFINITY)).isNotEmpty())
        assertNull(VoltageDrop.calculate(base.copy(amps = Double.POSITIVE_INFINITY)))
    }
}

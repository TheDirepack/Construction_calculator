package com.thedirepack.constructioncalc.core.electrical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RacewayInstallTest {

    @Test
    fun burialDepthsMatchTable3005() {
        // Row 0: All locations not specified below → Direct burial 24", RMC/IMC 6", PVC 18", Res 120V GFCI 12", <=30V 6"
        assertEquals(24, RacewayInstall.minimumCoverInches(0, BurialWiringMethod.DIRECT_BURIAL))
        assertEquals(6, RacewayInstall.minimumCoverInches(0, BurialWiringMethod.RMC_IMC))
        assertEquals(18, RacewayInstall.minimumCoverInches(0, BurialWiringMethod.PVC_NONMETALLIC))
        assertEquals(12, RacewayInstall.minimumCoverInches(0, BurialWiringMethod.RESIDENTIAL_120V_GFCI))
        assertEquals(6, RacewayInstall.minimumCoverInches(0, BurialWiringMethod.IRRIGATION_LANDSCAPE_30V))
        // Row 4: Under streets/highways → 24" for all
        assertEquals(24, RacewayInstall.minimumCoverInches(4, BurialWiringMethod.RMC_IMC))
    }

    @Test
    fun racewaySupportIntervalsFollowNec() {
        assertEquals(10.0, RacewayInstall.maxSupportSpacingFt(ConduitType.EMT, TradeSize.S_3_4)!!, 1e-9)
        assertEquals(3.0, RacewayInstall.supportRule(ConduitType.EMT)!!.withinBoxFt, 1e-9)

        // PVC Table 352.30 varies by trade size
        assertEquals(3.0, RacewayInstall.maxSupportSpacingFt(ConduitType.PVC_40, TradeSize.S_1)!!, 1e-9)
        assertEquals(5.0, RacewayInstall.maxSupportSpacingFt(ConduitType.PVC_40, TradeSize.S_1_1_4)!!, 1e-9)
        assertEquals(6.0, RacewayInstall.maxSupportSpacingFt(ConduitType.PVC_80, TradeSize.S_3)!!, 1e-9)
        assertEquals(8.0, RacewayInstall.maxSupportSpacingFt(ConduitType.PVC_40, TradeSize.S_6)!!, 1e-9)

        // RMC general vs threaded couplings
        assertEquals(10.0, RacewayInstall.maxSupportSpacingFt(ConduitType.RMC, TradeSize.S_2, threadedCouplings = false)!!, 1e-9)
        assertEquals(16.0, RacewayInstall.maxSupportSpacingFt(ConduitType.RMC, TradeSize.S_2, threadedCouplings = true)!!, 1e-9)

        // FMC / LFMC
        assertEquals(4.5, RacewayInstall.maxSupportSpacingFt(ConduitType.FMC, TradeSize.S_3_4)!!, 1e-9)
        assertEquals(1.0, RacewayInstall.supportRule(ConduitType.LFMC)!!.withinBoxFt, 1e-9)
    }

    @Test
    fun pvcThermalExpansionMatchesTable35244() {
        // 100 ft run, 100 °F swing → 4.06 in. (expansion fitting required)
        val res = RacewayInstall.pvcExpansionFahrenheit(100.0, 100.0)!!
        assertEquals(4.06, res.expansionIn, 1e-6)
        assertTrue(res.expansionFittingRequired)

        // Short run with tiny swing: 10 ft, 5 °F → 0.0203 in. (< 0.25 in., no fitting required)
        val small = RacewayInstall.pvcExpansionFahrenheit(10.0, 5.0)!!
        assertEquals(0.0203, small.expansionIn, 1e-6)
        assertFalse(small.expansionFittingRequired)

        // Celsius overload matches Fahrenheit
        val celsius = RacewayInstall.pvcExpansionCelsius(100.0, 50.0)!! // 50 °C = 90 °F → 3.654 in.
        assertEquals(3.654, celsius.expansionIn, 1e-6)

        assertNull(RacewayInstall.pvcExpansionFahrenheit(-10.0, 50.0))
    }
}

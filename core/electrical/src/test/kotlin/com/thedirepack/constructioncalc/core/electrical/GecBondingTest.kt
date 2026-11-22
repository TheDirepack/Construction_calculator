package com.thedirepack.constructioncalc.core.electrical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GecBondingTest {

    @Test
    fun standard200AmpCopperServiceTable25066() {
        // 2/0 AWG Cu service → 4 AWG Cu GEC (or 2 AWG Al)
        val cu = GecBonding.size(ConductorSize.AWG_2_0, ConductorMaterial.COPPER, ConductorMaterial.COPPER)
        assertEquals(ConductorSize.AWG_4, cu.gecSize)
        assertFalse(cu.gecCapped)
        assertEquals(ConductorSize.AWG_4, cu.bondingJumperSize)
        assertNull(cu.bondingJumperCmilRequired)

        val alGec = GecBonding.size(ConductorSize.AWG_2_0, ConductorMaterial.COPPER, ConductorMaterial.ALUMINUM)
        assertEquals(ConductorSize.AWG_2, alGec.gecSize)
    }

    @Test
    fun electrodeCapsApplyForGroundRodAndUfer() {
        // 500 kcmil Cu service → full Table 250.66 = 1/0 AWG Cu
        val full = GecBonding.size(ConductorSize.KCMIL_500, electrode = GroundingElectrodeType.FULL_TABLE)
        assertEquals(ConductorSize.AWG_1_0, full.gecSize)
        assertFalse(full.gecCapped)

        // Sole connection to ground rod: capped at 6 AWG Cu (250.66(A))
        val rod = GecBonding.size(ConductorSize.KCMIL_500, electrode = GroundingElectrodeType.ROD_PIPE_PLATE)
        assertEquals(ConductorSize.AWG_6, rod.gecSize)
        assertTrue(rod.gecCapped)
        // Bonding jumper is NOT capped by 250.66(A)
        assertEquals(ConductorSize.AWG_1_0, rod.bondingJumperSize)

        // Sole connection to concrete-encased electrode: capped at 4 AWG Cu (250.66(B))
        val ufer = GecBonding.size(ConductorSize.KCMIL_500, electrode = GroundingElectrodeType.CONCRETE_ENCASED)
        assertEquals(ConductorSize.AWG_4, ufer.gecSize)
        assertTrue(ufer.gecCapped)
    }

    @Test
    fun parallelSetsOver1100KcmilUse12Point5PercentForBondingJumper() {
        // 4 parallel sets of 500 kcmil Cu = 2,000,000 cmil (> 1100 kcmil)
        // Table 250.66 GEC = 3/0 AWG Cu
        // Table 250.102(C)(1) bonding jumper = 12.5% × 2,000,000 = 250,000 cmil → 250 kcmil Cu
        val res = GecBonding.size(
            serviceConductorSize = ConductorSize.KCMIL_500,
            serviceMaterial = ConductorMaterial.COPPER,
            gecMaterial = ConductorMaterial.COPPER,
            parallelSets = 4,
        )
        assertEquals(2_000_000L, res.totalServiceCmil)
        assertEquals(ConductorSize.AWG_3_0, res.gecSize)
        assertEquals(250_000L, res.bondingJumperCmilRequired)
        assertEquals(ConductorSize.KCMIL_250, res.bondingJumperSize)
    }
}

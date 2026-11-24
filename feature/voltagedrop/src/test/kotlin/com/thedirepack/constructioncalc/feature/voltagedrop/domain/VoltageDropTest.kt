package com.thedirepack.constructioncalc.feature.voltagedrop.domain

import com.thedirepack.constructioncalc.core.electrical.Table9Conduit
import com.thedirepack.constructioncalc.core.electrical.AcPhases
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.Stranding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class VoltageDropTest {
    /** 12 AWG solid Cu, 16 A, 100 ft one way, 120 V single-phase, DC (Table 8 uncoated solid = 1.93 Ω/kft). */
    private val dc12 = VoltageDropRequest(
        system = AcPhases.SINGLE, volts = 120.0, material = ConductorMaterial.COPPER,
        amps = 16.0, lengthFeet = 100.0, size = ConductorSize.AWG_12, method = Method.DC_TABLE_8,
        stranding = Stranding.SOLID,
    )

    @Test fun strandedIsTheDefaultAndUsesTheStrandedRow() {
        // Table 8 12 AWG uncoated stranded = 1.98 Ω/kft: 2 · 16 · 100 · 1.98 / 1000 = 6.336 V = 5.28 %
        val r = VoltageDrop.calculate(dc12.copy(stranding = Stranding.STRANDED))!!
        assertEquals(6.336, r.dropVolts, 1e-9)
        assertEquals(5.28, r.percent, 1e-9)
        assertEquals(Stranding.STRANDED, VoltageDropRequest(AcPhases.SINGLE, 120.0, ConductorMaterial.COPPER, 1.0, 1.0, ConductorSize.AWG_12).stranding)
    }

    @Test fun singlePhaseDcByHand() {
        // 2 · 16 · 100 · 1.93 / 1000 = 6.176 V; 6.176 / 120 = 5.1467 %
        val r = VoltageDrop.calculate(dc12)!!
        assertEquals(6.176, r.dropVolts, 1e-9)
        assertEquals(5.14667, r.percent, 1e-4)
        assertEquals(113.824, r.voltsAtLoad, 1e-9)
        assertFalse(r.meetsTarget)
        // Max length at 3 %: 3.6 V · 1000 / (2 · 16 · 1.93) = 58.29 ft
        assertEquals(58.2902, r.maxLengthFeet, 1e-3)
    }

    @Test fun maxLengthIsConsistentWithDrop() {
        val r = VoltageDrop.calculate(dc12)!!
        val atMax = VoltageDrop.calculate(dc12.copy(lengthFeet = r.maxLengthFeet))!!
        assertEquals(3.0, atMax.percent, 1e-9)
    }

    @Test fun threePhaseUsesRootThree() {
        val r = VoltageDrop.calculate(dc12.copy(system = AcPhases.THREE, volts = 208.0))!!
        assertEquals(sqrt(3.0) * 16 * 100 * 1.93 / 1000, r.dropVolts, 1e-9)
        assertEquals(5.3485, r.dropVolts, 1e-3) // 1.73205 · 3.088
    }

    @Test fun parallelSetsDivideTheDrop() {
        val one = VoltageDrop.calculate(dc12)!!
        val three = VoltageDrop.calculate(dc12.copy(parallelSets = 3))!!
        assertEquals(one.dropVolts / 3, three.dropVolts, 1e-9)
        assertEquals(one.maxLengthFeet * 3, three.maxLengthFeet, 1e-6)
    }

    @Test fun table9EffectiveImpedanceByHand() {
        // 4/0 Cu, steel conduit, PF 0.85: Z = 0.063·0.85 + 0.051·sin(acos 0.85) = 0.05355 + 0.026866 = 0.080416
        val request = VoltageDropRequest(
            system = AcPhases.THREE, volts = 480.0, material = ConductorMaterial.COPPER,
            amps = 100.0, lengthFeet = 200.0, size = ConductorSize.AWG_4_0,
            method = Method.AC_TABLE_9, conduit = Table9Conduit.STEEL, powerFactor = 0.85,
        )
        val r = VoltageDrop.calculate(request)!!
        assertEquals(0.080416, r.ohmsPerKft, 1e-5)
        // √3 · 100 · 200 · 0.080416 / 1000 = 2.7857 V; 0.5804 %
        assertEquals(2.7857, r.dropVolts, 1e-3)
        assertEquals(0.5804, r.percent, 1e-3)
        assertTrue(r.meetsTarget)
    }

    @Test fun table9At85MatchesPrintedEffectiveZ() {
        // 12 AWG Cu PVC: 2.0·0.85 + 0.054·0.5268 = 1.728 (Table 9 prints 1.7); Al PVC: 3.2·0.85 + 0.054·0.5268 = 2.748
        assertEquals(1.728, VoltageDrop.impedancePerKft(ConductorSize.AWG_12, ConductorMaterial.COPPER, Method.AC_TABLE_9, Table9Conduit.PVC, 0.85)!!, 0.01)
        assertEquals(2.748, VoltageDrop.impedancePerKft(ConductorSize.AWG_12, ConductorMaterial.ALUMINUM, Method.AC_TABLE_9, Table9Conduit.PVC, 0.85)!!, 0.01)
    }

    @Test fun unityPowerFactorIgnoresReactance() {
        val z = VoltageDrop.impedancePerKft(ConductorSize.AWG_1_0, ConductorMaterial.COPPER, Method.AC_TABLE_9, Table9Conduit.ALUMINUM, 1.0)
        assertEquals(0.13, z!!, 1e-12)
    }

    @Test fun smallestSizeMeetsTargetAndSmallerDoesNot() {
        // Allowed Z at 3 %: 3.6 V / (2 · 16 · 100 / 1000) = 1.125 Ω/kft → 10 AWG (1.21) fails, 8 AWG (0.764) passes
        assertEquals(ConductorSize.AWG_8, VoltageDrop.smallestSize(dc12))
        for (material in ConductorMaterial.entries) for (method in Method.entries) {
            val base = dc12.copy(material = material, method = method, amps = 60.0, lengthFeet = 150.0)
            val size = VoltageDrop.smallestSize(base)!!
            assertTrue(VoltageDrop.calculate(base.copy(size = size))!!.meetsTarget)
            val sizes = VoltageDrop.supportedSizes(material, method)
            val index = sizes.indexOf(size)
            if (index > 0) assertFalse(VoltageDrop.calculate(base.copy(size = sizes[index - 1]))!!.meetsTarget)
        }
    }

    @Test fun smallestSizeNullWhenNothingFits() {
        val huge = dc12.copy(amps = 2000.0, lengthFeet = 5000.0)
        assertNull(VoltageDrop.smallestSize(huge))
        assertNotNull(VoltageDrop.smallestSize(huge.copy(parallelSets = 6, lengthFeet = 300.0)))
    }

    @Test fun dropFallsAsSizeGrows() {
        for (material in ConductorMaterial.entries) for (method in Method.entries) {
            var previous = Double.MAX_VALUE
            for (size in VoltageDrop.supportedSizes(material, method)) {
                val drop = VoltageDrop.calculate(dc12.copy(material = material, method = method, size = size))!!.dropVolts
                assertTrue("${size.label} $material $method", drop < previous)
                previous = drop
            }
        }
    }

    @Test fun aluminumDropsMoreThanCopper() {
        val cu = VoltageDrop.calculate(dc12)!!
        val al = VoltageDrop.calculate(dc12.copy(material = ConductorMaterial.ALUMINUM))!!
        assertTrue(al.dropVolts > cu.dropVolts)
    }

    @Test fun invalidInputsAreReported() {
        assertTrue(VoltageDrop.problems(dc12).isEmpty())
        assertTrue(VoltageDrop.problems(dc12.copy(amps = 0.0)).isNotEmpty())
        assertTrue(VoltageDrop.problems(dc12.copy(lengthFeet = -5.0)).isNotEmpty())
        assertTrue(VoltageDrop.problems(dc12.copy(volts = 0.0)).isNotEmpty())
        assertTrue(VoltageDrop.problems(dc12.copy(parallelSets = 7)).isNotEmpty())
        assertTrue(VoltageDrop.problems(dc12.copy(targetPercent = 0.0)).isNotEmpty())
        assertTrue(VoltageDrop.problems(dc12.copy(method = Method.AC_TABLE_9, powerFactor = 1.2)).isNotEmpty())
        assertTrue(VoltageDrop.problems(dc12.copy(method = Method.AC_TABLE_9, powerFactor = 0.0)).isNotEmpty())
        // PF is ignored by the DC method
        assertTrue(VoltageDrop.problems(dc12.copy(powerFactor = 5.0)).isEmpty())
        assertNull(VoltageDrop.calculate(dc12.copy(amps = Double.NaN)))
    }

    @Test fun sizesNotInTheTableAreRejected() {
        val ac16 = dc12.copy(method = Method.AC_TABLE_9, size = ConductorSize.AWG_16)
        assertTrue(VoltageDrop.problems(ac16).single().contains("Table 9"))
        assertNull(VoltageDrop.calculate(ac16))
        // NEC Table 9 starts aluminum at 12 AWG (Table 8 lists it from 18 AWG).
        val al14 = dc12.copy(material = ConductorMaterial.ALUMINUM, size = ConductorSize.AWG_14, method = Method.AC_TABLE_9)
        assertTrue(VoltageDrop.problems(al14).single().contains("Table 9"))
        assertNotNull(VoltageDrop.calculate(al14.copy(method = Method.DC_TABLE_8)))
        assertNull(VoltageDrop.calculate(dc12.copy(method = Method.AC_TABLE_9, size = ConductorSize.KCMIL_1250)))
        assertNotNull(VoltageDrop.calculate(dc12.copy(size = ConductorSize.KCMIL_1250)))
    }

    @Test fun supportedSizeLists() {
        assertEquals(ConductorSize.AWG_18, VoltageDrop.supportedSizes(ConductorMaterial.COPPER, Method.DC_TABLE_8).first())
        assertEquals(ConductorSize.AWG_14, VoltageDrop.supportedSizes(ConductorMaterial.COPPER, Method.AC_TABLE_9).first())
        assertEquals(ConductorSize.KCMIL_1000, VoltageDrop.supportedSizes(ConductorMaterial.ALUMINUM, Method.AC_TABLE_9).last())
    }

    @Test fun nec310_10_gParallelEligibilityAndEnforcement() {
        assertFalse(VoltageDrop.allowsParallel(ConductorSize.AWG_1))
        assertTrue(VoltageDrop.allowsParallel(ConductorSize.AWG_1_0))
        assertTrue(VoltageDrop.allowsParallel(ConductorSize.KCMIL_500))

        assertFalse(VoltageDrop.violatesParallelMinimum(ConductorSize.AWG_4, 1))
        assertTrue(VoltageDrop.violatesParallelMinimum(ConductorSize.AWG_4, 2))
        assertFalse(VoltageDrop.violatesParallelMinimum(ConductorSize.AWG_1_0, 2))

        // With 2 parallel sets, NEC 310.10(G) enforces at least 1/0 AWG unless explicitly disabled.
        assertEquals(ConductorSize.AWG_1_0, VoltageDrop.smallestSize(dc12.copy(parallelSets = 2)))
        assertEquals(
            ConductorSize.AWG_12,
            VoltageDrop.smallestSize(dc12.copy(parallelSets = 2), enforceParallelMinimum = false),
        )
    }
}

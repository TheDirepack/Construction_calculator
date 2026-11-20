package com.thedirepack.constructioncalc.core.electrical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConductorTablesTest {
    private val cu = ConductorMaterial.COPPER
    private val al = ConductorMaterial.ALUMINUM

    @Test fun table8SpotChecks() {
        assertEquals(1.98, ConductorResistance.ohmsPerKft(ConductorSize.AWG_12, cu)!!, 1e-9)
        assertEquals(1.93, ConductorResistance.ohmsPerKft(ConductorSize.AWG_12, cu, Stranding.SOLID)!!, 1e-9)
        assertEquals(0.778, ConductorResistance.ohmsPerKft(ConductorSize.AWG_8, cu)!!, 1e-9)
        assertEquals(0.491, ConductorResistance.ohmsPerKft(ConductorSize.AWG_6, cu, Stranding.SOLID)!!, 1e-9)
        assertEquals(3.18, ConductorResistance.ohmsPerKft(ConductorSize.AWG_12, al, Stranding.SOLID)!!, 1e-9)
        assertEquals(0.308, ConductorResistance.ohmsPerKft(ConductorSize.AWG_4, cu)!!, 1e-9)
        assertEquals(0.0608, ConductorResistance.ohmsPerKft(ConductorSize.AWG_4_0, cu)!!, 1e-9)
        assertEquals(0.100, ConductorResistance.ohmsPerKft(ConductorSize.AWG_4_0, al)!!, 1e-9)
        assertEquals(0.0258, ConductorResistance.ohmsPerKft(ConductorSize.KCMIL_500, cu)!!, 1e-9)
        assertEquals(0.0424, ConductorResistance.ohmsPerKft(ConductorSize.KCMIL_500, al)!!, 1e-9)
    }

    @Test fun table8Coverage() {
        assertEquals(ConductorSize.entries, ConductorResistance.sizes(cu))
        assertEquals(ConductorSize.entries, ConductorResistance.sizes(al)) // Table 8 lists aluminum from 18 AWG
        assertEquals(5.17, ConductorResistance.ohmsPerKft(ConductorSize.AWG_14, al)!!, 1e-9)
        assertEquals(5.06, ConductorResistance.ohmsPerKft(ConductorSize.AWG_14, al, Stranding.SOLID)!!, 1e-9)
    }

    /** Catches typos: resistance ≈ resistivity ÷ circular mils, and falls as the conductor grows. */
    @Test fun table8MatchesPhysics() {
        for ((material, rho) in listOf(cu to 12.6, al to 20.7)) {
            var previous = Double.MAX_VALUE
            for (size in ConductorResistance.sizes(material)) {
                val r = ConductorResistance.ohmsPerKft(size, material)!!
                val expected = rho * 1000.0 / size.circularMils
                assertEquals("$material ${size.label}", 1.0, r / expected, 0.045)
                assertTrue("$material ${size.label} decreasing", r < previous)
                previous = r
            }
        }
    }

    @Test fun table9SpotChecks() {
        val r4 = ConductorImpedance.row(ConductorSize.AWG_4_0, cu)!!
        assertEquals(0.062, r4.resistance(Table9Conduit.PVC), 1e-9)
        assertEquals(0.067, r4.resistance(Table9Conduit.ALUMINUM), 1e-9)
        assertEquals(0.063, r4.resistance(Table9Conduit.STEEL), 1e-9)
        assertEquals(0.041, r4.reactance(Table9Conduit.PVC), 1e-9)
        assertEquals(0.041, r4.reactance(Table9Conduit.ALUMINUM), 1e-9)
        assertEquals(0.051, r4.reactance(Table9Conduit.STEEL), 1e-9)
        val a12 = ConductorImpedance.row(ConductorSize.AWG_12, al)!!
        assertEquals(3.2, a12.rPvc, 1e-9)
        assertEquals(0.054, a12.xNonMagnetic, 1e-9)
        assertEquals(0.068, a12.xSteel, 1e-9)
        assertEquals(0.015, ConductorImpedance.row(ConductorSize.KCMIL_1000, cu)!!.rPvc, 1e-9)
    }

    @Test fun table9Coverage() {
        assertNull(ConductorImpedance.row(ConductorSize.AWG_16, cu))
        assertNull(ConductorImpedance.row(ConductorSize.KCMIL_1250, cu))
        assertNull(ConductorImpedance.row(ConductorSize.AWG_14, al))
        assertNotNull(ConductorImpedance.row(ConductorSize.AWG_14, cu))
        assertEquals(21, ConductorImpedance.sizes(cu).size)
        assertEquals(20, ConductorImpedance.sizes(al).size)
    }

    /** AC resistance is within a few % of Table 8 DC (Table 9 rounds to 2 figures, e.g. 10 AWG Cu 1.2 vs 1.24), reactance sane. */
    @Test fun table9ConsistentWithTable8() {
        for (material in listOf(cu, al)) for (size in ConductorImpedance.sizes(material)) {
            val dc = ConductorResistance.ohmsPerKft(size, material)!!
            val row = ConductorImpedance.row(size, material)!!
            for (conduit in Table9Conduit.entries) {
                val ratio = row.resistance(conduit) / dc
                assertTrue("$material ${size.label} $conduit ratio $ratio", ratio in 0.95..1.50)
            }
            assertTrue(row.xSteel > row.xNonMagnetic)
            assertTrue(row.rPvc <= row.rAluminum && row.rSteel <= row.rAluminum)
        }
    }

    @Test fun effectiveImpedanceAt85Percent() {
        // 12 AWG Cu in PVC: 2.0·0.85 + 0.054·sin(acos 0.85) = 1.7 + 0.0284 = 1.728 (Table 9 prints 1.7)
        val z = ConductorImpedance.row(ConductorSize.AWG_12, cu)!!.effectiveZ(Table9Conduit.PVC, 0.85)
        assertEquals(1.728, z, 0.001)
        // PF = 1 reduces to R
        assertEquals(2.0, ConductorImpedance.row(ConductorSize.AWG_12, cu)!!.effectiveZ(Table9Conduit.STEEL, 1.0), 1e-9)
    }

    /** Values read from the printed NEC 2023 Table 8 (page 70-725). */
    @Test fun table8CoatedCopperAndStranding() {
        val coated = ConductorMaterial.COATED_COPPER
        assertEquals(2.05, ConductorResistance.ohmsPerKft(ConductorSize.AWG_12, coated)!!, 1e-9)
        assertEquals(2.01, ConductorResistance.ohmsPerKft(ConductorSize.AWG_12, coated, Stranding.SOLID)!!, 1e-9)
        assertEquals(0.0626, ConductorResistance.ohmsPerKft(ConductorSize.AWG_4_0, coated)!!, 1e-9)
        assertEquals(0.00662, ConductorResistance.ohmsPerKft(ConductorSize.KCMIL_2000, coated)!!, 1e-9)
        assertEquals(13.1, ConductorResistance.ohmsPerKft(ConductorSize.AWG_18, al)!!, 1e-9)
        assertEquals(0.00643, ConductorResistance.ohmsPerKft(ConductorSize.KCMIL_2000, cu)!!, 1e-9)
        assertTrue(ConductorResistance.hasSolidOption(ConductorSize.AWG_8))
        assertFalse(ConductorResistance.hasSolidOption(ConductorSize.AWG_6))
    }

    @Test fun copperCladAluminumUsesAluminumValues() {
        val cca = ConductorMaterial.COPPER_CLAD_ALUMINUM
        for (size in ConductorSize.entries) {
            assertEquals(ConductorResistance.ohmsPerKft(size, al), ConductorResistance.ohmsPerKft(size, cca))
        }
        assertEquals(ConductorImpedance.row(ConductorSize.AWG_4_0, al), ConductorImpedance.row(ConductorSize.AWG_4_0, cca))
        assertEquals(ConductorImpedance.row(ConductorSize.AWG_4_0, cu), ConductorImpedance.row(ConductorSize.AWG_4_0, ConductorMaterial.COATED_COPPER))
        assertFalse(ConductorResistance.hasOwnColumn(cca))
        assertFalse(ConductorImpedance.hasOwnColumn(cca))
        assertFalse(ConductorImpedance.hasOwnColumn(ConductorMaterial.COATED_COPPER))
    }

    /** Cells corrected after checking the printed NEC 2023 Table 9. */
    @Test fun table9CheckedCells() {
        assertEquals(0.20, ConductorImpedance.row(ConductorSize.AWG_2, cu)!!.rSteel, 1e-9)
        assertEquals(0.16, ConductorImpedance.row(ConductorSize.AWG_1, cu)!!.rSteel, 1e-9)
        assertEquals(0.085, ConductorImpedance.row(ConductorSize.KCMIL_250, al)!!.rPvc, 1e-9)
        assertEquals(0.027, ConductorImpedance.row(ConductorSize.KCMIL_1000, al)!!.rAluminum, 1e-9)
    }
}

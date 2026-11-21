package com.thedirepack.constructioncalc.core.electrical.formula

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class FormulaCatalogTest {
    private fun solve(id: String, vararg given: Pair<String, Double>) =
        FormulaSolver.solve(FormulaCatalog.find(id)!!, given.toMap())

    private fun assertNear(expected: Double, actual: Double?, tol: Double = 1e-6) {
        requireNotNull(actual) { "no value (expected $expected)" }
        assertEquals(expected, actual, tol * maxOf(1.0, kotlin.math.abs(expected)))
    }

    @Test
    fun ohmsLawFromAnyTwo() {
        // 120 V across 12 Ω: 10 A, 1200 W. Every pair of the four gives the same answer.
        val full = mapOf("V" to 120.0, "I" to 10.0, "R" to 12.0, "P" to 1200.0)
        val ids = full.keys.toList()
        for (a in ids.indices) for (b in a + 1 until ids.size) {
            val s = solve("ohms-law", ids[a] to full.getValue(ids[a]), ids[b] to full.getValue(ids[b]))
            assertTrue("${ids[a]},${ids[b]}: $s", s.isComplete)
            full.forEach { (k, v) -> assertNear(v, s.values[k]) }
        }
    }

    @Test
    fun threePhasePower() {
        // 480 V, 100 A, PF 0.8: S = √3·480·100 = 83,138 VA; P = 66,511 W; Q = 49,883 VAR.
        val s = solve("power-three-phase", "V" to 480.0, "I" to 100.0, "PF" to 0.8)
        assertTrue(s.isComplete)
        assertNear(sqrt(3.0) * 48000, s.values["S"])
        assertNear(sqrt(3.0) * 48000 * 0.8, s.values["P"])
        assertNear(sqrt(3.0) * 48000 * 0.6, s.values["Q"])
        // Back the other way: amps from kW, volts and PF.
        val back = solve("power-three-phase", "V" to 480.0, "P" to sqrt(3.0) * 48000 * 0.8, "PF" to 0.8)
        assertNear(100.0, back.values["I"])
    }

    @Test
    fun singlePhasePowerFactorFromKwAndKva() {
        val s = solve("power-single-phase", "P" to 8000.0, "S" to 10000.0)
        assertNear(0.8, s.values["PF"])
        assertNear(6000.0, s.values["Q"])
        assertTrue(s.unknown.containsAll(listOf("V", "I")))
    }

    @Test
    fun powerFactorAboveOneIsAProblem() {
        val s = solve("power-single-phase", "P" to 12000.0, "S" to 10000.0)
        assertTrue(s.problems.any { it.startsWith("Power factor works out to 1.2") })
    }

    @Test
    fun inconsistentInputsAreAConflict() {
        val s = solve("ohms-law", "V" to 120.0, "I" to 10.0, "R" to 10.0)
        assertTrue(s.conflicts.toString(), "V = I × R" in s.conflicts)
        // Within tolerance (rounded entries) is fine.
        assertTrue(solve("ohms-law", "V" to 120.0, "I" to 10.0, "R" to 12.01).conflicts.isEmpty())
    }

    @Test
    fun outOfRangeEntriesAreRejected() {
        val s = solve("ohms-law", "V" to -5.0, "I" to 2.0)
        assertEquals(listOf("Voltage must be more than zero."), s.problems)
        assertTrue("V" in s.unknown)
    }

    @Test
    fun motorCurrentFromHorsepower() {
        // 10 hp, 460 V, 90% efficient, PF 0.85: 7460 W out, 8289 W in, I = 8289/(√3·460·0.85) = 12.24 A.
        val s = solve("motor-current-three-phase", "HP" to 10.0, "V" to 460.0, "EFF" to 90.0, "PF" to 0.85)
        assertTrue(s.isComplete)
        assertNear(7460.0 / 0.9 / (sqrt(3.0) * 460 * 0.85), s.values["I"])
        // And horsepower back from a measured current.
        val back = solve("motor-current-three-phase", "I" to s.values.getValue("I"), "V" to 460.0, "EFF" to 90.0, "PF" to 0.85)
        assertNear(10.0, back.values["HP"])
    }

    @Test
    fun transformerFaultCurrent() {
        // 75 kVA, 208 V, 3.5 %Z: FLA = 208.2 A, Isc = 5,948 A.
        val s = solve("transformer-three-phase", "S" to 75000.0, "V" to 208.0, "Z" to 3.5)
        val fla = 75000 / (sqrt(3.0) * 208)
        assertNear(fla, s.values["I"])
        assertNear(fla * 100 / 3.5, s.values["ISC"])
        val single = solve("transformer-single-phase", "S" to 25000.0, "V" to 240.0)
        assertNear(25000.0 / 240, single.values["I"])
    }

    @Test
    fun transformerRatio() {
        val s = solve("transformer-ratio", "VP" to 480.0, "VS" to 240.0, "IP" to 50.0)
        assertNear(2.0, s.values["A"])
        assertNear(100.0, s.values["IS"])
    }

    @Test
    fun wyeAndDelta() {
        assertNear(120.089, solve("wye-delta", "VLL" to 208.0).values["VLN"], 1e-4)
        assertNear(480.0, solve("wye-delta", "VLN" to 277.128).values["VLL"], 1e-4)
        assertNear(sqrt(3.0) * 10, solve("wye-delta", "IPH" to 10.0).values["IL"])
    }

    @Test
    fun neutralCurrent() {
        assertNear(0.0, solve("neutral-current", "IA" to 50.0, "IB" to 50.0, "IC" to 50.0).values["IN"])
        assertNear(50.0, solve("neutral-current", "IA" to 50.0, "IB" to 0.0, "IC" to 0.0).values["IN"])
        // 100, 80, 60: √(10000+6400+3600−8000−4800−6000) = √1200
        assertNear(sqrt(1200.0), solve("neutral-current", "IA" to 100.0, "IB" to 80.0, "IC" to 60.0).values["IN"])
    }

    @Test
    fun reactanceAndImpedance() {
        // 60 Hz, 100 mH: XL = 37.70 Ω. With R = 50 Ω and no capacitor: Z = 62.62, PF = 0.798.
        val s = solve("impedance", "F" to 60.0, "L" to 0.1, "XC" to 0.0, "R" to 50.0)
        val xl = 2 * Math.PI * 60 * 0.1
        assertNear(xl, s.values["XL"])
        assertNear(sqrt(2500 + xl * xl), s.values["Z"])
        assertNear(50 / sqrt(2500 + xl * xl), s.values["PF"])
        // 100 µF at 60 Hz: XC = 26.53 Ω.
        assertNear(1 / (2 * Math.PI * 60 * 100e-6), solve("impedance", "F" to 60.0, "C" to 100e-6).values["XC"])
    }

    @Test
    fun seriesAndParallel() {
        val s = solve("series-parallel", "R1" to 10.0, "R2" to 15.0)
        assertNear(25.0, s.values["RS"])
        assertNear(6.0, s.values["RP"])
        assertNear(15.0, solve("series-parallel", "R1" to 10.0, "RP" to 6.0).values["R2"])
        // A parallel total above one branch is impossible.
        assertTrue("R2" in solve("series-parallel", "R1" to 10.0, "RP" to 12.0).unknown)
    }

    @Test
    fun powerFactorCorrection() {
        // 100 kW from 0.7 to 0.95: 100 × (1.0202 − 0.3287) = 69.15 kVAR.
        val s = solve("pf-correction", "P" to 100000.0, "PF1" to 0.7, "PF2" to 0.95)
        assertNear(69_150.0, s.values["QC"], 1e-3)
        assertNear(0.95, solve("pf-correction", "P" to 100000.0, "PF1" to 0.7, "QC" to s.values.getValue("QC")).values["PF2"])
        assertNear(0.7, solve("pf-correction", "P" to 100000.0, "PF2" to 0.95, "QC" to s.values.getValue("QC")).values["PF1"])
    }

    @Test
    fun resistanceAtTemperature() {
        // 12 AWG Cu 1.98 Ω/kft at 75 °C → 20 °C: 1.98 × (1 + 0.00323 × −55) = 1.628.
        val s = solve("resistance-temperature", "R1" to 1.98, "T1" to 75.0, "T2" to 20.0, "ALPHA" to 0.00323)
        assertNear(1.98 * (1 - 0.00323 * 55), s.values["R2"])
        assertNear(20.0, solve("resistance-temperature", "R1" to 1.98, "T1" to 75.0, "R2" to s.values.getValue("R2"), "ALPHA" to 0.00323).values["T2"])
    }

    @Test
    fun voltageDropKFactor() {
        // 20 A, 100 ft one way, 12 AWG Cu (6530 cmil), 120 V: 2·12.9·20·100/6530 = 7.90 V = 6.6 %.
        val s = solve("voltage-drop-single-phase", "K" to 12.9, "I" to 20.0, "L" to 100.0, "CM" to 6530.0, "V" to 120.0)
        assertNear(2 * 12.9 * 20 * 100 / 6530, s.values["VD"])
        assertNear(2 * 12.9 * 20 * 100 / 6530 / 120 * 100, s.values["PCT"])
        // Circular mils for a 3 % drop.
        val cm = solve("voltage-drop-single-phase", "K" to 12.9, "I" to 20.0, "L" to 100.0, "V" to 120.0, "PCT" to 3.0)
        assertNear(2 * 12.9 * 20 * 100 / 3.6, cm.values["CM"])
        val three = solve("voltage-drop-three-phase", "K" to 12.9, "I" to 20.0, "L" to 100.0, "CM" to 6530.0)
        assertNear(sqrt(3.0) * 12.9 * 20 * 100 / 6530, three.values["VD"])
    }

    @Test
    fun energyAndEfficiency() {
        val e = solve("energy-cost", "P" to 1500.0, "T" to 8.0, "RATE" to 0.15)
        assertNear(12.0, e.values["E"])
        assertNear(1.8, e.values["COST"])
        val eff = solve("efficiency", "PIN" to 1000.0, "EFF" to 92.0)
        assertNear(920.0, eff.values["POUT"])
        assertNear(80.0, eff.values["LOSS"])
    }

    @Test
    fun everyFormulaHasUniqueIdsAndSolvableVariables() {
        FormulaCatalog.all.forEach { f ->
            f.variables.forEach { v ->
                assertTrue("${f.id}.${v.id} is in no relation that can solve for it",
                    f.relations.any { v.id in it.solvers } || f.id == "neutral-current")
            }
            assertTrue(f.id, f.relations.isNotEmpty())
        }
    }
}

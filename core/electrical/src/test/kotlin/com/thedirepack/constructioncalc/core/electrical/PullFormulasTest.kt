package com.thedirepack.constructioncalc.core.electrical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

class PullFormulasTest {
    private fun rad(deg: Double) = deg * PI / 180

    // --- Straight and inclined sections (IEEE 1185) ---

    @Test fun levelStraightIsWMuWL() {
        // 100 lb in, 1.5 lb/ft, 200 ft, mu 0.35, W 1.2: 100 + 1.2 × 0.35 × 1.5 × 200 = 226.
        assertEquals(226.0, PullFormulas.straight(100.0, 1.5, 200.0, 0.35, 1.2), 1e-9)
    }

    @Test fun inclinedStraight() {
        // Up 30°: T + wL(sin θ + Wμ cos θ) = 100 + 300 × (0.5 + 0.42 × 0.8660) = 359.12.
        assertEquals(100 + 300 * (0.5 + 0.42 * cos(rad(30.0))), PullFormulas.straight(100.0, 1.5, 200.0, 0.35, 1.2, rad(30.0)), 1e-9)
        // Down 30°: T − wL(sin θ − Wμ cos θ).
        assertEquals(100 - 300 * (0.5 - 0.42 * cos(rad(30.0))), PullFormulas.straight(100.0, 1.5, 200.0, 0.35, 1.2, rad(-30.0)), 1e-9)
        // Straight up: friction drops out.
        assertEquals(400.0, PullFormulas.straight(100.0, 1.5, 200.0, 0.35, 1.2, rad(90.0)), 1e-9)
        // A steep drop can't make tension negative.
        assertEquals(0.0, PullFormulas.straight(10.0, 1.5, 200.0, 0.35, 1.0, rad(-90.0)), 0.0)
    }

    // --- Bends ---

    @Test fun horizontalBendExactVersusExponential() {
        val k = 1.2 * 0.35 * rad(90.0)
        // Weightless cable: cosh + sinh = e^k, so both forms agree.
        assertEquals(500 * exp(k), PullFormulas.horizontalBend(500.0, 0.0, 3.0, rad(90.0), 0.35, 1.2), 1e-9)
        assertEquals(500 * exp(k), PullFormulas.bendSimplified(500.0, rad(90.0), 0.35, 1.2), 1e-9)
        // With weight the exact form is higher: 500 cosh k + √(500² + (2 × 3)²) sinh k.
        val exact = PullFormulas.horizontalBend(500.0, 2.0, 3.0, rad(90.0), 0.35, 1.2)
        assertEquals(500 * kotlin.math.cosh(k) + sqrt(500.0 * 500 + 36) * kotlin.math.sinh(k), exact, 1e-9)
        assertTrue(exact > 500 * exp(k))
        // Zero incoming tension still picks up the weight term: wR sinh k.
        assertEquals(6 * kotlin.math.sinh(k), PullFormulas.horizontalBend(0.0, 2.0, 3.0, rad(90.0), 0.35, 1.2), 1e-9)
    }

    /** RK4 integration of the bend equation the closed forms are derived from. */
    private fun integrate(t1: Double, w: Double, r: Double, theta: Double, k: Double, a1: Double, sag: Boolean): Double {
        val n = 20_000
        val h = theta / n
        fun f(phi: Double, t: Double): Double {
            val a = if (sag) a1 + phi else a1 - phi
            return if (sag) k * (t - w * r * cos(a)) + w * r * sin(a) else k * (t + w * r * cos(a)) + w * r * sin(a)
        }
        var t = t1
        var phi = 0.0
        repeat(n) {
            val s1 = f(phi, t)
            val s2 = f(phi + h / 2, t + h / 2 * s1)
            val s3 = f(phi + h / 2, t + h / 2 * s2)
            val s4 = f(phi + h, t + h * s3)
            t += h / 6 * (s1 + 2 * s2 + 2 * s3 + s4)
            phi += h
        }
        return t
    }

    @Test fun verticalBendsMatchTheirDifferentialEquation() {
        val k = 1.2 * 0.35
        for (a1 in listOf(0.0, rad(30.0), rad(-45.0), rad(-90.0))) {
            val sag = PullFormulas.sagBend(800.0, 3.0, 2.0, rad(90.0), 0.35, 1.2, a1)
            assertEquals("sag from ${a1}", integrate(800.0, 3.0, 2.0, rad(90.0), k, a1, sag = true), sag, 1e-6)
            val hump = PullFormulas.humpBend(800.0, 3.0, 2.0, rad(90.0), 0.35, 1.2, -a1)
            assertEquals("hump from ${-a1}", integrate(800.0, 3.0, 2.0, rad(90.0), k, -a1, sag = false), hump, 1e-6)
        }
    }

    @Test fun verticalBendsWithoutFrictionAreJustLiftOrDrop() {
        // μ = 0: a sag from level up to vertical lifts the cable by R: T + wR.
        assertEquals(1006.0, PullFormulas.sagBend(1000.0, 3.0, 2.0, rad(90.0), 0.0, 1.0, 0.0), 1e-9)
        // A hump from level down to vertical drops it by R: T − wR.
        assertEquals(994.0, PullFormulas.humpBend(1000.0, 3.0, 2.0, rad(90.0), 0.0, 1.0, 0.0), 1e-9)
        // Weightless: both reduce to e^(Wμθ).
        assertEquals(1000 * exp(0.42 * PI / 2), PullFormulas.sagBend(1000.0, 0.0, 2.0, rad(90.0), 0.35, 1.2, 0.0), 1e-9)
        assertEquals(1000 * exp(0.42 * PI / 2), PullFormulas.humpBend(1000.0, 0.0, 2.0, rad(90.0), 0.35, 1.2, 0.0), 1e-9)
    }

    // --- Weight correction and sidewall pressure ---

    @Test fun weightCorrectionFactors() {
        // D = 3, d = 1: x = 0.5.
        assertEquals(1.0, PullFormulas.weightCorrection(CableConfiguration.SINGLE, 3.0, 1.0)!!, 0.0)
        assertEquals(1 / sqrt(0.75), PullFormulas.weightCorrection(CableConfiguration.TRIANGULAR, 3.0, 1.0)!!, 1e-12)
        assertEquals(1 + 4.0 / 3 * 0.25, PullFormulas.weightCorrection(CableConfiguration.CRADLED, 3.0, 1.0)!!, 1e-12)
        assertEquals(1.5, PullFormulas.weightCorrection(CableConfiguration.COMPLEX, 3.0, 1.0)!!, 1e-12)
        // Doesn't fit.
        assertNull(PullFormulas.weightCorrection(CableConfiguration.CRADLED, 1.0, 1.0))
        assertNull(PullFormulas.weightCorrection(CableConfiguration.TRIANGULAR, 1.9, 1.0))
    }

    @Test fun configurationFromCountAndRatio() {
        assertEquals(CableConfiguration.SINGLE, PullFormulas.configuration(1, 2.0, 1.0))
        assertEquals(CableConfiguration.TRIANGULAR, PullFormulas.configuration(2, 4.0, 1.0))
        assertEquals(CableConfiguration.TRIANGULAR, PullFormulas.configuration(3, 2.4, 1.0))
        assertEquals(CableConfiguration.CRADLED, PullFormulas.configuration(3, 2.5, 1.0))
        assertEquals(CableConfiguration.COMPLEX, PullFormulas.configuration(5, 4.0, 1.0))
    }

    /** Southwire sidewall pressure flyer: exit tension 600 lb, radius 2 ft, W = 1.3 for three cables. */
    @Test fun southwireSidewallPressureExamples() {
        assertEquals(300.0, PullFormulas.sidewallPressure(CableConfiguration.SINGLE, 1.0, 600.0, 2.0), 1e-9)
        assertEquals(195.0, PullFormulas.sidewallPressure(CableConfiguration.TRIANGULAR, 1.3, 600.0, 2.0), 1e-9)
        assertEquals(190.0, PullFormulas.sidewallPressure(CableConfiguration.CRADLED, 1.3, 600.0, 2.0), 1e-9)
    }

    @Test fun jamRatioAndClearance() {
        // 1.05 × 2.067 / 0.75 = 2.894: in the jam range.
        val ratio = PullFormulas.jamRatio(2.067, 0.75)
        assertEquals(2.8938, ratio, 1e-4)
        assertTrue(PullFormulas.inJamRange(ratio))
        assertTrue(!PullFormulas.inJamRange(PullFormulas.jamRatio(4.0, 1.0)))
        // One cable: D − d.
        assertEquals(1.0, PullFormulas.clearance(1, 2.0, 1.0)!!, 1e-12)
        // Three cables, D = 3, d = 1: 1.5 − 1.366 + 1 × √0.75.
        assertEquals(1.5 - 1.366 + sqrt(0.75), PullFormulas.clearance(3, 3.0, 1.0)!!, 1e-12)
        // Two cables: D/2 − d/2 + (D − d)/2 √(1 − x²).
        assertEquals(1.0 + sqrt(0.75), PullFormulas.clearance(2, 3.0, 1.0)!!, 1e-12)
        assertNull(PullFormulas.clearance(3, 1.5, 1.0))
    }

    // --- Ratings ---

    /** Southwire tension flyer p. 2 (copper table): 12 AWG is 52, 157 and 167 lb for one, three and four conductors. */
    @Test fun southwireTensionTable() {
        assertEquals(52, PullRatings.conductorTensionTable(ConductorMaterial.COPPER, ConductorSize.AWG_12, 1))
        assertEquals(157, PullRatings.conductorTensionTable(ConductorMaterial.COPPER, ConductorSize.AWG_12, 3))
        assertEquals(167, PullRatings.conductorTensionTable(ConductorMaterial.COPPER, ConductorSize.AWG_12, 4))
        assertEquals(5078, PullRatings.conductorTensionTable(ConductorMaterial.COPPER, ConductorSize.AWG_4_0, 3))
        assertEquals(6400, PullRatings.conductorTensionTable(ConductorMaterial.COPPER, ConductorSize.KCMIL_250, 4))
        // Aluminum table (p. 3): 4/0 is 1270 for one, 3809 for three.
        assertEquals(1270, PullRatings.conductorTensionTable(ConductorMaterial.ALUMINUM, ConductorSize.AWG_4_0, 1))
        assertEquals(3809, PullRatings.conductorTensionTable(ConductorMaterial.ALUMINUM, ConductorSize.AWG_4_0, 3))
        assertEquals(0.0, PullRatings.conductorTension(ConductorMaterial.COPPER, ConductorSize.AWG_12, 0), 0.0)
    }

    @Test fun buildingWirePressurePresetBoundary() {
        assertEquals(500.0, PullRatings.buildingWirePressure(ConductorSize.AWG_14), 0.0)
        assertEquals(500.0, PullRatings.buildingWirePressure(ConductorSize.AWG_10), 0.0)
        assertEquals(1000.0, PullRatings.buildingWirePressure(ConductorSize.AWG_8), 0.0)
        assertEquals(1000.0, PullRatings.buildingWirePressure(ConductorSize.KCMIL_500), 0.0)
    }

    @Test fun weightEstimate() {
        // 12 AWG THHN: metal 6530 cmil × π/4 × 1e-6 × 0.321 × 12 + (0.0133 − 0.006) × 0.0506 × 12.
        val expected = 12 * (6530 * PI / 4e6 * 0.321 + (0.0133 - 0.006) * 0.0506)
        assertEquals(expected, CableWeight.estimate(ConductorSize.AWG_12, InsulationType.THHN, ConductorMaterial.COPPER)!!, 1e-12)
        // Roughly 24 lb per 1000 ft, the order of catalog THHN weights.
        assertTrue(expected in 0.020..0.030)
        val al = CableWeight.estimate(ConductorSize.AWG_4_0, InsulationType.XHHW, ConductorMaterial.ALUMINUM)!!
        val cu = CableWeight.estimate(ConductorSize.AWG_4_0, InsulationType.XHHW, ConductorMaterial.COPPER)!!
        assertTrue(al < cu)
    }

    /**
     * SIMpull workbook (9.18.2020), "Illustration" sheet: 533 ft in 3" GRC, w = 4.233 lb/ft, W = 1.4,
     * 25 lb incoming. Each workbook segment is a straight followed by a bend, and the workbook uses
     * T·e^(Wμθ) for every bend, so this chains [PullFormulas.straight] and
     * [PullFormulas.bendSimplified]. Radii are the sheet's values in feet. Expected values are the
     * sheet's rounded tensions; at μ = 0.14 the sheet has two typed-in cells that disagree with its
     * own chain (707 for 701.9 and 2040 for 2045.3), so the computed values are used there.
     */
    @Test fun simpullIllustrationFixture() {
        data class Seg(val slopeDeg: Double, val lengthFt: Double, val bendDeg: Double, val radiusFt: Double)
        val segs = listOf(
            Seg(90.0, 6.0, 90.0, 1.08), Seg(0.0, 10.0, 60.0, 1.08), Seg(0.0, 1.0, 60.0, 1.08),
            Seg(0.0, 250.0, 90.0, 1.08), Seg(0.0, 90.0, 90.0, 1.08), Seg(90.0, 15.0, 90.0, 1.08),
            Seg(0.0, 106.0, 90.0, 1.37), Seg(0.0, 35.0, 90.0, 1.87), Seg(-90.0, 20.0, 0.0, 0.0),
        )
        fun chain(mu: Double): List<Pair<Double, Double>> {
            var t = 25.0
            return segs.map { s ->
                t = PullFormulas.straight(t, 4.233, s.lengthFt, mu, 1.4, rad(s.slopeDeg))
                if (s.bendDeg > 0) t = PullFormulas.bendSimplified(t, rad(s.bendDeg), mu, 1.4)
                // The workbook's "COMPLEX" pressure is W·T/(2R), the triangular form.
                val swbp = if (s.bendDeg > 0) PullFormulas.sidewallPressure(CableConfiguration.TRIANGULAR, 1.4, t, s.radiusFt) else 0.0
                t to swbp
            }
        }
        val lubricated = chain(0.35)
        val expected35 = listOf(109, 216, 365, 1908, 4522, 9900, 21850, 47334, 47249)
        val swbp35 = listOf(71, 140, 237, 1236, 2931, 6417, 11164, 17718)
        expected35.forEachIndexed { i, e -> assertEquals("μ 0.35 segment ${i + 1}", e.toDouble(), lubricated[i].first, 0.5) }
        swbp35.forEachIndexed { i, e -> assertEquals("μ 0.35 SWBP ${i + 1}", e.toDouble(), lubricated[i].second, 0.5) }
        val simpull = chain(0.14)
        val expected14 = listOf(69.0, 94.0, 117.0, 441.0, 701.9, 1041.0, 1536.0, 2130.0, 2045.25)
        expected14.forEachIndexed { i, e -> assertEquals("μ 0.14 segment ${i + 1}", e, simpull[i].first, 0.5) }
        assertEquals(68.57, simpull[0].first, 0.01)
        assertEquals(797.29, simpull[7].second, 0.01)
    }

    /** Four or more cables never get W below the SIMpull workbook's fixed 1.4 (roomy conduit). */
    @Test fun complexConfigurationHasAMinimumW() {
        // x = 0.5 / (4.0 − 0.5) = 0.143 → 1 + 2x² = 1.04, floored to 1.4.
        org.junit.Assert.assertEquals(1.4, PullFormulas.weightCorrection(CableConfiguration.COMPLEX, 4.0, 0.5)!!, 1e-12)
        // x = 0.5 / (1.5 − 0.5) = 0.5 → 1 + 2x² = 1.5, above the floor.
        org.junit.Assert.assertEquals(1.5, PullFormulas.weightCorrection(CableConfiguration.COMPLEX, 1.5, 0.5)!!, 1e-12)
    }
}

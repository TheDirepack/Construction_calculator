package com.thedirepack.constructioncalc.core.electrical

import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.electrical.ConductorSize.*

/**
 * NEC 2023 Table 310.15(B)(1)(1), "Ambient Temperature Correction Factors Based on 30°C (86°F)",
 * for use with Table 310.16. Checked against the printed table (70-185).
 */
object AmbientCorrection {
    private class Band(val maxC: Double, val c60: Double?, val c75: Double?, val c90: Double?)

    private val bands = listOf(
        Band(10.0, 1.29, 1.20, 1.15),
        Band(15.0, 1.22, 1.15, 1.12),
        Band(20.0, 1.15, 1.11, 1.08),
        Band(25.0, 1.08, 1.05, 1.04),
        Band(30.0, 1.00, 1.00, 1.00),
        Band(35.0, 0.91, 0.94, 0.96),
        Band(40.0, 0.82, 0.88, 0.91),
        Band(45.0, 0.71, 0.82, 0.87),
        Band(50.0, 0.58, 0.75, 0.82),
        Band(55.0, 0.41, 0.67, 0.76),
        Band(60.0, null, 0.58, 0.71),
        Band(65.0, null, 0.47, 0.65),
        Band(70.0, null, 0.33, 0.58),
        Band(75.0, null, null, 0.50),
        Band(80.0, null, null, 0.41),
        Band(85.0, null, null, 0.29),
    )

    /**
     * Factor for [ambientC] (rounded up to the table's whole-degree bands) and the conductor's
     * temperature rating; null when the table has no value (too hot for that insulation).
     */
    fun factor(ambientC: Double, rating: TemperatureRating): Double? {
        val band = bands.firstOrNull { ceilBand(ambientC) <= it.maxC } ?: return null
        return when (rating) {
            TemperatureRating.C60 -> band.c60
            TemperatureRating.C75 -> band.c75
            TemperatureRating.C90 -> band.c90
        }
    }

    // Table rows are whole degrees (e.g. 31–35); 35.4 °C falls in the 36–40 row.
    private fun ceilBand(c: Double) = kotlin.math.ceil(c - 1e-9)

    /**
     * 310.15(B)(2): raceways or cables in direct sunlight on or above a rooftop less than 19 mm
     * (3/4 in.) above the roof get a 33 °C (60 °F) adder to the outdoor temperature.
     * Exception: Type XHHW-2 conductors aren't subject to the adder.
     */
    const val ROOFTOP_ADDER_C = 33.0

    fun effectiveAmbient(ambientC: Double, rooftopUnder3_4In: Boolean, xhhw2: Boolean): Double =
        if (rooftopUnder3_4In && !xhhw2) ambientC + ROOFTOP_ADDER_C else ambientC
}

/**
 * NEC 2023 Table 310.15(C)(1), "Adjustment Factors for More Than Three Current-Carrying
 * Conductors" (70-186). Count per 310.15(E) (neutrals) and (F) (grounding conductors don't count).
 */
object ConductorCountAdjustment {
    fun factor(currentCarrying: Int): Double = when {
        currentCarrying <= 3 -> 1.00
        currentCarrying <= 6 -> 0.80
        currentCarrying <= 9 -> 0.70
        currentCarrying <= 20 -> 0.50
        currentCarrying <= 30 -> 0.45
        currentCarrying <= 40 -> 0.40
        else -> 0.35
    }
}

/**
 * NEC 2023 240.4(D) "Small Conductors": maximum overcurrent protection after correction and
 * adjustment, unless 240.4(E) or (G) permits otherwise. 18 and 16 AWG copper and 14 AWG copper-clad
 * aluminum carry extra conditions (listed devices, continuous load limits) noted in [conditions].
 */
object SmallConductorLimit {
    fun maxOcpd(size: ConductorSize, material: ConductorMaterial): Int? = when {
        material.isCopper -> when (size) {
            AWG_18 -> 7
            AWG_16 -> 10
            AWG_14 -> 15
            AWG_12 -> 20
            AWG_10 -> 30
            else -> null
        }
        material == ConductorMaterial.COPPER_CLAD_ALUMINUM && size == AWG_14 -> 10
        else -> when (size) {
            AWG_12 -> 15
            AWG_10 -> 25
            else -> null
        }
    }

    fun conditions(size: ConductorSize, material: ConductorMaterial): String? = when {
        material.isCopper && size == AWG_18 -> "18 AWG Cu: continuous load ≤ 5.6 A and devices listed for 18 AWG (${CodeRule.SMALL_CONDUCTOR_18_CU.cite})."
        material.isCopper && size == AWG_16 -> "16 AWG Cu: continuous load ≤ 8 A and devices listed for 16 AWG (${CodeRule.SMALL_CONDUCTOR_16_CU.cite})."
        material == ConductorMaterial.COPPER_CLAD_ALUMINUM && size == AWG_14 ->
            "14 AWG CCA: continuous load ≤ 8 A and devices listed for 14 AWG CCA (${CodeRule.SMALL_CONDUCTOR_14_CCA.cite})."
        else -> null
    }
}

/**
 * NEC 2023 Table 250.122 "Minimum Size Equipment Grounding Conductors for Grounding Raceway and
 * Equipment" (70-158), by the rating of the overcurrent device ahead of the circuit.
 */
object EquipmentGroundingConductor {
    private val rows: List<Triple<Int, ConductorSize, ConductorSize>> = listOf(
        Triple(15, AWG_14, AWG_12),
        Triple(20, AWG_12, AWG_10),
        Triple(60, AWG_10, AWG_8),
        Triple(100, AWG_8, AWG_6),
        Triple(200, AWG_6, AWG_4),
        Triple(300, AWG_4, AWG_2),
        Triple(400, AWG_3, AWG_1),
        Triple(500, AWG_2, AWG_1_0),
        Triple(600, AWG_1, AWG_2_0),
        Triple(800, AWG_1_0, AWG_3_0),
        Triple(1000, AWG_2_0, AWG_4_0),
        Triple(1200, AWG_3_0, KCMIL_250),
        Triple(1600, AWG_4_0, KCMIL_350),
        Triple(2000, KCMIL_250, KCMIL_400),
        Triple(2500, KCMIL_350, KCMIL_600),
        Triple(3000, KCMIL_400, KCMIL_600),
        Triple(4000, KCMIL_500, KCMIL_750),
        Triple(5000, KCMIL_700, KCMIL_1250),
        Triple(6000, KCMIL_800, KCMIL_1250),
    )

    /** Minimum EGC for an OCPD of [ocpdAmps]; null above 6000 A. */
    fun size(ocpdAmps: Int, material: ConductorMaterial): ConductorSize? {
        val row = rows.firstOrNull { ocpdAmps <= it.first } ?: return null
        return if (material.isCopper) row.second else row.third
    }

    /**
     * 250.122(B): when ungrounded conductors are made larger than the minimum (e.g. for voltage
     * drop), the EGC is increased in proportion to their circular-mil area. Never smaller than
     * the table size, never larger than the ungrounded conductor.
     */
    fun sizeWithUpsize(
        ocpdAmps: Int,
        material: ConductorMaterial,
        minimumUngrounded: ConductorSize,
        actualUngrounded: ConductorSize,
    ): ConductorSize? {
        val base = size(ocpdAmps, material) ?: return null
        if (actualUngrounded.circularMils <= minimumUngrounded.circularMils) return base
        val ratio = actualUngrounded.circularMils.toDouble() / minimumUngrounded.circularMils
        val needed = base.circularMils * ratio
        val upsized = ConductorSize.entries.firstOrNull { it.circularMils >= needed - 1e-6 } ?: actualUngrounded
        return if (upsized > actualUngrounded) actualUngrounded else upsized
    }
}

/** How a circuit's conductors are arranged, for counting current-carrying conductors (310.15(E)). */
enum class SupplySystem(val label: String) {
    /** 120/240 V single-phase 3-wire. */
    SINGLE_PHASE_3W("1φ 3-wire"),
    /** 3-phase 4-wire wye (208Y/120, 480Y/277). */
    WYE_3PH_4W("3φ 4-wire wye"),
    /** 3-phase 3-wire delta (no neutral). */
    DELTA_3PH_3W("3φ 3-wire delta"),
}

object CurrentCarryingConductors {
    /**
     * Current-carrying conductors for one circuit of [poles] ungrounded conductors, per 310.15(E)/(F):
     * - a neutral that carries only the unbalanced current doesn't count (E)(1);
     * - two phases + neutral of a 4-wire wye: the neutral counts (E)(2);
     * - a wye circuit whose load is mainly nonlinear: the neutral counts (E)(3);
     * - a 1-pole circuit's neutral carries the full load current, so it counts;
     * - equipment grounding conductors never count (F).
     */
    fun count(poles: Int, hasNeutral: Boolean, system: SupplySystem, nonlinear: Boolean = false): Int {
        val hots = poles.coerceIn(1, 3)
        if (!hasNeutral || system == SupplySystem.DELTA_3PH_3W) return if (hots == 1 && hasNeutral) 2 else hots
        return hots + when (hots) {
            1 -> 1
            2 -> if (system == SupplySystem.WYE_3PH_4W || nonlinear) 1 else 0
            else -> if (nonlinear) 1 else 0
        }
    }
}

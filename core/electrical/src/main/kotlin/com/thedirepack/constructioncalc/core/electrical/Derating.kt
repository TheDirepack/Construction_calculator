package com.thedirepack.constructioncalc.core.electrical

import com.thedirepack.constructioncalc.core.electrical.code.codeLength
import com.thedirepack.constructioncalc.core.electrical.code.AmbientBand
import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.electrical.code.CountAdjustmentBand
import com.thedirepack.constructioncalc.core.electrical.code.SmallConductorLimitRow
import com.thedirepack.constructioncalc.core.measure.Decimals
import com.thedirepack.constructioncalc.core.measure.Measure
import com.thedirepack.constructioncalc.core.measure.UnitSystem

/**
 * Ambient temperature correction: [CodeRule.AMBIENT_CORRECTION] (Table 310.15(B)(1)(1) in NEC 2023)
 * for use with the ampacity table, and the [CodeRule.ROOFTOP_ADDER]. Values from [Code.book].
 */
object AmbientCorrection {
    /** The table's rows, coolest first. */
    val bands: List<AmbientBand> get() = Code.book.ambientCorrection

    /**
     * Factor for [ambientC] (rounded up to the table's whole-degree bands) and the conductor's
     * temperature rating; null when the table has no value (too hot for that insulation).
     */
    fun factor(ambientC: Double, rating: TemperatureRating): Double? {
        val band = bands.firstOrNull { ceilBand(ambientC) <= it.maxC } ?: return null
        return band.of(rating)
    }

    // Table rows are whole degrees (e.g. 31–35); 35.4 °C falls in the 36–40 row.
    private fun ceilBand(c: Double) = kotlin.math.ceil(c - 1e-9)

    /**
     * [CodeRule.ROOFTOP_ADDER]: raceways or cables in direct sunlight on or above a rooftop less than
     * 19 mm (3/4 in.) above the roof get this many °C added to the outdoor temperature (33 °C in
     * NEC 2023). Exception: Type XHHW-2 conductors aren't subject to the adder.
     */
    val ROOFTOP_ADDER_C: Double get() = Code.book.values.rooftopAdderC

    /** [CodeRule.ROOFTOP_ADDER] in °F as the code prints it (a temperature difference, not converted from [ROOFTOP_ADDER_C]). */
    val ROOFTOP_ADDER_F: Double get() = Code.book.values.rooftopAdderF

    /** The adder as the code prints it in [units]: imperial `+60 °F`, metric `+33 °C`. */
    fun rooftopAdderText(units: UnitSystem = UnitSystem.IMPERIAL): String {
        val shown = if (units == UnitSystem.METRIC) ROOFTOP_ADDER_C else ROOFTOP_ADDER_F
        return "+${Decimals.trim(shown, 1)} ${Measure.TEMPERATURE.unit(units).symbol}"
    }

    /** True when [insulation] is exempt from the rooftop adder (XHHW-2 in NEC 2023). */
    fun isRooftopExempt(insulation: InsulationType?): Boolean = insulation in Code.book.values.rooftopExemptInsulation

    /** The rooftop condition as the code states it, e.g. `Less than 3/4 in. above the roof: +60 °F (310.15(B)(2)); XHHW-2 is exempt`. */
    fun rooftopConditionText(units: UnitSystem = UnitSystem.IMPERIAL): String {
        val v = Code.book.values
        val height = codeLength(v.rooftopMaxHeightIn, v.rooftopMaxHeightMm, units)
        val exempt = v.rooftopExemptInsulation.joinToString(", ") { it.code }
        return "Less than $height above the roof: ${rooftopAdderText(units)} (${CodeRule.ROOFTOP_ADDER.cite})" +
            if (exempt.isEmpty()) "" else "; $exempt is exempt"
    }

    fun effectiveAmbient(ambientC: Double, rooftopUnder3_4In: Boolean, exempt: Boolean): Double =
        if (rooftopUnder3_4In && !exempt) ambientC + ROOFTOP_ADDER_C else ambientC
}

/**
 * [CodeRule.CONDUCTOR_COUNT_ADJUSTMENT] (Table 310.15(C)(1) in NEC 2023), from [Code.book]. Count per
 * 310.15(E) (neutrals) and (F) (grounding conductors don't count).
 */
object ConductorCountAdjustment {
    /** The table's rows, fewest conductors first. Fewer than the first row's count need no adjustment. */
    val bands: List<CountAdjustmentBand> get() = Code.book.conductorCountAdjustment

    fun factor(currentCarrying: Int): Double =
        bands.firstOrNull { currentCarrying >= it.from && (it.to == null || currentCarrying <= it.to) }?.factor
            ?: 1.0
}

/**
 * [CodeRule.SMALL_CONDUCTORS] (240.4(D) in NEC 2023): maximum overcurrent protection after correction
 * and adjustment, unless 240.4(E) or (G) permits otherwise. Some sizes (18 and 16 AWG copper and 14 AWG
 * copper-clad aluminum in NEC 2023) carry extra conditions noted in [conditions]. Values from [Code.book].
 */
object SmallConductorLimit {
    // Coated copper uses copper; aluminum and copper-clad aluminum have their own entries.
    private fun row(size: ConductorSize, material: ConductorMaterial): SmallConductorLimitRow? {
        val key = if (material.isCopper) ConductorMaterial.COPPER else material
        return Code.book.smallConductorLimits[key]?.get(size)
    }

    fun maxOcpd(size: ConductorSize, material: ConductorMaterial): Int? = row(size, material)?.maxOcpdAmps

    fun conditions(size: ConductorSize, material: ConductorMaterial): String? {
        val row = row(size, material) ?: return null
        val continuous = row.maxContinuousAmps ?: return null
        val rule = row.conditionsRule ?: return null
        val metal = if (material.isCopper) "Cu" else material.shortLabel
        val listedFor = if (material.isCopper) size.label else "${size.label} $metal"
        return "${size.label} $metal: continuous load ≤ ${Decimals.trim(continuous)} A and devices listed for $listedFor (${rule.cite})."
    }
}

/**
 * [CodeRule.EGC_SIZE] (Table 250.122 in NEC 2023): minimum equipment grounding conductor by the rating
 * of the overcurrent device ahead of the circuit. Values from [Code.book].
 */
object EquipmentGroundingConductor {
    /** The table's rows: (rating or setting not exceeding, copper, aluminum or copper-clad aluminum). */
    val table: List<Triple<Int, ConductorSize, ConductorSize>>
        get() = Code.book.equipmentGroundingConductors.map { Triple(it.maxOcpdAmps, it.copper, it.aluminum) }

    /** Minimum EGC for an OCPD of [ocpdAmps]; null above the largest row. */
    fun size(ocpdAmps: Int, material: ConductorMaterial): ConductorSize? {
        val row = Code.book.equipmentGroundingConductors.firstOrNull { ocpdAmps <= it.maxOcpdAmps } ?: return null
        return if (material.isCopper) row.copper else row.aluminum
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

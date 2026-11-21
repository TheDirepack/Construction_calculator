package com.thedirepack.constructioncalc.core.electrical

import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * Coefficient of friction presets for a pull. These are design guidance, not code values: the
 * real figure depends on the cable jacket, conduit, lubricant and temperature, so the planner
 * always lets the user enter their own, and a pre-lubricated cable should use its maker's value.
 *
 * - [LUBRICATED] 0.35: the value the Southwire SIMpull workbook (9.18.2020) applies to standard
 *   (not pre-lubricated) building wire, and the usual design value for a lubricated pull.
 * - [UNLUBRICATED] 0.50: a conservative design value for a dry pull; pulling dry isn't recommended.
 * - [PRE_LUBRICATED] 0.15: the order of the values the same workbook gives for factory-lubricated
 *   building wire (0.14 to 0.16 for 8 AWG and larger); use the maker's figure for the actual cable.
 */
enum class FrictionPreset(val label: String, val coefficient: Double, val source: String) {
    LUBRICATED("Lubricated pull", 0.35, "Typical design value with pulling lubricant (Southwire SIMpull workbook value for standard wire)"),
    UNLUBRICATED("No lubricant", 0.50, "Conservative design value for a dry pull"),
    PRE_LUBRICATED("Pre-lubricated wire", 0.15, "Order of the Southwire workbook's values for factory-lubricated wire; use the maker's value"),
}

/** Where a cable's sidewall pressure limit came from. */
enum class PressureLimitSource { USER, BUILDING_WIRE_PRESET }

/**
 * Pulling limits for conductors and cables.
 *
 * ### Conductor tension (Southwire, "Maximum Pulling Tension", Edition 2, 2024, p. 1)
 * With a pulling eye or other device attached to the conductors: T = S × cmil × n, and
 * T = S × cmil × n × 0.8 when more than three conductors carry the pull. S is the maximum
 * allowable conductor stress: 0.008 lb/cmil for copper, 0.006 lb/cmil for the aluminum
 * constructions the flyer covers (3/4-hard and AA-8000). The flyer's tables cover cables rated up
 * to 46 kV. An equipment grounding conductor much smaller than the others is normally ignored.
 *
 * ### Sidewall pressure (Southwire, "Maximum Sidewall Pressure", 2021, p. 1)
 * Southwire recommends, for its building wire single conductors, 500 lb/ft for 14, 12 and 10 AWG
 * and 1,000 lb/ft for 8 AWG and larger. That's one manufacturer's guidance for its own products, so
 * it's offered as a labelled preset ([BUILDING_WIRE_PRESET_LABEL]) and a user value replaces it.
 */
object PullRatings {
    /** Maximum allowable conductor stress, lb/cmil (Southwire tension flyer p. 1). */
    fun conductorStress(material: ConductorMaterial): Double = if (material.isCopper) 0.008 else 0.006

    /** 0.8 multiplier when more than three conductors carry the pull. */
    const val MANY_CONDUCTOR_FACTOR = 0.8

    /** T = S × cmil × n (× 0.8 when n > 3), lb. */
    fun conductorTension(material: ConductorMaterial, size: ConductorSize, count: Int): Double {
        if (count <= 0) return 0.0
        val t = conductorStress(material) * size.circularMils * count
        return if (count > 3) t * MANY_CONDUCTOR_FACTOR else t
    }

    /** The flyer's table value: [conductorTension] rounded to whole pounds. */
    fun conductorTensionTable(material: ConductorMaterial, size: ConductorSize, count: Int): Int =
        conductorTension(material, size, count).roundToInt()

    const val BUILDING_WIRE_PRESET_LABEL =
        "Southwire building wire single conductors (Maximum Sidewall Pressure flyer, 2021): 500 lb/ft for 14–10 AWG, 1,000 lb/ft for 8 AWG and larger"

    /** Southwire's recommended maximum sidewall pressure for its building wire of [size], lb/ft. */
    fun buildingWirePressure(size: ConductorSize): Double =
        if (size.circularMils <= ConductorSize.AWG_10.circularMils) 500.0 else 1000.0
}

/**
 * Estimated weight of one insulated building-wire conductor, lb/ft, when no catalog value is known:
 * metal area × metal density + insulation area × compound density, × 12 in/ft.
 *
 * - Metal area is the conductor's circular mils × π/4 × 10⁻⁶ in² (NEC Chapter 9 Table 8 area column).
 * - Insulation area is NEC Chapter 9 Table 5 approximate area minus the Table 8 overall
 *   (stranded) area; Table 5 is a worst-case figure, so the estimate leans heavy.
 * - Densities: copper 0.321 lb/in³ (8.89 g/cm³), aluminum 0.0975 lb/in³ (2.70 g/cm³); PVC/nylon
 *   compounds (THHN, THW, TW, TFN) about 1.40 g/cm³ = 0.0506 lb/in³; cross-linked polyethylene
 *   (XHHW) about 0.93 g/cm³ = 0.0336 lb/in³; rubber/CPE (RHH, RHW) about 1.40 g/cm³. These are
 *   typical material densities, not product data.
 *
 * Always labelled an estimate; a catalog weight entered for the cable replaces it.
 */
object CableWeight {
    const val COPPER_LB_PER_CU_IN = 0.321
    const val ALUMINUM_LB_PER_CU_IN = 0.0975

    fun compoundDensity(insulation: InsulationType): Double = when (insulation) {
        InsulationType.XHHW, InsulationType.XHHW_2 -> 0.0336
        else -> 0.0506
    }

    fun metalAreaSqIn(size: ConductorSize): Double = size.circularMils * PI / 4.0 * 1e-6

    /** Estimated lb/ft for one conductor, or null when Table 5 has no row. */
    fun estimate(size: ConductorSize, insulation: InsulationType, material: ConductorMaterial): Double? {
        val insulated = InsulatedConductorArea.area(insulation, size) ?: return null
        val bare = BareConductorArea.area(size) ?: return null
        val metalDensity = if (material.isCopper) COPPER_LB_PER_CU_IN else ALUMINUM_LB_PER_CU_IN
        val insulationArea = (insulated - bare).coerceAtLeast(0.0)
        return 12.0 * (metalAreaSqIn(size) * metalDensity + insulationArea * compoundDensity(insulation))
    }

    /** Estimated lb/ft for a bare conductor (Table 8). */
    fun estimateBare(size: ConductorSize, material: ConductorMaterial): Double =
        12.0 * metalAreaSqIn(size) * if (material.isCopper) COPPER_LB_PER_CU_IN else ALUMINUM_LB_PER_CU_IN
}

package com.thedirepack.constructioncalc.core.electrical

import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule

/** Unit for a measured DC conductor resistance. */
enum class ResistanceUnit(val label: String, val ohmsPerUnit: Double) {
    OHM("Ω", 1.0),
    MILLIOHM("mΩ", 1e-3),
}

/**
 * Result of computing wire length from a measured DC resistance.
 *
 * @property lengthFt computed one-way conductor length, feet
 * @property lengthIn computed one-way conductor length, inches
 * @property effectiveResistanceOhms resistance of the single conductor (measured ÷ 2 for a round-trip loop)
 * @property ohmsPerKftAtTemp temperature-corrected conductor resistance, Ω/1000 ft
 * @property ohmsPerKftAtReference NEC Table 8 resistance at [Code.book]'s reference temperature (75 °C)
 * @property ambientC temperature used for the correction, °C
 * @property roundTrip true if the measurement was a round-trip loop (out and back, divided by 2)
 */
data class ResistanceLengthResult(
    val lengthFt: Double,
    val lengthIn: Double,
    val effectiveResistanceOhms: Double,
    val ohmsPerKftAtTemp: Double,
    val ohmsPerKftAtReference: Double,
    val ambientC: Double,
    val roundTrip: Boolean,
)

/**
 * Estimates conductor length from a measured DC resistance using [CodeRule.CONDUCTOR_PROPERTIES]
 * (NEC Chapter 9 Table 8, 75 °C basis), corrected to the conductor's actual temperature:
 *
 * `R(T) = R(75 °C) × [1 + α × (T − 75)]`
 * `L_ft = (R_conductor / R(T)) × 1000`
 *
 * where `α = 0.00323 /°C` for copper (uncoated or coated) and `0.00330 /°C` for aluminum / CCA,
 * read from [Code.book].
 */
object ResistanceLength {
    /** Default ambient temperature (°C) for dead-wire / reel field resistance measurements. */
    const val DEFAULT_AMBIENT_C = 20.0

    /** Temperature coefficient α (/°C) from [Code.book] for [material]. */
    fun temperatureCoefficient(material: ConductorMaterial): Double {
        val v = Code.book.values
        return if (material.isCopper) v.resistanceCoefficientCopper else v.resistanceCoefficientAluminum
    }

    /**
     * Temperature-corrected DC resistance (Ω/1000 ft) at [ambientC], or null if the temperature
     * drives the resistance factor non-positive or the conductor isn't in Table 8.
     */
    fun ohmsPerKftAt(
        size: ConductorSize,
        material: ConductorMaterial,
        stranding: Stranding = Stranding.STRANDED,
        ambientC: Double = Code.book.values.resistanceReferenceC,
    ): Double? {
        val base = ConductorResistance.ohmsPerKft(size, material, stranding) ?: return null
        val refC = Code.book.values.resistanceReferenceC
        val alpha = temperatureCoefficient(material)
        val factor = 1.0 + alpha * (ambientC - refC)
        if (factor <= 0.0 || !factor.isFinite()) return null
        return base * factor
    }

    /**
     * Computes conductor length from [measuredResistance] in [unit].
     *
     * @param roundTrip true when measuring a looped pair (out and back through two identical conductors);
     *                  divides the measured resistance by 2 to return the one-way length.
     * @return null if [measuredResistance] is non-positive/non-finite or inputs are out of range.
     */
    fun calculate(
        measuredResistance: Double,
        unit: ResistanceUnit = ResistanceUnit.OHM,
        size: ConductorSize,
        material: ConductorMaterial = ConductorMaterial.COPPER,
        stranding: Stranding = Stranding.STRANDED,
        ambientC: Double = DEFAULT_AMBIENT_C,
        roundTrip: Boolean = false,
    ): ResistanceLengthResult? {
        if (!measuredResistance.isFinite() || measuredResistance <= 0.0) return null
        if (!ambientC.isFinite()) return null
        val base = ConductorResistance.ohmsPerKft(size, material, stranding) ?: return null
        val atTemp = ohmsPerKftAt(size, material, stranding, ambientC) ?: return null
        val measuredOhms = measuredResistance * unit.ohmsPerUnit
        val effectiveOhms = if (roundTrip) measuredOhms / 2.0 else measuredOhms
        val lengthFt = (effectiveOhms / atTemp) * 1000.0
        return ResistanceLengthResult(
            lengthFt = lengthFt,
            lengthIn = lengthFt * 12.0,
            effectiveResistanceOhms = effectiveOhms,
            ohmsPerKftAtTemp = atTemp,
            ohmsPerKftAtReference = base,
            ambientC = ambientC,
            roundTrip = roundTrip,
        )
    }
}

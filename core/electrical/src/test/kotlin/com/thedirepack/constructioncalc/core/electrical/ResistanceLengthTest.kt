package com.thedirepack.constructioncalc.core.electrical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ResistanceLengthTest {

    @Test
    fun exactLengthAt75CMatchesTable8() {
        // 12 AWG Cu stranded at 75 °C = 1.98 Ω/kft → 1.98 Ω must equal exactly 1000 ft.
        val res = ResistanceLength.calculate(
            measuredResistance = 1.98,
            unit = ResistanceUnit.OHM,
            size = ConductorSize.AWG_12,
            material = ConductorMaterial.COPPER,
            stranding = Stranding.STRANDED,
            ambientC = 75.0,
            roundTrip = false,
        )!!
        assertEquals(1000.0, res.lengthFt, 1e-6)
        assertEquals(12000.0, res.lengthIn, 1e-4)
        assertEquals(1.98, res.ohmsPerKftAtTemp, 1e-9)
    }

    @Test
    fun temperatureCorrectionAt20CLowersResistanceAndIncreasesLength() {
        // At 20 °C, factor = 1 + 0.00323 * (20 - 75) = 1 - 0.17765 = 0.82235
        // 12 AWG Cu stranded R(20 °C) = 1.98 * 0.82235 = 1.628253 Ω/kft
        val res = ResistanceLength.calculate(
            measuredResistance = 1.628253,
            unit = ResistanceUnit.OHM,
            size = ConductorSize.AWG_12,
            material = ConductorMaterial.COPPER,
            stranding = Stranding.STRANDED,
            ambientC = 20.0,
            roundTrip = false,
        )!!
        assertEquals(1000.0, res.lengthFt, 1e-3)
        assertEquals(1.628253, res.ohmsPerKftAtTemp, 1e-6)
    }

    @Test
    fun roundTripDividesResistanceByTwo() {
        val oneWay = ResistanceLength.calculate(
            measuredResistance = 500.0,
            unit = ResistanceUnit.MILLIOHM,
            size = ConductorSize.AWG_10,
            material = ConductorMaterial.COPPER,
            ambientC = 20.0,
            roundTrip = false,
        )!!
        val loop = ResistanceLength.calculate(
            measuredResistance = 1000.0,
            unit = ResistanceUnit.MILLIOHM,
            size = ConductorSize.AWG_10,
            material = ConductorMaterial.COPPER,
            ambientC = 20.0,
            roundTrip = true,
        )!!
        assertEquals(oneWay.lengthFt, loop.lengthFt, 1e-9)
        assertEquals(0.5, loop.effectiveResistanceOhms, 1e-9)
    }

    @Test
    fun aluminumUsesAluminumCoefficientAndTableColumn() {
        val r75 = ConductorResistance.ohmsPerKft(ConductorSize.AWG_4, ConductorMaterial.ALUMINUM)!!
        val expectedAt20 = r75 * (1.0 + 0.00330 * (20.0 - 75.0))
        val actual = ResistanceLength.ohmsPerKftAt(ConductorSize.AWG_4, ConductorMaterial.ALUMINUM, ambientC = 20.0)!!
        assertEquals(expectedAt20, actual, 1e-9)
    }

    @Test
    fun solidVsStrandedAndInvalidInputs() {
        val solid = ResistanceLength.calculate(1.0, ResistanceUnit.OHM, ConductorSize.AWG_12, ConductorMaterial.COPPER, Stranding.SOLID, 75.0)
        val stranded = ResistanceLength.calculate(1.0, ResistanceUnit.OHM, ConductorSize.AWG_12, ConductorMaterial.COPPER, Stranding.STRANDED, 75.0)
        assertNotNull(solid)
        assertNotNull(stranded)
        // Solid has slightly lower resistance than stranded, so 1 Ω is slightly longer for solid.
        assert(solid!!.lengthFt > stranded!!.lengthFt)

        assertNull(ResistanceLength.calculate(0.0, size = ConductorSize.AWG_12))
        assertNull(ResistanceLength.calculate(-1.0, size = ConductorSize.AWG_12))
        assertNull(ResistanceLength.calculate(Double.NaN, size = ConductorSize.AWG_12))
    }
}

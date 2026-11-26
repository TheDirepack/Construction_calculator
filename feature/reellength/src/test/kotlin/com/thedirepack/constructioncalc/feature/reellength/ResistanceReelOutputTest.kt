package com.thedirepack.constructioncalc.feature.reellength

import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConductorResistance
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.ResistanceUnit
import com.thedirepack.constructioncalc.core.electrical.Stranding
import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.core.ui.components.NoticeLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResistanceReelOutputTest {

    private fun lengthFt(inputs: ReelLengthInputs, settings: AppSettings = AppSettings()): Double =
        reelLengthOutput(inputs, settings).results.first { it.key == "length" }.rational!!.toDouble() / 12.0

    /** Table 8 Ω/1000 ft for [size] corrected to [tempC] with the edition's coefficient, from [Code.book]. */
    private fun ohmsPerKft(size: ConductorSize, material: ConductorMaterial, tempC: Double): Double {
        val v = Code.book.values
        val alpha = if (material.isCopper) v.resistanceCoefficientCopper else v.resistanceCoefficientAluminum
        return ConductorResistance.ohmsPerKft(size, material)!! * (1 + alpha * (tempC - v.resistanceReferenceC))
    }

    @Test fun resistanceIsTheDefaultMethodAndSurvivesSave() {
        assertEquals(ReelMethod.RESISTANCE, ReelLengthInputs().method)
        assertEquals("20", ReelLengthInputs().conductorTempC)
        val custom = ReelLengthInputs(
            wireSize = ConductorSize.AWG_4.label, wireMaterial = ConductorMaterial.ALUMINUM, measuredResistance = "0.25",
            resistanceUnit = ResistanceUnit.OHM, conductorTempC = "30", measuredLoop = true,
        )
        assertEquals(custom, ToolInputs.decode<ReelLengthInputs>(ToolInputs.encode(custom)))
    }

    @Test fun lengthIsMeasuredResistanceOverCorrectedTable8Resistance() {
        val inputs = ReelLengthInputs(wireSize = ConductorSize.AWG_12.label, measuredResistance = "1.5")
        val expected = 1.5 / ohmsPerKft(ConductorSize.AWG_12, ConductorMaterial.COPPER, 20.0) * 1000
        assertEquals(expected, lengthFt(inputs), 0.01)
    }

    @Test fun atTheTableTemperatureTheTableValueIsExactlyOneThousandFeet() {
        val refC = Code.book.values.resistanceReferenceC
        val perKft = ConductorResistance.ohmsPerKft(ConductorSize.AWG_10, ConductorMaterial.COPPER)!!
        val inputs = ReelLengthInputs(wireSize = ConductorSize.AWG_10.label, measuredResistance = perKft.toString(), conductorTempC = refC.toString())
        assertEquals(1000.0, lengthFt(inputs), 0.01)
    }

    @Test fun warmerWireReadsHigherSoTheSameReadingIsShorter() {
        val cold = ReelLengthInputs(measuredResistance = "1", conductorTempC = "10")
        val warm = cold.copy(conductorTempC = "35")
        assertTrue(lengthFt(warm) < lengthFt(cold))
    }

    @Test fun loopReadingHalvesTheLength() {
        val single = ReelLengthInputs(measuredResistance = "2")
        val loop = single.copy(measuredLoop = true)
        assertEquals(lengthFt(single) / 2, lengthFt(loop), 1e-3)
        assertTrue(reelLengthOutput(loop, AppSettings()).notices.any { it.text.contains("halved") })
    }

    @Test fun milliohmsAluminumAndSolidUseTheirOwnValues() {
        val ohm = ReelLengthInputs(measuredResistance = "0.5")
        assertEquals(lengthFt(ohm), lengthFt(ohm.copy(measuredResistance = "500", resistanceUnit = ResistanceUnit.MILLIOHM)), 1e-3)

        val al = ReelLengthInputs(wireSize = ConductorSize.AWG_2.label, wireMaterial = ConductorMaterial.ALUMINUM, measuredResistance = "0.2")
        assertEquals(0.2 / ohmsPerKft(ConductorSize.AWG_2, ConductorMaterial.ALUMINUM, 20.0) * 1000, lengthFt(al), 0.01)

        val solid = ohm.copy(wireStranding = Stranding.SOLID)
        assertTrue(lengthFt(solid) > lengthFt(ohm)) // solid has slightly less resistance per foot
    }

    @Test fun lengthFollowsTheUnitSetting() {
        val inputs = ReelLengthInputs(measuredResistance = "1.5")
        val metric = reelLengthOutput(inputs, AppSettings(unitSystem = UnitSystem.METRIC))
        assertTrue(metric.results.first { it.key == "length_feet" }.text.endsWith(" m"))
        assertTrue(reelLengthOutput(inputs, AppSettings()).results.first { it.key == "length_feet" }.text.endsWith(" ft"))
    }

    @Test fun emptyAndBadInputsExplainInsteadOfCalculating() {
        val empty = reelLengthOutput(ReelLengthInputs(), AppSettings())
        assertTrue(empty.results.isEmpty())
        assertTrue(empty.notices.any { it.text.startsWith("How to measure") })
        assertTrue(empty.notices.none { it.level == NoticeLevel.ERROR })

        val negative = reelLengthOutput(ReelLengthInputs(measuredResistance = "-1"), AppSettings())
        assertTrue(negative.results.isEmpty())
        assertTrue(negative.notices.any { it.level == NoticeLevel.ERROR })

        val badTemp = reelLengthOutput(ReelLengthInputs(measuredResistance = "1", conductorTempC = "abc"), AppSettings())
        assertTrue(badTemp.notices.any { it.level == NoticeLevel.ERROR })
    }

    @Test fun cableNeededChecksTheReelInEitherMethod() {
        val base = ReelLengthInputs(measuredResistance = "1.5")
        val have = lengthFt(base)
        val enough = reelLengthOutput(base.copy(cableNeeded = Quantity.feet(Rational.of((have - 50).toLong())).stored()), AppSettings())
        assertTrue(enough.notices.any { it.text.startsWith("Enough for the") })
        val short = reelLengthOutput(base.copy(cableNeeded = Quantity.feet(Rational.of((have + 50).toLong())).stored()), AppSettings())
        assertTrue(short.notices.any { it.level == NoticeLevel.WARNING && it.text.startsWith("Short by") })
    }
}

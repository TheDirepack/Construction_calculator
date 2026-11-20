package com.thedirepack.constructioncalc.feature.vfdsizing.domain

import com.thedirepack.constructioncalc.core.electrical.AcPhases
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.TemperatureRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VfdSizingTest {
    private fun calc(request: VfdRequest) = VfdSizing.calculate(request)!!

    @Test fun tenHp460VoltThreePhase() {
        val r = calc(VfdRequest(hp = 10.0, volts = 460))
        assertEquals(14.0, r.tableFlc, 0.0)
        assertEquals(14.0, r.minDriveOutputAmps, 0.0)
        assertEquals(OutputBasis.TABLE_FLC, r.outputBasis)
        assertNull(r.singlePhaseDriveGuidanceAmps)
        assertNull(r.overloadMaxAmps)
        assertTrue(r.inputBasisIsEstimate)
        assertEquals(17.5, r.inputAmpacityRequired, 1e-9)
        assertEquals(ConductorSize.AWG_14, r.inputConductor!!.size) // 20 A at 75 °C
        assertEquals(ConductorSize.AWG_14, r.outputConductor!!.size)
        assertEquals(16.1, r.disconnectMinAmps, 1e-9)
        assertEquals(35.0, r.breakerMaxCalcAmps, 1e-9)
        assertEquals(35, r.breakerMax)
        assertEquals(24.5, r.fuseMaxCalcAmps, 1e-9)
        assertEquals(25, r.fuseMax)
    }

    @Test fun sixtyDegreeColumnNeedsLargerConductor() {
        val r = calc(VfdRequest(hp = 10.0, volts = 460, termination = TemperatureRating.C60))
        assertEquals(ConductorSize.AWG_12, r.inputConductor!!.size) // 14 AWG is only 15 A at 60 °C
    }

    @Test fun twentyFiveHp460RoundsProtectionUp() {
        val r = calc(VfdRequest(hp = 25.0, volts = 460))
        assertEquals(34.0, r.tableFlc, 0.0)
        assertEquals(85.0, r.breakerMaxCalcAmps, 1e-9)
        assertEquals(90, r.breakerMax)
        assertEquals(59.5, r.fuseMaxCalcAmps, 1e-9)
        assertEquals(60, r.fuseMax)
        assertEquals(42.5, r.outputAmpacityRequired, 1e-9)
        assertEquals(ConductorSize.AWG_8, r.outputConductor!!.size) // 50 A at 75 °C
    }

    @Test fun tenHp230Volt() {
        val r = calc(VfdRequest(hp = 10.0, volts = 230))
        assertEquals(28.0, r.tableFlc, 0.0)
        assertEquals(35.0, r.inputAmpacityRequired, 1e-9)
        assertEquals(ConductorSize.AWG_10, r.inputConductor!!.size) // 35 A at 75 °C
        assertEquals(70, r.breakerMax)
        assertEquals(49.0, r.fuseMaxCalcAmps, 1e-9)
        assertEquals(50, r.fuseMax)
    }

    @Test fun nameplateFlaDrivesTheDriveRatingAndOverload() {
        val r = calc(VfdRequest(hp = 10.0, volts = 460, nameplateFla = 12.8))
        assertEquals(12.8, r.minDriveOutputAmps, 0.0)
        assertEquals(OutputBasis.NAMEPLATE_FLA, r.outputBasis)
        assertEquals(14.0, r.tableFlc, 0.0) // conductors and protection still use the table
        assertEquals(16.0, r.overloadMaxAmps!!, 1e-9)
        assertEquals(100.0 * 12.8 / 14.0, r.nameplatePercentOfTable!!, 1e-9)
        assertEquals(35, r.breakerMax)
    }

    @Test fun knownDriveInputCurrentReplacesTheApproximation() {
        val r = calc(VfdRequest(hp = 10.0, volts = 460, driveInputAmps = 20.0))
        assertEquals(false, r.inputBasisIsEstimate)
        assertEquals(25.0, r.inputAmpacityRequired, 1e-9)
        assertEquals(ConductorSize.AWG_12, r.inputConductor!!.size)
        assertEquals(23.0, r.disconnectMinAmps, 1e-9)
        assertEquals(17.5, r.outputAmpacityRequired, 1e-9) // output side stays on FLC
    }

    @Test fun singlePhaseInputGivesDriveGuidance() {
        val r = calc(VfdRequest(hp = 3.0, volts = 230, supplyPhase = AcPhases.SINGLE, nameplateFla = 9.0))
        assertEquals(18.0, r.singlePhaseDriveGuidanceAmps!!, 1e-9)
        val noPlate = calc(VfdRequest(hp = 3.0, volts = 230, supplyPhase = AcPhases.SINGLE))
        assertEquals(19.2, noPlate.singlePhaseDriveGuidanceAmps!!, 1e-9)
    }

    @Test fun aluminumUsesItsOwnColumn() {
        val r = calc(VfdRequest(hp = 10.0, volts = 460, material = ConductorMaterial.ALUMINUM))
        assertEquals(ConductorSize.AWG_12, r.inputConductor!!.size) // 20 A at 75 °C; no 14 AWG aluminum
    }

    @Test fun largeMotorsUseParallelSets() {
        val r = calc(VfdRequest(hp = 200.0, volts = 200)) // FLC 552 A, 690 A required
        assertEquals(690.0, r.inputAmpacityRequired, 1e-9)
        val c = r.inputConductor!!
        assertEquals(2, c.sets)
        assertEquals(ConductorSize.KCMIL_500, c.size) // 345 A per set needs 380 A (400 kcmil is 335 A)
        assertEquals("2 × 500 kcmil Cu per phase", c.text(ConductorMaterial.COPPER))
        assertEquals(1380, r.breakerMaxCalcAmps.toInt())
        assertEquals(1600, r.breakerMax)
    }

    @Test fun singleLargeConductorBeforeParallel() {
        val r = calc(VfdRequest(hp = 100.0, volts = 460)) // 124 A, 155 A required
        assertEquals(ConductorSize.AWG_2_0, r.inputConductor!!.size) // 175 A; 1/0 is 150 A
        assertEquals(1, r.inputConductor.sets)
        assertEquals("2/0 AWG Cu", r.inputConductor.text(ConductorMaterial.COPPER))
    }

    @Test fun unavailableCombinationsAreProblems() {
        assertNotNull(VfdSizing.problems(VfdRequest(hp = 250.0, volts = 230)).firstOrNull())
        assertNull(VfdSizing.calculate(VfdRequest(hp = 250.0, volts = 230)))
        assertNull(VfdSizing.calculate(VfdRequest(hp = 10.0, volts = 480)))
        assertTrue(VfdSizing.problems(VfdRequest(hp = 10.0, volts = 460, nameplateFla = 0.0)).isNotEmpty())
        assertTrue(VfdSizing.problems(VfdRequest(hp = 10.0, volts = 460)).isEmpty())
    }

    @Test fun kilowattsConvertToStandardHorsepower() {
        assertEquals(10.0, VfdSizing.horsepowerForKw(7.5, 460)!!, 0.0)
        assertEquals(15.0, VfdSizing.horsepowerForKw(11.0, 460)!!, 0.0)
        assertEquals(20.0, VfdSizing.horsepowerForKw(15.0, 460)!!, 0.0)
        assertEquals(30.0, VfdSizing.horsepowerForKw(22.0, 460)!!, 0.0)
        assertEquals(0.5, VfdSizing.horsepowerForKw(0.37, 460)!!, 0.0)
        assertNull(VfdSizing.horsepowerForKw(0.0, 460))
        assertNull(VfdSizing.horsepowerForKw(2000.0, 460))
        assertNull(VfdSizing.horsepowerForKw(200.0, 230)) // above the 200 HP table limit at 230 V
    }

    @Test fun nearestHorsepowerSnapsToTheTable() {
        assertEquals(200.0, VfdSizing.nearestHorsepower(300.0, 230), 0.0)
        assertEquals(10.0, VfdSizing.nearestHorsepower(10.0, 230), 0.0)
        assertEquals(15.0, VfdSizing.nearestHorsepower(12.0, 460), 0.0)
    }

    @Test fun singlePhaseInputEstimateIsTwiceTheMotorFlc() {
        val three = calc(VfdRequest(hp = 3.0, volts = 230))
        val single = calc(VfdRequest(hp = 3.0, volts = 230, supplyPhase = AcPhases.SINGLE))
        assertEquals(9.6, three.inputBasisAmps, 1e-9)
        assertEquals(19.2, single.inputBasisAmps, 1e-9)
        assertTrue(single.inputAmpacityRequired > three.inputAmpacityRequired)
        // A drive input rating always wins over the estimate.
        val rated = calc(VfdRequest(hp = 3.0, volts = 230, supplyPhase = AcPhases.SINGLE, driveInputAmps = 15.0))
        assertEquals(15.0, rated.inputBasisAmps, 1e-9)
    }

    @Test fun conductorTextNamesEveryMaterial() {
        val choice = ConductorChoice(com.thedirepack.constructioncalc.core.electrical.ConductorSize.AWG_10, 1, 35)
        assertEquals("10 AWG Cu coated", choice.text(com.thedirepack.constructioncalc.core.electrical.ConductorMaterial.COATED_COPPER))
        assertEquals("10 AWG CCA", choice.text(com.thedirepack.constructioncalc.core.electrical.ConductorMaterial.COPPER_CLAD_ALUMINUM))
    }
}

package com.thedirepack.constructioncalc.core.electrical.code

import com.thedirepack.constructioncalc.core.electrical.AcPhases
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.InsulationType
import com.thedirepack.constructioncalc.core.electrical.MotorProtectiveDevice
import com.thedirepack.constructioncalc.core.electrical.MotorType
import com.thedirepack.constructioncalc.core.electrical.Stranding
import com.thedirepack.constructioncalc.core.electrical.code.nec2023.Nec2023
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The edition source: the app reads NEC 2023, every table is there and complete, and the single
 * values are the ones printed in the 2023 book. Table cells are pinned by the per-table tests
 * (AmpacityTest, ConduitTableTest…); this checks the shape and the scalar values.
 */
class ElectricalCodeTest {
    private val code: ElectricalCode = Code.book

    @Test
    fun theAppReadsNec2023() {
        assertSame(Nec2023, Code.book)
        assertEquals(CodeEdition.NEC_2023, Code.edition)
    }

    @Test
    fun everyCitationIsPresent() {
        assertEquals(emptyList<CodeRule>(), Nec2023.missing)
        CodeRule.entries.forEach { assertTrue("$it has an empty citation", it.cite.isNotBlank()) }
    }

    @Test
    fun everyTableHasRows() {
        val tables: Map<String, Collection<*>> = mapOf(
            "ampacity" to code.ampacity.values.flatMap { it.values },
            "ambientCorrection" to code.ambientCorrection,
            "conductorCountAdjustment" to code.conductorCountAdjustment,
            "insulationRatings" to code.insulationRatings.values,
            "insulatedConductorDimensions" to code.insulatedConductorDimensions.values.flatMap { it.values },
            "bareConductorDimensions" to code.bareConductorDimensions.values.flatMap { it.values },
            "conductorResistance" to code.conductorResistance.values,
            "acImpedance" to code.acImpedance.values.flatMap { it.values },
            "standardOcpdRatings" to code.standardOcpdRatings,
            "fuseOnlyRatings" to code.fuseOnlyRatings,
            "smallConductorLimits" to code.smallConductorLimits.values.flatMap { it.values },
            "equipmentGroundingConductors" to code.equipmentGroundingConductors,
            "conduitDimensions" to code.conduitDimensions.values.flatten(),
            "bendRadius" to code.bendRadius.values,
            "raceways" to code.raceways.values,
            "motorFullLoadCurrent" to code.motorFullLoadCurrent.values.flatten(),
            "motorFullLoadVoltages" to code.motorFullLoadVoltages.values.flatten(),
            "motorBranchProtection" to code.motorBranchProtection.values.flatMap { it.values },
            "workingSpace" to code.workingSpace,
        )
        tables.forEach { (name, rows) -> assertTrue("$name is empty", rows.isNotEmpty()) }
    }

    @Test
    fun tablesCoverTheAppVocabulary() {
        listOf(ConductorMaterial.COPPER, ConductorMaterial.ALUMINUM).forEach {
            assertTrue("ampacity $it", code.ampacity.getValue(it).isNotEmpty())
            assertTrue("Table 9 $it", code.acImpedance.getValue(it).isNotEmpty())
        }
        listOf(ConductorMaterial.COPPER, ConductorMaterial.ALUMINUM, ConductorMaterial.COPPER_CLAD_ALUMINUM).forEach {
            assertTrue("240.4(D) $it", code.smallConductorLimits.getValue(it).isNotEmpty())
        }
        InsulationType.entries.forEach { assertTrue("Table 5 $it", code.insulatedConductorDimensions.getValue(it).isNotEmpty()) }
        assertEquals(ConductorSize.entries.toSet(), code.bareConductorDimensions.getValue(Stranding.STRANDED).keys)
        assertEquals(ConductorSize.entries.take(6).toSet(), code.bareConductorDimensions.getValue(Stranding.SOLID).keys)
        assertEquals(ConductorSize.entries.toSet(), code.conductorResistance.keys)
        ConduitType.entries.forEach {
            assertTrue("Table 4 $it", code.conduitDimensions.getValue(it).isNotEmpty())
            assertTrue("article $it", code.raceways.getValue(it).article.startsWith("Article"))
            assertTrue("bend section $it", code.raceways.getValue(it).bendLimitSection.endsWith("(B)"))
        }
        AcPhases.entries.forEach {
            assertTrue("motor FLC $it", code.motorFullLoadCurrent.getValue(it).isNotEmpty())
            assertTrue("motor volts $it", code.motorFullLoadVoltages.getValue(it).isNotEmpty())
        }
        MotorType.entries.forEach { motor ->
            assertEquals("430.52 $motor", MotorProtectiveDevice.entries.toSet(), code.motorBranchProtection.getValue(motor).keys)
        }
        code.workingSpace.forEach { assertEquals(3, it.depthIn.size); assertEquals(3, it.depthMm.size) }
    }

    @Test
    fun tablesAreInOrder() {
        assertTrue(code.standardOcpdRatings.zipWithNext().all { (a, b) -> a < b })
        assertTrue(code.ambientCorrection.zipWithNext().all { (a, b) -> a.maxC < b.maxC })
        assertTrue(code.conductorCountAdjustment.zipWithNext().all { (a, b) -> a.to != null && a.to!! + 1 == b.from })
        assertTrue(code.equipmentGroundingConductors.zipWithNext().all { (a, b) -> a.maxOcpdAmps < b.maxOcpdAmps })
        code.conduitDimensions.values.forEach { rows ->
            assertTrue(rows.zipWithNext().all { (a, b) -> a.tradeSize < b.tradeSize })
        }
    }

    /** The printed NEC 2023 value of every single value. */
    @Test
    fun singleValuesMatchThe2023Book() {
        val v = code.values
        assertEquals(30.0, v.ambientBasisC, 0.0)
        assertEquals(33.0, v.rooftopAdderC, 0.0)
        assertEquals(1.25, v.continuousLoadFactor, 0.0)
        assertEquals(800, v.nextSizeUpMaxAmps)
        assertEquals(ConductorSize.AWG_14, v.minimumSizeCopper)
        assertEquals(ConductorSize.AWG_12, v.minimumSizeAluminum)
        assertEquals(ConductorSize.AWG_1_0, v.parallelMinimumSize)
        assertEquals(100, v.terminationLowColumnMaxAmps)
        assertEquals(ConductorSize.AWG_1, v.terminationLowColumnMaxSize)

        assertEquals(53, v.fillOneConductorPercent)
        assertEquals(31, v.fillTwoConductorsPercent)
        assertEquals(40, v.fillOverTwoConductorsPercent)
        assertEquals(60, v.fillNipplePercent)
        assertEquals(24.0, v.nippleMaxLengthIn, 0.0)
        assertEquals(0.8, v.note7RoundUpFraction, 0.0)
        assertEquals(2.8, v.jamRatioLow, 0.0)
        assertEquals(3.2, v.jamRatioHigh, 0.0)
        assertEquals(360.0, v.maxBendDegreesBetweenPullPoints, 0.0)

        assertEquals(75.0, v.resistanceReferenceC, 0.0)
        assertEquals(0.00323, v.resistanceCoefficientCopper, 0.0)
        assertEquals(0.00330, v.resistanceCoefficientAluminum, 0.0)
        assertEquals(0.85, v.acImpedanceTablePowerFactor, 0.0)

        assertEquals(3.0, v.voltageDropBranchPercent, 0.0)
        assertEquals(3.0, v.voltageDropFeederPercent, 0.0)
        assertEquals(5.0, v.voltageDropTotalPercent, 0.0)

        assertEquals(1.25, v.motorConductorFactor, 0.0)
        assertEquals(1.15, v.motorDisconnectFactor, 0.0)
        assertEquals(1.25, v.motorOverloadFactor, 0.0)
        assertEquals(1.15, v.motorOverloadFactorOther, 0.0)
        assertEquals(1.15, v.motorOverloadServiceFactor, 0.0)
        assertEquals(40.0, v.motorOverloadMaxRiseC, 0.0)
        assertEquals(1.25, v.driveInputConductorFactor, 0.0)
        assertEquals(1.25, v.driveOutputConductorFactor, 0.0)
        assertEquals(1.15, v.driveDisconnectFactor, 0.0)

        assertEquals(30, v.workingSpaceMinWidthIn)
        assertEquals(78, v.workingSpaceMinHeightIn)
    }

    @Test
    fun newCitationsAreThe2023Numbers() {
        assertEquals("210.19(A)(1)", CodeRule.BRANCH_CONDUCTOR_CONTINUOUS.cite)
        assertEquals("210.19(A)(2)", CodeRule.BRANCH_CONDUCTOR_ADJUSTED.cite)
        assertEquals("215.2(A)(1)", CodeRule.FEEDER_CONDUCTOR_CONTINUOUS.cite)
        assertEquals("310.3(A)", CodeRule.MINIMUM_CONDUCTOR_SIZE.cite)
        assertEquals("430.128", CodeRule.DRIVE_DISCONNECT_RATING.cite)
        assertEquals("110.26(A)(2)", CodeRule.WORKING_SPACE_WIDTH.cite)
        assertEquals("110.26(A)(3)", CodeRule.WORKING_SPACE_HEIGHT.cite)
    }
}

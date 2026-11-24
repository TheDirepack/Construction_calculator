package com.thedirepack.constructioncalc.feature.voltagedrop.domain

import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.electrical.AcPhases
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.Stranding
import com.thedirepack.constructioncalc.core.electrical.Table9Conduit
import com.thedirepack.constructioncalc.core.electrical.TemperatureRating
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.feature.voltagedrop.VoltageDropInputs
import com.thedirepack.constructioncalc.feature.voltagedrop.parse
import com.thedirepack.constructioncalc.feature.voltagedrop.voltageDropOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoltageDropAmpacityTest {
    private val baseAc = VoltageDropRequest(
        system = AcPhases.SINGLE,
        volts = 120.0,
        material = ConductorMaterial.COPPER,
        amps = 25.0,
        lengthFeet = 10.0,
        size = ConductorSize.AWG_12,
        method = Method.AC_TABLE_9,
        conduit = Table9Conduit.PVC,
    )

    @Test fun ampacityGovernsWhenDropAllowsSmallerWire() {
        val dropMin = VoltageDrop.smallestSize(baseAc)
        assertEquals(ConductorSize.AWG_14, dropMin)

        val result = VoltageDrop.checkAmpacity(
            request = baseAc,
            ampacity = AmpacityCheckRequest(insulation = TemperatureRating.C75, termination = TemperatureRating.C75),
            dropMinimum = dropMin,
        )
        assertEquals(ConductorSize.AWG_12, result.minimumSize)
        assertEquals(25.0, result.minimumUsableAmpacity!!, 1e-9)
        assertEquals(25.0, result.selectedUsableAmpacity!!, 1e-9)
        assertTrue(result.selectedMeetsAmpacity)
        assertNull(result.selectedFailure)
        assertEquals(ConductorSize.AWG_12, result.governingSize)
        assertEquals(GoverningConstraint.AMPACITY, result.governing)

        val tooSmall = VoltageDrop.checkAmpacity(
            request = baseAc.copy(size = ConductorSize.AWG_14),
            ampacity = AmpacityCheckRequest(),
            dropMinimum = dropMin,
        )
        assertFalse(tooSmall.selectedMeetsAmpacity)
        assertEquals(20.0, tooSmall.selectedUsableAmpacity!!, 1e-9)
        assertTrue(tooSmall.selectedFailure!!.contains(CodeRule.TERMINATION_TEMPERATURE.cite))
    }

    @Test fun voltageDropGovernsOnLongRun() {
        val longRun = VoltageDropRequest(
            system = AcPhases.SINGLE,
            volts = 120.0,
            material = ConductorMaterial.COPPER,
            amps = 16.0,
            lengthFeet = 100.0,
            size = ConductorSize.AWG_12,
            method = Method.DC_TABLE_8,
            stranding = Stranding.SOLID,
        )
        val dropMin = VoltageDrop.smallestSize(longRun)
        assertEquals(ConductorSize.AWG_8, dropMin)

        val result = VoltageDrop.checkAmpacity(
            request = longRun,
            ampacity = AmpacityCheckRequest(ocpdAmps = 20),
            dropMinimum = dropMin,
        )
        assertEquals(ConductorSize.AWG_12, result.minimumSize)
        assertEquals(ConductorSize.AWG_8, result.governingSize)
        assertEquals(GoverningConstraint.VOLTAGE_DROP, result.governing)
    }

    @Test fun exactTieReportsBoth() {
        // 25 A, 25 ft, 120 V 1-ph DC stranded: 14 AWG = 3.925 V (>3.6), 12 AWG = 2.475 V (<=3.6).
        // Ampacity 75 °C at 25 A also selects 12 AWG.
        val tieReq = VoltageDropRequest(
            system = AcPhases.SINGLE,
            volts = 120.0,
            material = ConductorMaterial.COPPER,
            amps = 25.0,
            lengthFeet = 25.0,
            size = ConductorSize.AWG_12,
            method = Method.DC_TABLE_8,
            stranding = Stranding.STRANDED,
        )
        val dropMin = VoltageDrop.smallestSize(tieReq)
        assertEquals(ConductorSize.AWG_12, dropMin)

        val result = VoltageDrop.checkAmpacity(tieReq, AmpacityCheckRequest(), dropMin)
        assertEquals(ConductorSize.AWG_12, result.minimumSize)
        assertEquals(ConductorSize.AWG_12, result.governingSize)
        assertEquals(GoverningConstraint.BOTH, result.governing)
    }

    @Test fun continuousLoadApplies125PercentAtTerminals() {
        // 80 A continuous -> 100 A required at 75 °C terminals: 4 AWG (85 A) fails, 3 AWG (100 A) passes.
        val req = baseAc.copy(volts = 240.0, amps = 80.0, size = ConductorSize.AWG_4)
        val amp = AmpacityCheckRequest(
            insulation = TemperatureRating.C90,
            termination = TemperatureRating.C75,
            continuousLoad = true,
        )
        val dropMin = VoltageDrop.smallestSize(req)
        val nonCont = VoltageDrop.checkAmpacity(req, amp.copy(continuousLoad = false), dropMin)
        assertEquals(ConductorSize.AWG_4, nonCont.minimumSize)
        assertEquals(80.0, nonCont.requiredTerminalAmps, 1e-9)
        assertTrue(nonCont.selectedMeetsAmpacity)

        val cont = VoltageDrop.checkAmpacity(req, amp, dropMin)
        assertEquals(ConductorSize.AWG_3, cont.minimumSize)
        assertEquals(100.0, cont.requiredTerminalAmps, 1e-9)
        assertEquals(80.0, cont.requiredLoadAmps, 1e-9)
        assertFalse(cont.selectedMeetsAmpacity)
        assertTrue(cont.selectedFailure!!.contains(CodeRule.BRANCH_CONDUCTOR_CONTINUOUS.cite))
        assertTrue(cont.selectedFailure.contains(CodeRule.TERMINATION_TEMPERATURE.cite))
    }

    @Test fun ambientAndCurrentCarryingDeratingAndTooHot() {
        // 90 °C Cu at 50 °C (×0.82) and 7 CCC (×0.70) = ×0.574.
        // 10 AWG = 40 × 0.574 = 22.96 A (< 24 A); 8 AWG = 55 × 0.574 = 31.57 A (>= 24 A).
        val req = baseAc.copy(amps = 24.0, size = ConductorSize.AWG_10)
        val derated = VoltageDrop.checkAmpacity(
            request = req,
            ampacity = AmpacityCheckRequest(
                insulation = TemperatureRating.C90,
                termination = TemperatureRating.C75,
                ambientC = 50.0,
                currentCarrying = 7,
            ),
            dropMinimum = VoltageDrop.smallestSize(req),
        )
        assertEquals(0.82, derated.ambientFactor!!, 1e-9)
        assertEquals(0.70, derated.adjustmentFactor, 1e-9)
        assertEquals(ConductorSize.AWG_8, derated.minimumSize)
        assertEquals(31.57, derated.minimumUsableAmpacity!!, 1e-6)
        assertFalse(derated.selectedMeetsAmpacity)
        assertTrue(derated.selectedFailure!!.contains(CodeRule.AMPACITY.cite))

        // 75 °C insulation has no factor at 75 °C ambient -> UNSOLVED citing ambient table.
        val tooHot = VoltageDrop.checkAmpacity(
            request = req,
            ampacity = AmpacityCheckRequest(insulation = TemperatureRating.C75, ambientC = 75.0),
            dropMinimum = VoltageDrop.smallestSize(req),
        )
        assertNull(tooHot.ambientFactor)
        assertNull(tooHot.minimumSize)
        assertNull(tooHot.governingSize)
        assertEquals(GoverningConstraint.UNSOLVED, tooHot.governing)
        assertFalse(tooHot.selectedMeetsAmpacity)
        assertTrue(tooHot.notes.any { it.contains(CodeRule.AMBIENT_CORRECTION.cite) })
    }

    @Test fun ocpdSmallConductorAndNextSizeUpProtection() {
        // 12 AWG Cu is limited to 20 A OCPD per 240.4(D) even though 75 °C ampacity is 25 A.
        val smallReq = baseAc.copy(amps = 18.0, size = ConductorSize.AWG_12)
        val smallRes = VoltageDrop.checkAmpacity(
            request = smallReq,
            ampacity = AmpacityCheckRequest(ocpdAmps = 25),
            dropMinimum = VoltageDrop.smallestSize(smallReq),
        )
        assertEquals(ConductorSize.AWG_10, smallRes.minimumSize)
        assertFalse(smallRes.selectedMeetsAmpacity)
        assertTrue(smallRes.selectedFailure!!.contains(CodeRule.SMALL_CONDUCTORS.cite))

        // 6 AWG Cu has 65 A usable ampacity at 75 °C: 240.4(B) allows next standard rating 70 A, not 80 A.
        val nextUpReq = baseAc.copy(volts = 240.0, amps = 60.0, size = ConductorSize.AWG_6)
        val ok70 = VoltageDrop.checkAmpacity(
            request = nextUpReq,
            ampacity = AmpacityCheckRequest(ocpdAmps = 70),
            dropMinimum = VoltageDrop.smallestSize(nextUpReq),
        )
        assertEquals(ConductorSize.AWG_6, ok70.minimumSize)
        assertTrue(ok70.selectedMeetsAmpacity)
        assertTrue(ok70.notes.any { it.contains(CodeRule.NEXT_SIZE_UP.cite) })

        val fail80 = VoltageDrop.checkAmpacity(
            request = nextUpReq,
            ampacity = AmpacityCheckRequest(ocpdAmps = 80),
            dropMinimum = VoltageDrop.smallestSize(nextUpReq),
        )
        assertEquals(ConductorSize.AWG_4, fail80.minimumSize)
        assertFalse(fail80.selectedMeetsAmpacity)
        assertTrue(fail80.selectedFailure!!.contains(CodeRule.CONDUCTOR_PROTECTION.cite))
    }

    @Test fun parallelSetsEnforce1_0MinimumAndScaleAmpacity() {
        // 250 A across 2 sets: 1 AWG (260 A) fails 310.10(G) 1/0 minimum; 1/0 AWG (300 A) passes.
        val req = VoltageDropRequest(
            system = AcPhases.THREE,
            volts = 480.0,
            material = ConductorMaterial.COPPER,
            amps = 250.0,
            lengthFeet = 50.0,
            size = ConductorSize.AWG_1,
            parallelSets = 2,
            method = Method.AC_TABLE_9,
        )
        val res = VoltageDrop.checkAmpacity(req, AmpacityCheckRequest(), VoltageDrop.smallestSize(req))
        assertEquals(ConductorSize.AWG_1_0, res.minimumSize)
        assertEquals(300.0, res.minimumUsableAmpacity!!, 1e-9)
        assertFalse(res.selectedMeetsAmpacity)
        assertTrue(res.selectedFailure!!.contains(CodeRule.PARALLEL_CONDUCTORS.cite))
    }

    @Test fun minimumConductorSizeRuleIsEnforced() {
        val smallCu = VoltageDropRequest(
            system = AcPhases.SINGLE,
            volts = 120.0,
            material = ConductorMaterial.COPPER,
            amps = 5.0,
            lengthFeet = 10.0,
            size = ConductorSize.AWG_16,
            method = Method.DC_TABLE_8,
        )
        val res = VoltageDrop.checkAmpacity(
            smallCu,
            AmpacityCheckRequest(insulation = TemperatureRating.C90, termination = TemperatureRating.C90),
            VoltageDrop.smallestSize(smallCu),
        )
        assertEquals(ConductorSize.AWG_14, res.minimumSize)
        assertFalse(res.selectedMeetsAmpacity)
        assertTrue(res.selectedFailure!!.contains(CodeRule.MINIMUM_CONDUCTOR_SIZE.cite))
    }

    @Test fun unsolvedWhenDropExceedsTable() {
        val hugeDrop = baseAc.copy(amps = 100.0, lengthFeet = 5000.0, size = ConductorSize.AWG_3)
        val dropMin = VoltageDrop.smallestSize(hugeDrop)
        assertNull(dropMin)

        val res = VoltageDrop.checkAmpacity(hugeDrop, AmpacityCheckRequest(), dropMin)
        assertEquals(ConductorSize.AWG_3, res.minimumSize)
        assertNull(res.governingSize)
        assertEquals(GoverningConstraint.UNSOLVED, res.governing)
    }

    @Test fun dropOnlyModeIsUnchangedWhenAmpacityDisabled() {
        val dropOnlyInputs = VoltageDropInputs(
            amps = "16",
            length = Quantity.feet(Rational.of(100)).stored(),
            method = Method.DC_TABLE_8,
            stranding = Stranding.SOLID,
            checkAmpacity = false,
            ambientC = "not-a-number",
            currentCarrying = "0",
            ocpdAmps = "bad",
        )
        val parsed = dropOnlyInputs.parse()
        assertNotNull(parsed.request)
        assertNull(parsed.ampacity)
        assertTrue(parsed.problems.isEmpty())

        val output = voltageDropOutput(dropOnlyInputs, AppSettings())
        assertEquals(
            listOf("min_size", "percent_drop", "voltage_drop", "voltage_at_load", "max_length"),
            output.results.map { it.key },
        )
        assertTrue(output.notices.any { it.text == "Ampacity, terminal temperature, derating and overcurrent protection are not checked." })

        val manualDropOnly = voltageDropOutput(dropOnlyInputs.copy(manualSize = ConductorSize.AWG_12.label), AppSettings())
        assertEquals(
            listOf("voltage_drop", "percent_drop", "voltage_at_load", "wire_size", "min_size", "max_length"),
            manualDropOnly.results.map { it.key },
        )
        assertTrue(manualDropOnly.notices.any { it.text == "Ampacity, terminal temperature, derating and overcurrent protection are not checked." })
    }

    @Test fun unitSystemChangesOnlyNoteTemperatures() {
        val dropMin = VoltageDrop.smallestSize(baseAc)
        val imperial = VoltageDrop.checkAmpacity(baseAc, AmpacityCheckRequest(ambientC = 40.0), dropMin, UnitSystem.IMPERIAL)
        val metric = VoltageDrop.checkAmpacity(baseAc, AmpacityCheckRequest(ambientC = 40.0), dropMin, UnitSystem.METRIC)
        assertEquals(imperial.minimumSize, metric.minimumSize)
        assertEquals(imperial.minimumUsableAmpacity, metric.minimumUsableAmpacity)
        assertEquals(imperial.governingSize, metric.governingSize)
        assertEquals(imperial.governing, metric.governing)
        assertTrue(imperial.notes.any { it.contains("104 °F") })
        assertTrue(metric.notes.any { it.contains("40 °C") })
    }
}

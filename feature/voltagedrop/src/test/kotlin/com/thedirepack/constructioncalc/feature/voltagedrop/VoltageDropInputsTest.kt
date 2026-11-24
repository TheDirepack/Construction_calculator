package com.thedirepack.constructioncalc.feature.voltagedrop

import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.electrical.AcPhases
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.Stranding
import com.thedirepack.constructioncalc.core.electrical.TemperatureRating
import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeProfile
import com.thedirepack.constructioncalc.core.electrical.code.RegionalAmendment
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.FractionRounding
import com.thedirepack.constructioncalc.core.measure.MetricDisplay
import com.thedirepack.constructioncalc.core.measure.MetricResolution
import com.thedirepack.constructioncalc.core.measure.Precision
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.core.ui.components.NoticeLevel
import com.thedirepack.constructioncalc.feature.voltagedrop.domain.Method
import com.thedirepack.constructioncalc.feature.voltagedrop.domain.VoltageDrop
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoltageDropInputsTest {
    private val autoFilled = VoltageDropInputs(
        amps = "16",
        length = Quantity.feet(Rational.of(100)).stored(),
        method = Method.DC_TABLE_8,
        stranding = Stranding.SOLID, // Table 8 solid rows: 12 AWG = 1.93, 8 AWG = 0.764
    )

    private val manual12 = autoFilled.copy(manualSize = ConductorSize.AWG_12.label)

    @Test fun savedInputsRoundTripAndDefaultsDecode() {
        val custom = autoFilled.copy(
            system = AcPhases.THREE,
            voltagePreset = 0,
            customVoltage = "400",
            material = "ALUMINUM",
            size = "2/0 AWG",
            manualSize = "2/0 AWG",
            parallelSets = 2,
        )
        assertEquals(custom, ToolInputs.decode<VoltageDropInputs>(ToolInputs.encode(custom)))
        val autoCustom = custom.copy(manualSize = null)
        assertEquals(autoCustom, ToolInputs.decode<VoltageDropInputs>(ToolInputs.encode(autoCustom)))
        // A file with no fields (or only unknown ones) still opens with defaults (auto-sizing on).
        val defaults = ToolInputs.decode<VoltageDropInputs>(JsonObject(emptyMap()))!!
        assertEquals(VoltageDropInputs(), defaults)
        assertNull(defaults.manualSize)
        assertTrue(defaults.isAutoSizing)
        assertEquals("0.85", defaults.powerFactor)
        assertEquals("3", defaults.targetPercent)
    }

    @Test fun autoSizingIsDefaultAndFindsSmallestCompliantWire() {
        val quickInputs = VoltageDropInputs(
            amps = "16",
            length = Quantity.feet(Rational.of(100)).stored(),
        )
        val parsed = quickInputs.parse()
        assertTrue(parsed.isAutoSized)
        val request = parsed.request!!
        assertEquals(120.0, request.volts, 0.0)
        assertEquals(100.0, request.lengthFeet, 1e-9)
        assertEquals(0.85, request.powerFactor, 0.0)
        assertEquals(3.0, request.targetPercent, 0.0)
        assertEquals(Method.AC_TABLE_9, request.method)
        assertEquals(ConductorSize.AWG_10, request.size)
        assertEquals(ConductorSize.AWG_10, parsed.smallestSize)

        val output = voltageDropOutput(quickInputs, AppSettings())
        assertTrue(output.isAutoSized)
        assertEquals(ConductorSize.AWG_10, output.activeSize)
        assertFalse(output.canParallel)
        assertFalse(output.hasParallelViolation)
        assertEquals(
            listOf("min_size", "percent_drop", "voltage_drop", "voltage_at_load", "max_length"),
            output.results.map { it.key },
        )
        val byKey = output.results.associateBy { it.key }
        assertEquals("10 AWG Cu", byKey.getValue("min_size").text)
        assertEquals("2.79 %", byKey.getValue("percent_drop").text)
        assertEquals("3.35 V", byKey.getValue("voltage_drop").text)
        assertEquals("116.65 V", byKey.getValue("voltage_at_load").text)
        assertTrue(output.notices.none { it.level == NoticeLevel.WARNING })
        assertTrue(output.summary.contains("10 AWG Cu"))
    }

    @Test fun manualOverrideParsesAndOutputsSelectedWireWithKinds() {
        val request = manual12.parse().request!!
        assertEquals(120.0, request.volts, 0.0)
        assertEquals(100.0, request.lengthFeet, 1e-9)
        assertEquals(ConductorSize.AWG_12, request.size)
        assertEquals(3.0, request.targetPercent, 0.0)

        val output = voltageDropOutput(manual12, AppSettings())
        assertFalse(output.isAutoSized)
        assertEquals(ConductorSize.AWG_12, output.activeSize)
        val byKey = output.results.associateBy { it.key }
        assertEquals(
            listOf("voltage_drop", "percent_drop", "voltage_at_load", "wire_size", "min_size", "max_length"),
            output.results.map { it.key },
        )
        assertEquals(ValueKind.VOLTAGE, byKey.getValue("voltage_drop").kind)
        assertEquals("6.18 V", byKey.getValue("voltage_drop").text)
        assertEquals(ValueKind.PERCENT, byKey.getValue("percent_drop").kind)
        assertEquals("5.15 %", byKey.getValue("percent_drop").text)
        assertEquals(ValueKind.VOLTAGE, byKey.getValue("voltage_at_load").kind)
        assertEquals("113.82 V", byKey.getValue("voltage_at_load").text)
        assertEquals("12 AWG Cu", byKey.getValue("wire_size").text)
        assertEquals(ValueKind.TEXT, byKey.getValue("min_size").kind)
        assertEquals("8 AWG Cu", byKey.getValue("min_size").text)
        val max = byKey.getValue("max_length")
        assertEquals(ValueKind.LENGTH, max.kind)
        // 58.2902 ft = 699.48 in, floored to 1/64
        assertEquals(699.48, max.rational!!.toDouble(), 0.02)
        assertNotNull(output.notices.firstOrNull { it.level == NoticeLevel.WARNING })
        assertTrue(output.notices.any { it.text.contains("75 °C") })
        assertTrue(output.notices.any { it.text.contains("Table 8") })
        assertTrue(output.summary.contains("12 AWG Cu"))
    }

    @Test fun incompleteFormIsMissingNotError() {
        val parsed = VoltageDropInputs().parse()
        assertNull(parsed.request)
        assertEquals(listOf("Load current", "Length"), parsed.missing)
        assertTrue(parsed.problems.isEmpty())
        assertTrue(voltageDropOutput(VoltageDropInputs(), AppSettings()).notices.isEmpty())
    }

    @Test fun badNumbersAreProblems() {
        val parsed = autoFilled.copy(amps = "1.2.3").parse()
        assertNull(parsed.request)
        assertTrue(parsed.problems.single().startsWith("Load current"))
        assertEquals(listOf("Load current isn't a number."), parsed.problems)
        assertTrue(autoFilled.copy(amps = "0").parse().problems.isNotEmpty())
        assertTrue(autoFilled.copy(manualSize = "99 AWG").parse().problems.isNotEmpty())
        assertEquals(240.0, autoFilled.copy(voltagePreset = 0, customVoltage = "240,0").parse().request!!.volts, 0.0)
    }

    @Test fun maximumLengthIsFlooredAtDisplayPrecisionInBothSystems() {
        fun maximum(settings: AppSettings, inputs: VoltageDropInputs = manual12) =
            voltageDropOutput(inputs, settings).results.first { it.key == "max_length" }

        val fractional = maximum(AppSettings())
        assertEquals("58' 3 7/16\"", fractional.text)
        assertEquals(Rational.of(22383, 32), fractional.rational)
        assertEquals("58' 3\"", maximum(AppSettings(precision = Precision.HALF)).text)
        // Even with FractionRounding.UP in user settings, BUG-05 max length stays floored!
        assertEquals("58' 3 7/16\"", maximum(AppSettings(fractionRounding = FractionRounding.UP)).text)

        val metric = AppSettings(unitSystem = UnitSystem.METRIC, metricDisplay = MetricDisplay.MILLIMETERS)
        assertEquals("17766 mm", maximum(metric).text)
        assertEquals("17766.8 mm", maximum(metric.copy(metricResolution = MetricResolution.TENTH_MILLIMETER)).text)

        // Auto-sizing mode preserves the exact same BUG-05 floor behavior on the sized conductor.
        val autoOut = voltageDropOutput(autoFilled, AppSettings())
        val autoMax = autoOut.results.first { it.key == "max_length" }
        val autoCalc = VoltageDrop.calculate(autoFilled.parse().request!!)!!
        assertTrue(autoMax.rational!!.toDouble() <= autoCalc.maxLengthFeet * 12.0 + 1e-9)
        assertTrue(autoMax.rational!!.toDouble() > autoCalc.maxLengthFeet * 12.0 - (1.0 / 16.0))
    }

    @Test fun nec310_10_gParallelGatingAndOverrideWarning() {
        // Small wire (< 1/0 AWG): quick parallel control hidden.
        val small = voltageDropOutput(autoFilled, AppSettings())
        assertFalse(small.canParallel)
        assertFalse(small.hasParallelViolation)

        // Large feeder (>= 1/0 AWG): quick parallel control enabled.
        val largeInputs = VoltageDropInputs(
            system = AcPhases.THREE,
            voltagePreset = 208,
            amps = "250",
            length = Quantity.feet(Rational.of(250)).stored(),
        )
        val largeSingle = voltageDropOutput(largeInputs, AppSettings())
        assertTrue(largeSingle.activeSize!! >= ConductorSize.AWG_1_0)
        assertTrue(largeSingle.canParallel)
        assertFalse(largeSingle.hasParallelViolation)

        val largeParallel = voltageDropOutput(largeInputs.copy(parallelSets = 2), AppSettings())
        assertTrue(largeParallel.activeSize!! >= ConductorSize.AWG_1_0)
        assertTrue(largeParallel.canParallel)
        assertFalse(largeParallel.hasParallelViolation)
        assertTrue(largeParallel.results.first { it.key == "min_size" }.text.endsWith("× 2 sets"))

        // Explicit manual override with < 1/0 AWG and parallelSets > 1 emits NEC 310.10(G) warning.
        val illegalOverride = voltageDropOutput(
            manual12.copy(parallelSets = 2),
            AppSettings(),
        )
        assertFalse(illegalOverride.canParallel)
        assertTrue(illegalOverride.hasParallelViolation)
        assertTrue(
            illegalOverride.notices.any {
                it.level == NoticeLevel.WARNING &&
                    it.text.contains("310.10(G)") &&
                    it.text.contains("1/0 AWG")
            },
        )
    }

    @Test fun parallelSetsShowInMinimumSize() {
        val output = voltageDropOutput(autoFilled.copy(amps = "300", parallelSets = 2, method = Method.DC_TABLE_8), AppSettings())
        assertTrue(output.results.first { it.key == "min_size" }.text.endsWith("× 2 sets"))
    }

    @Test fun autoSizingFallbackWhenNoSingleSizeFitsEnablesParallelSuggestion() {
        val huge = VoltageDropInputs(
            amps = "2000",
            length = Quantity.feet(Rational.of(5000)).stored(),
        )
        val output = voltageDropOutput(huge, AppSettings())
        assertEquals("None up to 1000 kcmil", output.results.first { it.key == "min_size" }.text)
        assertEquals(ConductorSize.KCMIL_1000, output.activeSize)
        assertTrue(output.canParallel)
        assertTrue(output.notices.any { it.level == NoticeLevel.WARNING && it.text.contains("1000 kcmil") })
    }

    @Test fun customPowerFactorTargetAndAdvancedSummary() {
        assertEquals("3% code · PF 0.85 · AC Table 9 · PVC", advancedSummary(VoltageDropInputs()))
        assertEquals(
            "3% code · PF 0.85 · AC Table 9 · PVC · Ampacity on",
            advancedSummary(VoltageDropInputs(checkAmpacity = true)),
        )
        assertEquals(
            "3% code · PF 0.85 · AC Table 9 · PVC · Ampacity (20 A)",
            advancedSummary(VoltageDropInputs(checkAmpacity = true, ocpdAmps = "20")),
        )

        val base = VoltageDropInputs(
            system = AcPhases.THREE,
            voltagePreset = 480,
            amps = "100",
            length = Quantity.feet(Rational.of(400)).stored(),
        )
        val defaultOut = voltageDropOutput(base, AppSettings())
        val unityOut = voltageDropOutput(base.copy(powerFactor = "1.0"), AppSettings())
        assertTrue(
            unityOut.results.first { it.key == "voltage_drop" }.rational!! <
                defaultOut.results.first { it.key == "voltage_drop" }.rational!! ||
                unityOut.activeSize!! <= defaultOut.activeSize!!,
        )

        val relaxed = base.copy(powerFactor = "0.95", targetPercent = "5", manualSize = "4 AWG", parallelSets = 2)
        assertEquals("5% target · PF 0.95 · AC Table 9 · PVC · 4 AWG manual · 2× parallel", advancedSummary(relaxed))
        val relaxedOut = voltageDropOutput(relaxed, AppSettings())
        assertTrue(relaxedOut.notices.none { it.level == NoticeLevel.WARNING && it.text.contains("above your") })
        assertTrue(relaxedOut.hasParallelViolation) // 4 AWG < 1/0 AWG with 2 sets!

        // Code profile override changes default target and summary code badge.
        val amended = CodeProfile(
            customAmendment = RegionalAmendment(valuesTransform = { it.copy(voltageDropBranchPercent = 2.0) }),
        )
        Code.withProfile(amended) {
            val profileDefaults = VoltageDropInputs()
            assertEquals("2", profileDefaults.targetPercent)
            assertEquals("2% code · PF 0.85 · AC Table 9 · PVC", advancedSummary(profileDefaults))
        }
    }

    @Test fun table9NoticeNamesTheConduit() {
        val output = voltageDropOutput(autoFilled.copy(method = Method.AC_TABLE_9), AppSettings())
        assertTrue(output.notices.any { it.text.contains("Table 9") && it.text.contains("PVC") && it.text.contains("0.85") })
    }

    @Test fun unsupportedManualSizeIsAnErrorNotice() {
        val output = voltageDropOutput(autoFilled.copy(method = Method.AC_TABLE_9, manualSize = "18 AWG"), AppSettings())
        assertTrue(output.results.isEmpty())
        assertEquals(NoticeLevel.ERROR, output.notices.single().level)
    }

    @Test fun viewModelMovesToNearestListedSizeAndPreservesAutoSize() {
        val vm = VoltageDropViewModel()
        vm.update { autoFilled.copy(size = "16 AWG", manualSize = "16 AWG", method = Method.DC_TABLE_8) }
        assertEquals("16 AWG", vm.inputs.size)
        assertEquals("16 AWG", vm.inputs.manualSize)
        vm.update { it.copy(method = Method.AC_TABLE_9) }
        assertEquals("14 AWG", vm.inputs.size)
        assertEquals("14 AWG", vm.inputs.manualSize)
        vm.update { it.copy(size = "1250 kcmil", manualSize = "1250 kcmil", method = Method.DC_TABLE_8) }
        assertEquals("1250 kcmil", vm.inputs.size)
        assertEquals("1250 kcmil", vm.inputs.manualSize)
        vm.update { it.copy(method = Method.AC_TABLE_9) }
        assertEquals("1000 kcmil", vm.inputs.size)
        assertEquals("1000 kcmil", vm.inputs.manualSize)
        vm.update { it.copy(size = "14 AWG", manualSize = "14 AWG", material = "ALUMINUM") }
        assertEquals("12 AWG", vm.inputs.size)
        assertEquals("12 AWG", vm.inputs.manualSize)
        // Clearing manualSize returns to auto-sizing and stays null across method/material switches.
        vm.update { it.copy(manualSize = null, method = Method.DC_TABLE_8) }
        assertNull(vm.inputs.manualSize)
    }

    @Test fun lengthsAndTemperaturesFollowTheSystem() {
        val metric = AppSettings(unitSystem = UnitSystem.METRIC)
        val imperial = voltageDropOutput(manual12, AppSettings())
        val si = voltageDropOutput(manual12, metric)
        assertTrue(imperial.notices.any { it.text.contains("167 °F (75 °C)") })
        assertTrue(si.notices.any { it.text.contains("75 °C") && !it.text.contains("°F") })
        assertTrue(imperial.summary, imperial.summary.contains("100'"))
        assertTrue(si.summary, si.summary.contains(" m of "))
        assertTrue(si.results.first { it.key == "max_length" }.text.endsWith(" m"))
        // exact values and electrical results don't change with the system
        assertEquals(imperial.results.map { it.exact }, si.results.map { it.exact })
        assertEquals(imperial.results.first().text, si.results.first().text)
    }

    @Test fun ampacityInputsRoundTripAndDefaultsPreserved() {
        val custom = autoFilled.copy(
            checkAmpacity = true,
            insulation = TemperatureRating.C90,
            termination = TemperatureRating.C60,
            continuousLoad = true,
            ambientC = "40",
            currentCarrying = "6",
            ocpdAmps = "30",
        )
        assertEquals(custom, ToolInputs.decode<VoltageDropInputs>(ToolInputs.encode(custom)))
        val decodedDefaults = ToolInputs.decode<VoltageDropInputs>(JsonObject(emptyMap()))!!
        assertFalse(decodedDefaults.checkAmpacity)
        assertEquals(TemperatureRating.C75, decodedDefaults.insulation)
        assertEquals(TemperatureRating.C75, decodedDefaults.termination)
        assertFalse(decodedDefaults.continuousLoad)
        assertEquals("30", decodedDefaults.ambientC)
        assertEquals("3", decodedDefaults.currentCarrying)
        assertEquals("", decodedDefaults.ocpdAmps)
    }

    @Test fun ampacityValidationErrorsAndOptionalOcpd() {
        val enabled = autoFilled.copy(checkAmpacity = true)
        assertNull(enabled.copy(ambientC = "hot").parse().request)
        assertTrue(enabled.copy(ambientC = "hot").parse().problems.isNotEmpty())
        assertTrue("Ambient temperature" in enabled.copy(ambientC = "").parse().missing)

        for (badCcc in listOf("0", "-1", "3.5", "abc")) {
            val parsed = enabled.copy(currentCarrying = badCcc).parse()
            assertNull(parsed.request)
            assertNull(parsed.ampacity)
            assertTrue(parsed.problems.isNotEmpty())
        }
        assertTrue("Current-carrying conductors" in enabled.copy(currentCarrying = "").parse().missing)

        for (badOcpd in listOf("0", "-20", "15.5", "abc")) {
            val parsed = enabled.copy(ocpdAmps = badOcpd).parse()
            assertNull(parsed.request)
            assertTrue(parsed.problems.isNotEmpty())
        }

        val blankOcpd = enabled.copy(ocpdAmps = "").parse()
        assertNotNull(blankOcpd.request)
        assertNull(blankOcpd.ampacity!!.ocpdAmps)

        val negativeAmbientOk = enabled.copy(ambientC = "-5").parse()
        assertNotNull(negativeAmbientOk.request)
        assertEquals(-5.0, negativeAmbientOk.ampacity!!.ambientC, 0.0)
    }

    @Test fun ampacityOutputShowsGoverningConstraintAndWarnings() {
        val dropGoverns = voltageDropOutput(autoFilled.copy(checkAmpacity = true, ocpdAmps = "20"), AppSettings())
        val byKey = dropGoverns.results.associateBy { it.key }
        assertEquals(ConductorSize.AWG_8, dropGoverns.activeSize)
        assertEquals("8 AWG Cu", byKey.getValue("min_size").text)
        assertEquals("12 AWG Cu (25 A usable)", byKey.getValue("ampacity_size").text)
        assertEquals("8 AWG Cu · Voltage drop governs", byKey.getValue("governing_size").text)
        assertTrue(dropGoverns.notices.any { it.text.contains("20 A OCPD") })
        assertFalse(dropGoverns.notices.any { it.text.contains("Ampacity, terminal temperature, derating and overcurrent protection are not checked.") })
        assertTrue(dropGoverns.notices.none { it.level == NoticeLevel.WARNING })

        val ampGovernsOut = voltageDropOutput(
            autoFilled.copy(
                amps = "25",
                length = Quantity.feet(Rational.of(10)).stored(),
                method = Method.AC_TABLE_9,
                checkAmpacity = true,
            ),
            AppSettings(),
        )
        assertEquals(ConductorSize.AWG_12, ampGovernsOut.activeSize)
        assertTrue(ampGovernsOut.notices.none { it.level == NoticeLevel.WARNING })
        val ampGoverns = ampGovernsOut.results.associateBy { it.key }
        assertEquals("14 AWG Cu", ampGoverns.getValue("min_size").text)
        assertEquals("12 AWG Cu (25 A usable)", ampGoverns.getValue("ampacity_size").text)
        assertEquals("12 AWG Cu · Ampacity governs", ampGoverns.getValue("governing_size").text)
        assertEquals("0.86 V", ampGoverns.getValue("voltage_drop").text)

        val tieOut = voltageDropOutput(
            autoFilled.copy(
                amps = "25",
                length = Quantity.feet(Rational.of(25)).stored(),
                stranding = Stranding.STRANDED,
                checkAmpacity = true,
            ),
            AppSettings(),
        )
        assertEquals(ConductorSize.AWG_12, tieOut.activeSize)
        assertEquals("12 AWG Cu · Drop & ampacity match", tieOut.results.first { it.key == "governing_size" }.text)
        assertTrue(tieOut.notices.any { it.text.contains("not checked") })

        val ampFail = voltageDropOutput(
            manual12.copy(amps = "30", checkAmpacity = true, ocpdAmps = "30"),
            AppSettings(),
        )
        val warnings = ampFail.notices.filter { it.level == NoticeLevel.WARNING }
        assertTrue(warnings.any { it.text.contains("12 AWG Cu fails ampacity check") && it.text.contains("10 AWG Cu") })

        // When ampacity governs into >= 1/0 AWG on a short high-current run, parallel eligibility opens automatically.
        val parallelEligibleByAmpacity = voltageDropOutput(
            VoltageDropInputs(
                amps = "150",
                length = Quantity.feet(Rational.of(10)).stored(),
                checkAmpacity = true,
            ),
            AppSettings(),
        )
        assertEquals(ConductorSize.AWG_1_0, parallelEligibleByAmpacity.activeSize)
        assertTrue(parallelEligibleByAmpacity.canParallel)
        assertTrue(parallelEligibleByAmpacity.notices.none { it.level == NoticeLevel.WARNING })
    }
}

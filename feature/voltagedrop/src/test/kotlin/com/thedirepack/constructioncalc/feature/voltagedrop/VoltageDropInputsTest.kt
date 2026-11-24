package com.thedirepack.constructioncalc.feature.voltagedrop

import com.thedirepack.constructioncalc.core.electrical.AcPhases
import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.Stranding
import com.thedirepack.constructioncalc.core.electrical.TemperatureRating
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.MetricDisplay
import com.thedirepack.constructioncalc.core.measure.MetricResolution
import com.thedirepack.constructioncalc.core.measure.Precision
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.core.ui.components.NoticeLevel
import com.thedirepack.constructioncalc.feature.voltagedrop.domain.Method
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.thedirepack.constructioncalc.core.measure.UnitSystem

class VoltageDropInputsTest {
    private val filled = VoltageDropInputs(
        amps = "16",
        length = Quantity.feet(Rational.of(100)).stored(),
        method = Method.DC_TABLE_8,
        stranding = Stranding.SOLID, // hand-checked numbers below use Table 8's 12 AWG solid row (1.93)
    )

    @Test fun savedInputsRoundTripAndDefaultsDecode() {
        val custom = filled.copy(system = AcPhases.THREE, voltagePreset = 0, customVoltage = "400", material = "ALUMINUM", size = "2/0 AWG", parallelSets = 2)
        assertEquals(custom, ToolInputs.decode<VoltageDropInputs>(ToolInputs.encode(custom)))
        // A file with no fields (or only unknown ones) still opens with defaults.
        assertEquals(VoltageDropInputs(), ToolInputs.decode<VoltageDropInputs>(JsonObject(emptyMap())))
    }

    @Test fun parsesToRequest() {
        val request = filled.parse().request!!
        assertEquals(120.0, request.volts, 0.0)
        assertEquals(100.0, request.lengthFeet, 1e-9)
        assertEquals(ConductorSize.AWG_12, request.size)
        assertEquals(3.0, request.targetPercent, 0.0)
    }

    @Test fun incompleteFormIsMissingNotError() {
        val parsed = VoltageDropInputs().parse()
        assertNull(parsed.request)
        assertEquals(listOf("Load current", "Length"), parsed.missing)
        assertTrue(parsed.problems.isEmpty())
        assertTrue(voltageDropOutput(VoltageDropInputs(), AppSettings()).notices.isEmpty())
    }

    @Test fun badNumbersAreProblems() {
        val parsed = filled.copy(amps = "1.2.3").parse()
        assertNull(parsed.request)
        assertTrue(parsed.problems.single().startsWith("Load current"))
        assertEquals(listOf("Load current isn't a number."), parsed.problems)
        assertTrue(filled.copy(amps = "0").parse().problems.isNotEmpty())
        assertEquals(240.0, filled.copy(voltagePreset = 0, customVoltage = "240,0").parse().request!!.volts, 0.0)
    }

    @Test fun outputHasEveryResultWithKinds() {
        val output = voltageDropOutput(filled, AppSettings())
        val byKey = output.results.associateBy { it.key }
        assertEquals(
            listOf("voltage_drop", "percent_drop", "voltage_at_load", "min_size", "max_length"),
            output.results.map { it.key },
        )
        assertEquals(ValueKind.VOLTAGE, byKey.getValue("voltage_drop").kind)
        assertEquals("6.18 V", byKey.getValue("voltage_drop").text)
        assertEquals(ValueKind.PERCENT, byKey.getValue("percent_drop").kind)
        assertEquals("5.15 %", byKey.getValue("percent_drop").text)
        assertEquals(ValueKind.VOLTAGE, byKey.getValue("voltage_at_load").kind)
        assertEquals("113.82 V", byKey.getValue("voltage_at_load").text)
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

    @Test fun maximumLengthIsFlooredAtDisplayPrecisionInBothSystems() {
        fun maximum(settings: AppSettings) = voltageDropOutput(filled, settings).results.first { it.key == "max_length" }

        val fractional = maximum(AppSettings())
        assertEquals("58' 3 7/16\"", fractional.text)
        assertEquals(Rational.of(22383, 32), fractional.rational)
        assertEquals("58' 3\"", maximum(AppSettings(precision = Precision.HALF)).text)

        val metric = AppSettings(unitSystem = UnitSystem.METRIC, metricDisplay = MetricDisplay.MILLIMETERS)
        assertEquals("17766 mm", maximum(metric).text)
        assertEquals("17766.8 mm", maximum(metric.copy(metricResolution = MetricResolution.TENTH_MILLIMETER)).text)
    }

    @Test fun parallelSetsShowInMinimumSize() {
        val output = voltageDropOutput(filled.copy(amps = "300", parallelSets = 2, method = Method.DC_TABLE_8), AppSettings())
        assertTrue(output.results.first { it.key == "min_size" }.text.endsWith("× 2 sets"))
    }

    @Test fun table9NoticeNamesTheConduit() {
        val output = voltageDropOutput(filled.copy(method = Method.AC_TABLE_9), AppSettings())
        assertTrue(output.notices.any { it.text.contains("Table 9") && it.text.contains("PVC") && it.text.contains("0.85") })
    }

    @Test fun unsupportedSizeIsAnErrorNotice() {
        val output = voltageDropOutput(filled.copy(method = Method.AC_TABLE_9, size = "18 AWG"), AppSettings())
        assertTrue(output.results.isEmpty())
        assertEquals(NoticeLevel.ERROR, output.notices.single().level)
    }

    @Test fun viewModelMovesToNearestListedSize() {
        val vm = VoltageDropViewModel()
        vm.update { filled.copy(size = "16 AWG", method = Method.DC_TABLE_8) }
        assertEquals("16 AWG", vm.inputs.size)
        vm.update { it.copy(method = Method.AC_TABLE_9) }
        assertEquals("14 AWG", vm.inputs.size)
        vm.update { it.copy(size = "1250 kcmil", method = Method.DC_TABLE_8) }
        assertEquals("1250 kcmil", vm.inputs.size)
        vm.update { it.copy(method = Method.AC_TABLE_9) }
        assertEquals("1000 kcmil", vm.inputs.size)
        vm.update { it.copy(size = "14 AWG", material = "ALUMINUM") }
        assertEquals("12 AWG", vm.inputs.size)
    }

    @Test fun lengthsAndTemperaturesFollowTheSystem() {
        val metric = AppSettings(unitSystem = UnitSystem.METRIC)
        val imperial = voltageDropOutput(filled, AppSettings())
        val si = voltageDropOutput(filled, metric)
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
        val custom = filled.copy(
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
        val enabled = filled.copy(checkAmpacity = true)
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
        val dropGoverns = voltageDropOutput(filled.copy(checkAmpacity = true, ocpdAmps = "20"), AppSettings())
        val byKey = dropGoverns.results.associateBy { it.key }
        assertEquals("8 AWG Cu", byKey.getValue("min_size").text)
        assertEquals("12 AWG Cu (25 A usable)", byKey.getValue("ampacity_size").text)
        assertEquals("8 AWG Cu · Voltage drop governs", byKey.getValue("governing_size").text)
        assertTrue(dropGoverns.notices.any { it.text.contains("20 A OCPD") })
        assertFalse(dropGoverns.notices.any { it.text.contains("Ampacity, terminal temperature, derating and overcurrent protection are not checked.") })

        val ampGoverns = voltageDropOutput(
            filled.copy(
                amps = "25",
                length = Quantity.feet(Rational.of(10)).stored(),
                method = Method.AC_TABLE_9,
                checkAmpacity = true,
            ),
            AppSettings(),
        ).results.associateBy { it.key }
        assertEquals("14 AWG Cu", ampGoverns.getValue("min_size").text)
        assertEquals("12 AWG Cu (25 A usable)", ampGoverns.getValue("ampacity_size").text)
        assertEquals("12 AWG Cu · Ampacity governs", ampGoverns.getValue("governing_size").text)

        val tieOut = voltageDropOutput(
            filled.copy(
                amps = "25",
                length = Quantity.feet(Rational.of(25)).stored(),
                stranding = Stranding.STRANDED,
                checkAmpacity = true,
            ),
            AppSettings(),
        )
        assertEquals("12 AWG Cu · Drop & ampacity match", tieOut.results.first { it.key == "governing_size" }.text)
        assertTrue(tieOut.notices.any { it.text.contains("not checked") })

        val ampFail = voltageDropOutput(
            filled.copy(amps = "30", size = "12 AWG", checkAmpacity = true, ocpdAmps = "30"),
            AppSettings(),
        )
        val warnings = ampFail.notices.filter { it.level == NoticeLevel.WARNING }
        assertTrue(warnings.any { it.text.contains("12 AWG Cu fails ampacity check") && it.text.contains("10 AWG Cu") })
    }
}

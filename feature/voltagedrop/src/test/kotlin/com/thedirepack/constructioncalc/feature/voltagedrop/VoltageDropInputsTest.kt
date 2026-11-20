package com.thedirepack.constructioncalc.feature.voltagedrop

import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.Stranding
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.core.ui.components.NoticeLevel
import com.thedirepack.constructioncalc.feature.voltagedrop.domain.Method
import com.thedirepack.constructioncalc.feature.voltagedrop.domain.SystemType
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoltageDropInputsTest {
    private val filled = VoltageDropInputs(
        amps = "16",
        length = Quantity.feet(Rational.of(100)).stored(),
        method = Method.DC_TABLE_8,
        stranding = Stranding.SOLID, // hand-checked numbers below use Table 8's 12 AWG solid row (1.93)
    )

    @Test fun savedInputsRoundTripAndDefaultsDecode() {
        val custom = filled.copy(system = SystemType.THREE_PHASE, voltagePreset = 0, customVoltage = "400", material = "ALUMINUM", size = "2/0 AWG", parallelSets = 2)
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
}

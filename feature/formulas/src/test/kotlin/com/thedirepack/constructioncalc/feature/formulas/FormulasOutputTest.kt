package com.thedirepack.constructioncalc.feature.formulas

import com.thedirepack.constructioncalc.core.electrical.formula.FormulaCatalog
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.core.model.ValueKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import com.thedirepack.constructioncalc.core.data.AppSettings

class FormulasOutputTest {
    private fun inputs(id: String, vararg values: Pair<String, String>) =
        FormulaInputs.start(FormulaCatalog.find(id)!!).let { start -> values.fold(start) { acc, (k, v) -> acc.withValue(k, v) } }

    @Test
    fun threePhaseAmpsFromKw() {
        // 50 kW at 480 V, PF 0.9: I = 50000 / (√3 · 480 · 0.9) = 66.82 A.
        val out = formulasOutput(inputs("power-three-phase", "V" to "480", "P" to "50", "PF" to "0.9"))
        val amps = out.results.first { it.key == "power-three-phase.I" }
        assertEquals(ValueKind.CURRENT, amps.kind)
        assertEquals("66.82 A", amps.text)
        assertEquals("66.82 A", out.computedText["I"])
        // kW is entered in the default kW unit but published in watts.
        val kw = out.results.first { it.key.endsWith(".P") }
        assertEquals(50000.0, kw.rational!!.toDouble(), 1e-9)
        assertEquals("50 kW", kw.text)
        assertTrue(out.steps.toString(), out.steps.any { it.startsWith("S = √3 × V × I") && it.endsWith("I = 66.82 A") })
    }

    @Test
    fun unitChangeKeepsTheQuantity() {
        val i = inputs("power-three-phase", "P" to "50").withUnit("P", 0)
        assertEquals("50000", i.values["P"])
        assertEquals(50000.0, i.given()["P"]!!, 1e-9)
    }

    @Test
    fun defaultsArePrefilled() {
        val i = FormulaInputs.start(FormulaCatalog.find("impedance")!!)
        assertEquals("60", i.values["F"])
        assertEquals("0.00323", FormulaInputs.start(FormulaCatalog.find("resistance-temperature")!!).values["ALPHA"])
    }

    @Test
    fun lengthsArePublishedInInches() {
        val out = formulasOutput(inputs("voltage-drop-single-phase", "I" to "20", "CM" to "6530", "VD" to "3.6"))
        val length = out.results.first { it.key.endsWith(".L") }
        assertEquals(ValueKind.LENGTH, length.kind)
        assertEquals(12 * 3.6 * 6530 / (2 * 12.9 * 20), length.rational!!.toDouble(), 1e-6)
    }

    @Test
    fun badEntriesAndConflictsAreExplained() {
        val bad = formulasOutput(inputs("ohms-law", "V" to "12x"))
        assertTrue(bad.notices.any { it.text == "Voltage isn't a number." })
        val conflict = formulasOutput(inputs("ohms-law", "V" to "120", "I" to "10", "R" to "5"))
        assertTrue(conflict.notices.any { it.text.startsWith("The entered values don't agree") })
    }

    @Test
    fun savedInputsRoundTrip() {
        val i = inputs("transformer-three-phase", "S" to "75", "V" to "208", "Z" to "3.5")
        val back = ToolInputs.json.decodeFromJsonElement(FormulaInputs.serializer(), ToolInputs.encode(i))
        assertEquals(i, back)
    }

    @Test
    fun numberFormatting() {
        assertEquals("0.1235", FormulasText.number(0.123456))
        assertEquals("12.35", FormulasText.number(12.3456))
        assertEquals("83138.4", FormulasText.number(83138.43))
        assertEquals("0", FormulasText.number(0.0))
    }

    @Test
    fun lengthDefaultsToMetersInMetric() {
        val metric = AppSettings(unitSystem = UnitSystem.METRIC)
        val i = inputs("voltage-drop-single-phase", "I" to "20", "CM" to "6530", "VD" to "3.6")
        val imperial = formulasOutput(i)
        val si = formulasOutput(i, metric)
        assertEquals("ft", imperial.results.first { it.key.endsWith(".L") }.text.substringAfterLast(' '))
        assertEquals("m", si.results.first { it.key.endsWith(".L") }.text.substringAfterLast(' '))
        // published exact value (inches) is identical
        assertEquals(imperial.results.first { it.key.endsWith(".L") }.exact, si.results.first { it.key.endsWith(".L") }.exact)
        // Meters typed in metric become feet (30.48 m = 100 ft), and stay meters after switching.
        val typed = FormulaInputs.start(FormulaCatalog.find("voltage-drop-single-phase")!!).withValue("L", "30.48", UnitSystem.METRIC)
        assertEquals(100.0, typed.given(UnitSystem.METRIC)["L"]!!, 1e-9)
        assertEquals(100.0, typed.given(UnitSystem.IMPERIAL)["L"]!!, 1e-9)
        assertEquals(1, typed.unitIndex("L", UnitSystem.IMPERIAL))
    }

    @Test
    fun typedValueKeepsItsUnitWhenTheSystemChanges() {
        // 30 typed as meters in metric must still mean 30 m after switching to imperial.
        val start = FormulaInputs.start(FormulaCatalog.find("voltage-drop-single-phase")!!)
        val typed = start.withValue("L", "30", com.thedirepack.constructioncalc.core.measure.UnitSystem.METRIC)
        val metricFt = typed.given(com.thedirepack.constructioncalc.core.measure.UnitSystem.METRIC)["L"]!!
        val imperialFt = typed.given(com.thedirepack.constructioncalc.core.measure.UnitSystem.IMPERIAL)["L"]!!
        assertEquals(30 / 0.3048, metricFt, 1e-9)
        assertEquals(metricFt, imperialFt, 1e-9)
    }
}

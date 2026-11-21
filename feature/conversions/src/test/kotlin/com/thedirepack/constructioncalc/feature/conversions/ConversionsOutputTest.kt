package com.thedirepack.constructioncalc.feature.conversions

import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.core.ui.components.NoticeLevel
import com.thedirepack.constructioncalc.feature.conversions.domain.ConversionCategory
import com.thedirepack.constructioncalc.feature.conversions.domain.UnitTable
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversionsOutputTest {
    private val settings = AppSettings()

    private fun out(inputs: ConversionsInputs) = conversionsOutput(inputs, settings)

    private fun table(category: ConversionCategory, unit: String, value: String) =
        out(ConversionsInputs(category = category, fromUnit = unit, value = value))

    @Test fun savedInputsRoundTripAndDefaultsDecode() {
        val custom = ConversionsInputs(
            category = ConversionCategory.WIRE_SIZE, wireFrom = WireFrom.MM2,
            value = "75", length = Quantity.inches(Rational.of(30)).stored(),
        )
        assertEquals(custom, ToolInputs.decode<ConversionsInputs>(ToolInputs.encode(custom)))
        assertEquals(ConversionsInputs(), ToolInputs.decode<ConversionsInputs>(JsonObject(emptyMap())))
        assertTrue(ConversionsInputs().usesCalculator)
    }

    @Test fun emptyInputsGiveNoResults() {
        assertTrue(out(ConversionsInputs()).results.isEmpty())
        assertTrue(table(ConversionCategory.AREA, "sqft", "").results.isEmpty())
        val bad = table(ConversionCategory.AREA, "sqft", ".")
        assertTrue(bad.results.isEmpty())
        assertEquals(NoticeLevel.ERROR, bad.notices.single().level)
    }

    @Test fun lengthFromCalculatorPublishesInches() {
        val inputs = ConversionsInputs(length = Quantity.inches(Rational.ONE).stored())
        val o = out(inputs)
        val first = o.results.first()
        assertEquals("length", first.key)
        assertEquals(ValueKind.LENGTH, first.kind)
        assertEquals(Rational.ONE, first.rational)
        assertEquals(1 + UnitTable.unitsFor(ConversionCategory.LENGTH).size, o.results.size)
        val mm = o.results.first { it.key == "mm" }
        assertEquals("25.4 mm", mm.text)
        assertEquals(ValueKind.LENGTH, mm.kind)
        assertEquals(Rational.ONE, mm.rational)
        assertEquals("1 in", o.results.first { it.key == "in" }.text)
    }

    @Test fun metricLengthTypedAsNumber() {
        val o = table(ConversionCategory.LENGTH, "m", "1")
        assertEquals("39.37008 in", o.results.first { it.key == "in" }.text)
        assertEquals("3.280840 ft".replace("3.280840", "3.28084"), o.results.first { it.key == "ft" }.text)
        // base inches published on every row
        assertEquals(o.results.first { it.key == "in" }.exact, o.results.first { it.key == "km" }.exact)
    }

    @Test fun kindsAndBaseUnits() {
        fun kindOf(c: ConversionCategory, unit: String, v: String = "10") = table(c, unit, v).results.map { it.kind }.toSet()
        assertEquals(setOf(ValueKind.AREA), kindOf(ConversionCategory.AREA, "sqft"))
        assertEquals(setOf(ValueKind.VOLUME), kindOf(ConversionCategory.VOLUME, "gal"))
        assertEquals(setOf(ValueKind.WEIGHT), kindOf(ConversionCategory.WEIGHT, "kg"))
        assertEquals(setOf(ValueKind.TEMPERATURE), kindOf(ConversionCategory.TEMPERATURE, "c"))
        assertEquals(setOf(ValueKind.PRESSURE), kindOf(ConversionCategory.PRESSURE, "bar"))
        assertEquals(setOf(ValueKind.POWER), kindOf(ConversionCategory.POWER, "hp"))
        assertEquals(setOf(ValueKind.FLOW), kindOf(ConversionCategory.FLOW, "lps"))
        assertEquals(setOf(ValueKind.ANGLE), kindOf(ConversionCategory.ANGLE, "rad"))
        assertEquals(setOf(ValueKind.NUMBER), kindOf(ConversionCategory.ENERGY, "kwh"))
        assertEquals(setOf(ValueKind.NUMBER), kindOf(ConversionCategory.SPEED, "mph"))
        assertEquals(setOf(ValueKind.NUMBER), kindOf(ConversionCategory.RESISTANCE_LENGTH, "okft"))

        // exact values are in the base unit: 100 °C is 212 °F, 1 US gal is 231 cu in, 1 hp ≈ 745.7 W
        assertEquals(Rational.of(212), table(ConversionCategory.TEMPERATURE, "c", "100").results.first().rational)
        assertEquals(Rational.of(231), table(ConversionCategory.VOLUME, "gal", "1").results.first().rational)
        assertEquals(745.7, table(ConversionCategory.POWER, "hp", "1").results.first().rational!!.toDouble(), 0.001)
        assertEquals(Rational.of(144), table(ConversionCategory.AREA, "sqft", "1").results.first().rational)
        // number-only rows publish the number in their own unit
        val energy = table(ConversionCategory.ENERGY, "kwh", "1").results
        assertEquals(Rational.ONE, energy.first { it.key == "kwh" }.rational)
        assertEquals(Rational.of(3600), energy.first { it.key == "kj" }.rational)
        assertEquals("3600 kJ", energy.first { it.key == "kj" }.text)
    }

    @Test fun temperatureBelowAbsoluteZeroIsRejected() {
        val o = table(ConversionCategory.TEMPERATURE, "c", "-300")
        assertTrue(o.results.isEmpty())
        assertEquals(NoticeLevel.ERROR, o.notices.single().level)
        assertTrue(table(ConversionCategory.TEMPERATURE, "c", "-273.15").results.isNotEmpty())
    }

    @Test fun summaryAndTextHaveUnits() {
        val o = table(ConversionCategory.PRESSURE, "psi", "30")
        assertTrue(o.results.all { it.text.contains(' ') })
        assertEquals("Pressure: 30 psi = 206.84 kPa = 2.0684 bar", o.summary)
    }

    @Test fun wireSizeFromSize() {
        val o = out(ConversionsInputs(category = ConversionCategory.WIRE_SIZE, wireSize = "12 AWG"))
        assertEquals("12 AWG", o.results.first { it.key == "size" }.text)
        assertEquals("6530 cmil", o.results.first { it.key == "cmil" }.text)
        assertEquals("3.309 mm²", o.results.first { it.key == "mm2" }.text)
        assertEquals(ValueKind.AREA, o.results.first { it.key == "mm2" }.kind)
        assertEquals(ValueKind.TEXT, o.results.first { it.key == "size" }.kind)
    }

    @Test fun wireSizeNearestForMm2() {
        val o = out(ConversionsInputs(category = ConversionCategory.WIRE_SIZE, wireFrom = WireFrom.MM2, value = "10"))
        assertEquals("6 AWG", o.results.first { it.key == "size" }.text)
        assertEquals("13.3 mm²", o.results.first { it.key == "size_mm2" }.text.replace("13.30", "13.3"))
        assertEquals("19735 cmil", o.results.first { it.key == "entered_cmil" }.text)
        assertTrue(o.results.first { it.key == "entered_mm2" }.rational!!.toDouble() > 0)
    }

    @Test fun wireSizeTooLargeWarns() {
        val o = out(ConversionsInputs(category = ConversionCategory.WIRE_SIZE, wireFrom = WireFrom.CMIL, value = "3000000"))
        assertTrue(o.results.none { it.key == "size" })
        assertTrue(o.notices.any { it.level == NoticeLevel.WARNING })
    }

    @Test fun wireAreaOverflowProducesAnErrorInsteadOfCrashing() {
        val input = ConversionsInputs(category = ConversionCategory.WIRE_SIZE, wireFrom = WireFrom.MM2)
        for (value in listOf("1e309", "1e307")) {
            val output = out(input.copy(value = value))
            assertTrue(output.results.isEmpty())
            assertEquals(NoticeLevel.ERROR, output.notices.single().level)
        }
    }

    @Test fun excessiveExponentsAreRejectedAtNumberEntry() {
        assertEquals(Rational.of(1, 1000), parseRational("1e-3"))
        for (value in listOf("1e10020", "1e-10020")) {
            val output = table(ConversionCategory.PRESSURE, "psi", value)
            assertTrue(output.results.isEmpty())
            assertEquals(NoticeLevel.ERROR, output.notices.single().level)
        }
    }
}

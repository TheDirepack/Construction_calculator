package com.thedirepack.constructioncalc.core.measure

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.input.CalcInput
import com.thedirepack.constructioncalc.core.measure.input.CalcState
import com.thedirepack.constructioncalc.core.measure.input.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitSystemTest {
    private fun inches(n: Long, d: Long = 1) = Quantity.inches(Rational.of(n, d))
    private val metric = LengthFormat(UnitSystem.METRIC)

    @Test
    fun metricLiteralsAreExactInches() {
        assertEquals(inches(1), MeasureLiteral.parse("25.4mm"))
        assertEquals(inches(12), MeasureLiteral.parse("30.48cm"))
        assertEquals(Quantity.inches(Rational.of(1000) / Units.MM_PER_INCH), MeasureLiteral.parse("1m"))
        assertEquals(Quantity.inches(Rational.of(2500) / Units.MM_PER_INCH), MeasureLiteral.parse("2.5m"))
        assertEquals("300 mm", MeasureLiteral.display("300mm"))
    }

    @Test
    fun metricPrefixesWhileTyping() {
        assertTrue(MeasureLiteral.isValidPrefix("12c"))
        assertTrue(MeasureLiteral.isValidPrefix("12m"))
        assertTrue(MeasureLiteral.isValidPrefix("12mm"))
        assertFalse(MeasureLiteral.isValidPrefix("12mmm"))
        assertFalse(MeasureLiteral.isValidPrefix("12cx"))
        assertFalse(MeasureLiteral.isValidPrefix("5'3mm"))
    }

    @Test
    fun unitKeyTypesAllOrNothing() {
        var s = CalcState()
        for (c in "300") s = CalcInput.reduce(s, Key.Char(c)).state
        val out = CalcInput.reduce(s, Key.Text("mm"))
        assertTrue(out.accepted)
        val done = CalcInput.reduce(CalcInput.reduce(out.state, Key.Op(Operator.ADD)).state.let { st ->
            "1m".fold(st) { acc, c -> CalcInput.reduce(acc, Key.Char(c)).state }
        }, Key.Equals)
        assertEquals(Quantity.inches(Rational.of(1300) / Units.MM_PER_INCH), done.completed!!.result)
        assertFalse(CalcInput.reduce(out.state, Key.Text("cm")).accepted)
    }

    @Test
    fun mixedSystemsAdd() {
        // 1' + 100 mm = 304.8 + 100 = 404.8 mm
        val sum = Evaluator.evaluate(listOf(Token.Literal("1'"), Token.Op(Operator.ADD), Token.Literal("100mm")))
        assertEquals("405 mm", MeasureFormat.primary(sum, metric))
        assertEquals("404.8 mm", MeasureFormat.primary(sum, metric.copy(metricResolution = MetricResolution.TENTH_MILLIMETER)))
    }

    @Test
    fun metricLengthDisplay() {
        assertEquals("1250 mm", MetricFormat.length(Rational.of(1250) / Units.MM_PER_INCH, MetricDisplay.MILLIMETERS))
        assertEquals("3.048 m", MeasureFormat.primary(inches(120), metric))
        assertEquals("305 mm", MeasureFormat.primary(inches(12), metric))
        assertEquals("30.5 cm", MeasureFormat.primary(inches(12), metric.copy(metricDisplay = MetricDisplay.CENTIMETERS)))
        assertEquals("0.305 m", MeasureFormat.primary(inches(12), metric.copy(metricDisplay = MetricDisplay.METERS)))
        assertEquals("12.5 mm", MeasureFormat.primary(Quantity.inches(Rational.of(125, 10) / Units.MM_PER_INCH), metric.copy(metricResolution = MetricResolution.HALF_MILLIMETER)))
        assertEquals("−25 mm", MeasureFormat.primary(inches(-1), metric))
        assertEquals("0 mm", MeasureFormat.primary(inches(0), metric))
    }

    @Test
    fun imperialFormatIsUnchanged() {
        assertEquals("1' 0\"", MeasureFormat.primary(inches(12), LengthFormat()))
        assertEquals("12\"", MeasureFormat.primary(inches(12), LengthFormat(style = LengthStyle.INCHES)))
    }

    @Test
    fun metricAreaAndVolume() {
        // 1 sq ft = 0.0929 m²; 1 sq in = 645.2 mm²
        assertEquals("0.093 m²", MeasureFormat.primary(Quantity(Rational.of(144), Dimension.AREA), metric))
        assertEquals("645.2 mm²", MeasureFormat.primary(Quantity(Rational.of(1), Dimension.AREA), metric))
        // 1 cu ft = 28.32 L; 1 cu yd = 0.765 m³
        assertEquals("28.32 L", MeasureFormat.primary(Quantity(Rational.of(1728), Dimension.VOLUME), metric))
        assertEquals("0.765 m³", MeasureFormat.primary(Quantity(Rational.of(46656), Dimension.VOLUME), metric))
    }

    @Test
    fun metricConversionsShowImperial() {
        val labels = MeasureFormat.conversions(inches(12), metric).map { it.label }
        assertTrue(labels.containsAll(listOf("Feet-inches", "Inches", "Millimeters")))
    }

    @Test
    fun measures() {
        assertEquals(86.0, Measure.TEMPERATURE.fromBase(30.0, UnitSystem.IMPERIAL), 1e-9)
        assertEquals(30.0, Measure.TEMPERATURE.toBase(86.0, UnitSystem.IMPERIAL), 1e-9)
        assertEquals("86 °F", Measure.TEMPERATURE.format(30.0, UnitSystem.IMPERIAL))
        assertEquals("30 °C", Measure.TEMPERATURE.format(30.0, UnitSystem.METRIC))
        assertEquals("86 °F (30 °C)", Measure.TEMPERATURE.formatBoth(30.0, UnitSystem.IMPERIAL))
        assertEquals("30.48 m", Measure.RUN_LENGTH.format(100.0, UnitSystem.METRIC))
        assertEquals(100.0, Measure.RUN_LENGTH.toBase(30.48, UnitSystem.METRIC), 1e-9)
        assertEquals("4448 N", Measure.FORCE.format(1000.0, UnitSystem.METRIC))
        assertEquals("7.3 kN/m", Measure.SIDEWALL_PRESSURE.format(500.0, UnitSystem.METRIC))
        assertEquals("1.488 kg/m", Measure.LINEAR_WEIGHT.format(1.0, UnitSystem.METRIC))
        assertEquals("100 ft", Measure.RUN_LENGTH.formatBoth(100.0, UnitSystem.IMPERIAL).substringBefore(" ("))
        Measure.entries.forEach { m ->
            UnitSystem.entries.forEach { sys -> assertEquals("$m $sys", 12.5, m.toBase(m.fromBase(12.5, sys), sys), 1e-9) }
        }
    }

    @Test
    fun drawingStyleFeetInches() {
        val drawing = LengthFormat(feetInchStyle = FeetInchStyle.HYPHEN)
        assertEquals("10'-4 3/16\"", MeasureFormat.primary(Quantity.inches(Rational.of(124) + Rational.of(3, 16)), drawing))
        assertEquals("12'-0\"", MeasureFormat.primary(inches(144), drawing))
        assertEquals("7/16\"", MeasureFormat.primary(Quantity.inches(Rational.of(7, 16)), drawing))
        assertEquals("−2'-0\"", MeasureFormat.primary(inches(-24), drawing))
        assertEquals("10'-4-3/16\"", MeasureFormat.forClipboard("10'-4 3/16\""))
        assertEquals("10' 4 3/16\"", MeasureFormat.primary(Quantity.inches(Rational.of(124) + Rational.of(3, 16)), LengthFormat()))
        assertEquals("1'-0\"", MeasureFormat.conversions(inches(12), drawing.copy(style = LengthStyle.INCHES)).first { it.label == "Feet-inches" }.value)
    }

    @Test
    fun architecturalAndRoundingLengthFormat() {
        val arch = LengthFormat(
            feetInchStyle = FeetInchStyle.ARCHITECTURAL,
            rounding = FractionRounding.UP,
        )
        val valInches = Quantity.inches(Rational.of(63) + Rational.of(15, 32)) // 5' 3 15/32" -> rounds UP to 5' 3 1/2"
        assertEquals("5-03-08", MeasureFormat.primary(valInches, arch))
        assertEquals(1, MeasureFormat.roundingDirection(valInches, arch))
        assertEquals(0, MeasureFormat.roundingDirection(valInches, metric))

        // Positional 6-argument constructor remains backward-compatible
        val legacySixArg = LengthFormat(
            UnitSystem.IMPERIAL,
            Precision.SIXTEENTH,
            LengthStyle.FEET_INCHES,
            MetricDisplay.AUTO,
            MetricResolution.MILLIMETER,
            FeetInchStyle.ARCHITECTURAL,
        )
        assertEquals(FractionRounding.NEAREST, legacySixArg.rounding)
        assertEquals(
            "1-00-00",
            MeasureFormat.conversions(inches(12), legacySixArg.copy(style = LengthStyle.INCHES))
                .first { it.label == "Feet-inches" }.value,
        )
    }
}

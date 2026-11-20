package com.thedirepack.constructioncalc.feature.conversions.domain

import com.thedirepack.constructioncalc.core.math.Rational
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class UnitTableTest {
    private fun d(s: String) = Rational.of(BigDecimal(s))

    private fun unit(category: ConversionCategory, id: String) = UnitTable.unit(category, id).also { assertEquals(id, it.id) }

    /** 1 [from] expressed in [to], as a double. */
    private fun one(category: ConversionCategory, from: String, to: String, value: String = "1"): Double =
        Conversions.convert(d(value), unit(category, from), unit(category, to)).toDouble()

    private fun assertRel(expected: Double, actual: Double, tolerance: Double = 1e-6) {
        assertEquals(expected, actual, Math.abs(expected) * tolerance)
    }

    @Test fun everyCategoryRoundTripsExactly() {
        val values = listOf("1", "7", "123.456", "0.001", "-40")
        ConversionCategory.entries.filter { it.isTable }.forEach { category ->
            val units = UnitTable.unitsFor(category)
            assertTrue("$category has units", units.size >= 4)
            assertEquals("unique ids in $category", units.size, units.map { it.id }.toSet().size)
            assertTrue(units.any { it.id == UnitTable.defaultUnitId(category) } || category == ConversionCategory.LENGTH)
            for (a in units) for (b in units) for (v in values) {
                val start = d(v)
                val back = Conversions.convert(Conversions.convert(start, a, b), b, a)
                assertEquals("$category ${a.id}->${b.id} $v", start, back)
            }
        }
    }

    @Test fun lengthMatchesDefinitions() {
        assertEquals(d("25.4"), Conversions.convert(Rational.ONE, unit(ConversionCategory.LENGTH, "in"), unit(ConversionCategory.LENGTH, "mm")))
        assertEquals(Rational.of(5280), Conversions.convert(Rational.ONE, unit(ConversionCategory.LENGTH, "mi"), unit(ConversionCategory.LENGTH, "ft")))
        assertEquals(d("0.3048"), Conversions.convert(Rational.ONE, unit(ConversionCategory.LENGTH, "ft"), unit(ConversionCategory.LENGTH, "m")))
        assertEquals(d("0.9144"), Conversions.convert(Rational.ONE, unit(ConversionCategory.LENGTH, "yd"), unit(ConversionCategory.LENGTH, "m")))
        assertEquals(d("1609.344"), Conversions.convert(Rational.ONE, unit(ConversionCategory.LENGTH, "mi"), unit(ConversionCategory.LENGTH, "m")))
        assertEquals(Rational.of(10), Conversions.convert(Rational.ONE, unit(ConversionCategory.LENGTH, "cm"), unit(ConversionCategory.LENGTH, "mm")))
    }

    @Test fun areaMatchesDefinitions() {
        val a = ConversionCategory.AREA
        assertEquals(Rational.of(9), Conversions.convert(Rational.ONE, unit(a, "sqyd"), unit(a, "sqft")))
        assertEquals(Rational.of(43560), Conversions.convert(Rational.ONE, unit(a, "acre"), unit(a, "sqft")))
        assertEquals(d("4046.8564224"), Conversions.convert(Rational.ONE, unit(a, "acre"), unit(a, "m2")))
        assertEquals(d("0.09290304"), Conversions.convert(Rational.ONE, unit(a, "sqft"), unit(a, "m2")))
        assertEquals(Rational.of(640), Conversions.convert(Rational.ONE, unit(a, "sqmi"), unit(a, "acre")))
        assertEquals(Rational.of(10_000), Conversions.convert(Rational.ONE, unit(a, "ha"), unit(a, "m2")))
    }

    @Test fun volumeMatchesDefinitions() {
        val v = ConversionCategory.VOLUME
        assertEquals(Rational.of(27), Conversions.convert(Rational.ONE, unit(v, "cuyd"), unit(v, "cuft")))
        assertEquals(d("3.785411784"), Conversions.convert(Rational.ONE, unit(v, "gal"), unit(v, "l")))
        assertEquals(Rational.of(4), Conversions.convert(Rational.ONE, unit(v, "gal"), unit(v, "qt")))
        assertEquals(Rational.of(128), Conversions.convert(Rational.ONE, unit(v, "gal"), unit(v, "floz")))
        assertEquals(Rational.of(1000), Conversions.convert(Rational.ONE, unit(v, "l"), unit(v, "ml")))
        assertEquals(Rational.of(1000), Conversions.convert(Rational.ONE, unit(v, "m3"), unit(v, "l")))
        // 1 cu ft = 28.316846592 L, 1 cu yd = 0.764554857984 m³ (NIST SP 811)
        assertEquals(d("28.316846592"), Conversions.convert(Rational.ONE, unit(v, "cuft"), unit(v, "l")))
        assertEquals(d("0.764554857984"), Conversions.convert(Rational.ONE, unit(v, "cuyd"), unit(v, "m3")))
    }

    @Test fun weightMatchesDefinitions() {
        val w = ConversionCategory.WEIGHT
        assertEquals(d("0.45359237"), Conversions.convert(Rational.ONE, unit(w, "lb"), unit(w, "kg")))
        assertEquals(Rational.of(16), Conversions.convert(Rational.ONE, unit(w, "lb"), unit(w, "oz")))
        assertEquals(Rational.of(2000), Conversions.convert(Rational.ONE, unit(w, "ton"), unit(w, "lb")))
        assertEquals(Rational.of(1000), Conversions.convert(Rational.ONE, unit(w, "t"), unit(w, "kg")))
        assertEquals(d("453.59237"), Conversions.convert(Rational.ONE, unit(w, "lb"), unit(w, "g")))
        assertRel(2.20462262, one(w, "kg", "lb"))
    }

    @Test fun temperature() {
        val t = ConversionCategory.TEMPERATURE
        fun conv(v: String, from: String, to: String) = Conversions.convert(d(v), unit(t, from), unit(t, to))
        assertEquals(Rational.of(32), conv("0", "c", "f"))
        assertEquals(Rational.of(212), conv("100", "c", "f"))
        assertEquals(Rational.of(-40), conv("-40", "c", "f"))
        assertEquals(Rational.of(-40), conv("-40", "f", "c"))
        assertEquals(Rational.of(68), conv("20", "c", "f"))
        assertEquals(d("37"), conv("98.6", "f", "c"))
        assertEquals(d("273.15"), conv("0", "c", "k"))
        assertEquals(d("-459.67"), conv("0", "k", "f").let { it - Rational.ZERO })
        assertEquals(d("459.67"), conv("0", "f", "r"))
        assertEquals(d("491.67"), conv("32", "f", "r"))
        assertEquals(d("255.3722222222"), conv("0", "f", "k").let { Rational.of(it.toBigDecimal(10)) })
    }

    @Test fun absoluteZero() {
        val t = ConversionCategory.TEMPERATURE
        val belowKelvin = UnitTable.unit(t, "k").toBase(d("-1"))
        assertTrue(Conversions.belowAbsoluteZero(belowKelvin))
        assertTrue(!Conversions.belowAbsoluteZero(UnitTable.unit(t, "k").toBase(Rational.ZERO)))
    }

    @Test fun pressure() {
        val p = ConversionCategory.PRESSURE
        assertRel(6.894757293168361, one(p, "psi", "kpa"), 1e-12)
        assertRel(14.5037737797, one(p, "bar", "psi"), 1e-9)
        assertRel(14.6959487755, one(p, "atm", "psi"), 1e-9)
        assertEquals(Rational.of(100), Conversions.convert(Rational.ONE, unit(p, "bar"), unit(p, "kpa")))
        // 60 °F water: 27.708 inH2O and 2.309 ft of head per psi (1 ft of head = 0.4331 psi)
        assertRel(27.7076, one(p, "psi", "inh2o"), 1e-4)
        assertRel(2.30897, one(p, "psi", "fth2o"), 1e-4)
        assertRel(0.43309, one(p, "fth2o", "psi"), 1e-4)
        assertEquals(Rational.of(12), Conversions.convert(Rational.ONE, unit(p, "fth2o"), unit(p, "inh2o")))
        assertRel(2.0418, one(p, "psi", "inhg"), 1e-4)
    }

    @Test fun power() {
        val p = ConversionCategory.POWER
        assertEquals(d("745.69987158227022"), Conversions.convert(Rational.ONE, unit(p, "hp"), unit(p, "w")))
        assertRel(3.412141633, one(p, "w", "btuh"), 1e-8)
        assertRel(2544.4336, one(p, "hp", "btuh"), 1e-6)
        assertRel(3516.8528420667, one(p, "tr", "w"), 1e-12)
        assertEquals(Rational.of(12_000), Conversions.convert(Rational.ONE, unit(p, "tr"), unit(p, "btuh")))
        assertEquals(Rational.of(12), Conversions.convert(Rational.ONE, unit(p, "tr"), unit(p, "mbh")))
        assertEquals(Rational.of(1000), Conversions.convert(Rational.ONE, unit(p, "kw"), unit(p, "w")))
        assertRel(1.34102209, one(p, "kw", "hp"), 1e-7)
    }

    @Test fun energy() {
        val e = ConversionCategory.ENERGY
        assertEquals(Rational.of(3_600_000), Conversions.convert(Rational.ONE, unit(e, "kwh"), unit(e, "j")))
        assertRel(3412.14163, one(e, "kwh", "btu"), 1e-8)
        assertEquals(Rational.of(4184), Conversions.convert(Rational.ONE, unit(e, "kcal"), unit(e, "j")))
        assertRel(1.3558179483314, one(e, "ftlbf", "j"), 1e-12)
        assertRel(100_000.0, one(e, "therm", "btu"), 5e-4) // 1 therm ≈ 100,000 BTU
    }

    @Test fun speed() {
        val s = ConversionCategory.SPEED
        assertEquals(d("1.609344"), Conversions.convert(Rational.ONE, unit(s, "mph"), unit(s, "kmh")))
        assertEquals(d("1.852"), Conversions.convert(Rational.ONE, unit(s, "kn"), unit(s, "kmh")))
        assertEquals(Rational.of(88, 60), Conversions.convert(Rational.ONE, unit(s, "mph"), unit(s, "fps")))
        assertEquals(Rational.of(60), Conversions.convert(Rational.ONE, unit(s, "fps"), unit(s, "fpm")))
        assertRel(0.44704, one(s, "mph", "mps"), 1e-12)
    }

    @Test fun flow() {
        val f = ConversionCategory.FLOW
        assertRel(3.785411784, one(f, "gpm", "lpm"), 1e-12)
        assertRel(0.0630901964, one(f, "gpm", "lps"), 1e-9)
        assertRel(7.480519481, one(f, "cfm", "gpm"), 1e-9)
        assertEquals(Rational.of(60), Conversions.convert(Rational.ONE, unit(f, "gpm"), unit(f, "gph")))
        assertRel(0.2271247, one(f, "gpm", "m3h"), 1e-6)
        assertEquals(Rational.of(60), Conversions.convert(Rational.ONE, unit(f, "cfs"), unit(f, "cfm")))
    }

    @Test fun angle() {
        val a = ConversionCategory.ANGLE
        assertRel(Math.PI, one(a, "deg", "rad", "180"), 1e-15)
        assertRel(57.29577951308232, one(a, "rad", "deg"), 1e-15)
        assertEquals(Rational.of(90), Conversions.convert(Rational.of(100), unit(a, "gon"), unit(a, "deg")))
        assertEquals(Rational.of(60), Conversions.convert(Rational.ONE, unit(a, "deg"), unit(a, "arcmin")))
        assertEquals(Rational.of(3600), Conversions.convert(Rational.ONE, unit(a, "deg"), unit(a, "arcsec")))
        assertEquals(Rational.of(360), Conversions.convert(Rational.ONE, unit(a, "turn"), unit(a, "deg")))
    }

    @Test fun resistancePerLength() {
        val r = ConversionCategory.RESISTANCE_LENGTH
        // 12 AWG copper: 1.93 Ω/1000 ft ≈ 6.332 Ω/km (NEC Chapter 9 Table 8)
        assertRel(6.33202, one(r, "okft", "okm", "1.93"), 1e-5)
        assertEquals(d("0.3048"), Conversions.convert(Rational.ONE, unit(r, "okm"), unit(r, "okft")))
        assertEquals(Rational.of(10), Conversions.convert(Rational.ONE, unit(r, "o100ft"), unit(r, "okft")))
        assertRel(5.28, one(r, "okft", "omi"), 1e-12)
    }

    @Test fun convertAllListsEveryUnitInOrder() {
        val rows = Conversions.convertAll(ConversionCategory.LENGTH, Rational.ONE, unit(ConversionCategory.LENGTH, "ft"))
        assertEquals(UnitTable.unitsFor(ConversionCategory.LENGTH).map { it.id }, rows.map { it.unit.id })
        assertEquals(Rational.of(12), rows.first { it.unit.id == "in" }.value)
        assertEquals(Rational.of(1, 3), rows.first { it.unit.id == "yd" }.value)
    }

    @Test fun unknownIdFallsBackToDefault() {
        assertEquals("gal", UnitTable.unit(ConversionCategory.VOLUME, "nonsense").id)
    }

    @Test fun numberText() {
        assertEquals("0", NumberText.significant(Rational.ZERO))
        assertEquals("63360", NumberText.significant(Rational.of(63360)))
        assertEquals("25.4", NumberText.significant(d("25.4")))
        assertEquals("0.03937008", NumberText.significant(Rational.ONE / d("25.4")))
        assertEquals("−40", NumberText.significant(Rational.of(-40)))
        assertEquals("3.309", NumberText.significant(d("3.30871"), 4))
        assertEquals("6.213712E-7", NumberText.significant(Rational.ONE / d("1609344")))
        assertEquals("1.234568E+12", NumberText.significant(d("1234567890123")))
    }
}

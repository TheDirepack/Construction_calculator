package com.thedirepack.constructioncalc.feature.conversions.domain

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import java.math.BigDecimal

/**
 * One unit: `base = value × factor + offset`, where base is the category's base unit
 * (the unit published to other tools: inches, sq in, cu in, lb, °C, psi, W, gpm, degrees; °C for temperature;
 * joules, m/s and Ω per 1000 ft for the number-only categories).
 *
 * Only temperature has a non-zero [offset] (it is affine; see [UnitTable]).
 */
data class UnitDef(
    val id: String,
    val symbol: String,
    val name: String,
    val factor: Rational,
    val offset: Rational = Rational.ZERO,
) {
    fun toBase(value: Rational): Rational = value * factor + offset

    fun fromBase(base: Rational): Rational = (base - offset) / factor
}

/**
 * The one table of conversion factors, kept as exact [Rational]s. Definitions come from
 * NIST Special Publication 811 (2008), "Guide for the Use of the International System of Units",
 * Appendix B (conversion factors) and Appendix A:
 *
 * - 1 in = 25.4 mm exactly (so 1 ft = 0.3048 m, 1 yd = 0.9144 m, 1 mi = 5280 ft).
 * - 1 lb = 0.45359237 kg exactly (avoirdupois pound).
 * - 1 US gallon = 231 in³ exactly (= 3.785411784 L); 1 qt = ¼ gal; 1 fl oz = 1/128 gal;
 *   1 L = 1 dm³; 1 mL = 1 cm³.
 * - 1 lbf = 4.4482216152605 N exactly (pound × standard gravity 9.80665 m/s²), so
 *   1 psi = lbf/in² = 6894.757293168361… Pa and 1 ft·lbf = 1.3558179483314… J.
 * - 1 hp (mechanical) = 550 ft·lbf/s = 745.69987158227022 W.
 * - 1 BTU (International Table) = 1055.05585262 J exactly; 1 BTU/h = that ÷ 3600 W;
 *   1 ton of refrigeration = 12 000 BTU/h (≈ 3516.853 W); 1 MBH = 1000 BTU/h.
 * - 1 bar = 100 kPa; 1 atm = 101 325 Pa. Water column and mercury use NIST's 60 °F values:
 *   1 inH₂O = 248.84 Pa, 1 inHg = 3376.85 Pa (these two are defined by measurement, so only
 *   as exact as NIST's rounded figures). "Feet of head" is 12 inH₂O of water at 60 °F.
 * - 1 acre = 43 560 sq ft with the international foot (the US-survey acre differs by 2 ppm).
 * - 1 kcal = 4184 J (thermochemical); 1 therm (US) = 105 480 400 J; 1 Wh = 3600 J.
 * - 1 mph = 1609.344 m ÷ 3600 s = 0.44704 m/s exactly; 1 knot = 1852 m/h.
 * - 1 radian = 180/π degrees. π is irrational, so the radian factor carries 30 digits.
 * - Temperature: °C = (°F − 32) × 5/9 and K = °C + 273.15, °R = °F + 459.67. The scales differ
 *   by an offset as well as a ratio, so each temperature unit has an [UnitDef.offset] and converts
 *   through °C (the base, as the NEC tables use).
 * - Resistance per length: 1 Ω/km = 0.3048 Ω/1000 ft (exact); 1 mi = 5.28 × 1000 ft.
 */
object UnitTable {
    /** The "Feet & inches (calculator)" entry in Length's from-unit list; the value comes from a MeasureField. */
    const val CALCULATOR_ID = "ftin"

    private fun d(text: String) = Rational.of(BigDecimal(text))
    private fun n(value: Long) = Rational.of(value)
    private fun u(id: String, symbol: String, name: String, factor: Rational) = UnitDef(id, symbol, name, factor)

    private val MM_PER_IN = d("25.4")
    private val MM_PER_IN_SQ = MM_PER_IN * MM_PER_IN
    private val MM_PER_IN_CU = MM_PER_IN_SQ * MM_PER_IN
    private val M_PER_IN = d("0.0254")
    private val M_PER_FT = d("0.3048")
    private val NEWTON_PER_LBF = d("4.4482216152605")
    private val KG_PER_LB = d("0.45359237")
    private val JOULE_PER_BTU = d("1055.05585262")
    private val JOULE_PER_FT_LBF = M_PER_FT * NEWTON_PER_LBF
    private val PA_PER_PSI = NEWTON_PER_LBF / (M_PER_IN * M_PER_IN)
    private val CU_IN_PER_GAL = n(231)
    private val CU_IN_PER_LITER = n(1_000_000) / MM_PER_IN_CU
    private val SQ_IN_PER_ACRE = n(43_560 * 144L)
    private val WATT_PER_BTU_H = JOULE_PER_BTU / n(3600)
    private val PA_PER_IN_H2O = d("248.84")
    private val DEG_PER_RADIAN = d("57.295779513082320876798154814105")
    private val CU_IN_PER_CU_FT = n(1728)

    private fun pa(pascals: Rational) = pascals / PA_PER_PSI

    private val length = listOf(
        u("in", "in", "Inches", Rational.ONE),
        u("ft", "ft", "Feet", n(12)),
        u("yd", "yd", "Yards", n(36)),
        u("mi", "mi", "Miles", n(63_360)),
        u("mm", "mm", "Millimeters", Rational.ONE / MM_PER_IN),
        u("cm", "cm", "Centimeters", n(10) / MM_PER_IN),
        u("m", "m", "Meters", n(1000) / MM_PER_IN),
        u("km", "km", "Kilometers", n(1_000_000) / MM_PER_IN),
    )

    private val area = listOf(
        u("sqin", "sq in", "Square inches", Rational.ONE),
        u("sqft", "sq ft", "Square feet", n(144)),
        u("sqyd", "sq yd", "Square yards", n(1296)),
        u("acre", "acre", "Acres", SQ_IN_PER_ACRE),
        u("sqmi", "sq mi", "Square miles", SQ_IN_PER_ACRE * n(640)),
        u("mm2", "mm²", "Square millimeters", Rational.ONE / MM_PER_IN_SQ),
        u("cm2", "cm²", "Square centimeters", n(100) / MM_PER_IN_SQ),
        u("m2", "m²", "Square meters", n(1_000_000) / MM_PER_IN_SQ),
        u("ha", "ha", "Hectares", n(10_000_000_000) / MM_PER_IN_SQ),
    )

    private val volume = listOf(
        u("cuin", "cu in", "Cubic inches", Rational.ONE),
        u("cuft", "cu ft", "Cubic feet", CU_IN_PER_CU_FT),
        u("cuyd", "cu yd", "Cubic yards", n(46_656)),
        u("gal", "gal", "US gallons", CU_IN_PER_GAL),
        u("qt", "qt", "US quarts", CU_IN_PER_GAL / n(4)),
        u("floz", "fl oz", "US fluid ounces", CU_IN_PER_GAL / n(128)),
        u("ml", "mL", "Milliliters", CU_IN_PER_LITER / n(1000)),
        u("l", "L", "Liters", CU_IN_PER_LITER),
        u("m3", "m³", "Cubic meters", CU_IN_PER_LITER * n(1000)),
    )

    private val weight = listOf(
        u("oz", "oz", "Ounces", Rational.ONE / n(16)),
        u("lb", "lb", "Pounds", Rational.ONE),
        u("ton", "ton", "Short tons (2000 lb)", n(2000)),
        u("g", "g", "Grams", Rational.ONE / (KG_PER_LB * n(1000))),
        u("kg", "kg", "Kilograms", Rational.ONE / KG_PER_LB),
        u("t", "t", "Metric tons", n(1000) / KG_PER_LB),
    )

    private val temperature = listOf(
        // Base is °C (matching ValueKind.TEMPERATURE): °C = value × factor + offset.
        UnitDef("f", "°F", "Fahrenheit", Rational.of(5, 9), offset = Rational.of(-160, 9)),
        UnitDef("c", "°C", "Celsius", Rational.ONE),
        UnitDef("k", "K", "Kelvin", Rational.ONE, offset = -d("273.15")),
        UnitDef("r", "°R", "Rankine", Rational.of(5, 9), offset = -d("273.15")),
    )

    private val pressure = listOf(
        u("psi", "psi", "Pounds per sq in", Rational.ONE),
        u("kpa", "kPa", "Kilopascals", pa(n(1000))),
        u("bar", "bar", "Bar", pa(n(100_000))),
        u("atm", "atm", "Atmospheres", pa(n(101_325))),
        u("inh2o", "inH₂O", "Inches of water (60 °F)", pa(PA_PER_IN_H2O)),
        u("fth2o", "ft head", "Feet of head (water, 60 °F)", pa(PA_PER_IN_H2O * n(12))),
        u("inhg", "inHg", "Inches of mercury (60 °F)", pa(d("3376.85"))),
    )

    private val power = listOf(
        u("w", "W", "Watts", Rational.ONE),
        u("kw", "kW", "Kilowatts", n(1000)),
        u("hp", "hp", "Horsepower (mechanical)", n(550) * JOULE_PER_FT_LBF),
        u("btuh", "BTU/h", "BTU per hour", WATT_PER_BTU_H),
        u("mbh", "MBH", "Thousand BTU per hour", WATT_PER_BTU_H * n(1000)),
        u("tr", "TR", "Tons of refrigeration", WATT_PER_BTU_H * n(12_000)),
    )

    private val energy = listOf(
        u("j", "J", "Joules", Rational.ONE),
        u("kj", "kJ", "Kilojoules", n(1000)),
        u("wh", "Wh", "Watt-hours", n(3600)),
        u("kwh", "kWh", "Kilowatt-hours", n(3_600_000)),
        u("btu", "BTU", "BTU (International Table)", JOULE_PER_BTU),
        u("kcal", "kcal", "Kilocalories", n(4184)),
        u("ftlbf", "ft·lbf", "Foot-pounds", JOULE_PER_FT_LBF),
        u("therm", "therm", "Therms (US)", n(105_480_400)),
    )

    private val speed = listOf(
        u("fps", "ft/s", "Feet per second", M_PER_FT),
        u("fpm", "ft/min", "Feet per minute", M_PER_FT / n(60)),
        u("mph", "mph", "Miles per hour", d("0.44704")),
        u("kmh", "km/h", "Kilometers per hour", Rational.of(5, 18)),
        u("mps", "m/s", "Meters per second", Rational.ONE),
        u("kn", "kn", "Knots", Rational.of(463, 900)),
    )

    private val flow = listOf(
        u("gpm", "gpm", "US gallons per minute", Rational.ONE),
        u("gph", "gph", "US gallons per hour", Rational.ONE / n(60)),
        u("lpm", "L/min", "Liters per minute", CU_IN_PER_LITER / CU_IN_PER_GAL),
        u("lps", "L/s", "Liters per second", CU_IN_PER_LITER / CU_IN_PER_GAL * n(60)),
        u("m3h", "m³/h", "Cubic meters per hour", CU_IN_PER_LITER * n(1000) / CU_IN_PER_GAL / n(60)),
        u("cfm", "cfm", "Cubic feet per minute", CU_IN_PER_CU_FT / CU_IN_PER_GAL),
        u("cfs", "cfs", "Cubic feet per second", CU_IN_PER_CU_FT / CU_IN_PER_GAL * n(60)),
    )

    private val angle = listOf(
        u("deg", "°", "Degrees", Rational.ONE),
        u("rad", "rad", "Radians", DEG_PER_RADIAN),
        u("gon", "gon", "Gradians", Rational.of(9, 10)),
        u("arcmin", "′", "Arcminutes", Rational.of(1, 60)),
        u("arcsec", "″", "Arcseconds", Rational.of(1, 3600)),
        u("turn", "rev", "Revolutions", n(360)),
    )

    /** Base unit is Ω per 1000 ft. 1 Ω/km is 0.3048 Ω/1000 ft; 1 Ω/mi is 1/5.28 Ω/1000 ft. */
    private val resistanceLength = listOf(
        u("okft", "Ω/1000 ft", "Ohms per 1000 ft", Rational.ONE),
        u("o100ft", "Ω/100 ft", "Ohms per 100 ft", n(10)),
        u("okm", "Ω/km", "Ohms per kilometer", M_PER_FT),
        u("omi", "Ω/mi", "Ohms per mile", Rational.ONE / d("5.28")),
    )

    /** The units of a table category, in display order. Empty for the wire-size and current/power calculators. */
    fun unitsFor(category: ConversionCategory): List<UnitDef> = when (category) {
        ConversionCategory.LENGTH -> length
        ConversionCategory.AREA -> area
        ConversionCategory.VOLUME -> volume
        ConversionCategory.WEIGHT -> weight
        ConversionCategory.TEMPERATURE -> temperature
        ConversionCategory.PRESSURE -> pressure
        ConversionCategory.POWER -> power
        ConversionCategory.ENERGY -> energy
        ConversionCategory.SPEED -> speed
        ConversionCategory.FLOW -> flow
        ConversionCategory.ANGLE -> angle
        ConversionCategory.RESISTANCE_LENGTH -> resistanceLength
        ConversionCategory.WIRE_SIZE -> emptyList()
    }

    /** Id of the unit the category starts on. */
    fun defaultUnitId(category: ConversionCategory, system: UnitSystem = UnitSystem.IMPERIAL): String =
        if (system == UnitSystem.METRIC) metricDefaultUnitId(category) ?: defaultUnitId(category) else defaultUnitId(category)

    /** The metric unit a category starts on when the app is set to Metric, or null to keep the imperial default. */
    private fun metricDefaultUnitId(category: ConversionCategory): String? = when (category) {
        ConversionCategory.AREA -> "m2"
        ConversionCategory.VOLUME -> "l"
        ConversionCategory.WEIGHT -> "kg"
        ConversionCategory.TEMPERATURE -> "c"
        ConversionCategory.PRESSURE -> "kpa"
        ConversionCategory.SPEED -> "kmh"
        ConversionCategory.FLOW -> "lpm"
        else -> null
    }

    private fun defaultUnitId(category: ConversionCategory): String = when (category) {
        ConversionCategory.LENGTH -> CALCULATOR_ID
        ConversionCategory.AREA -> "sqft"
        ConversionCategory.VOLUME -> "gal"
        ConversionCategory.WEIGHT -> "lb"
        ConversionCategory.TEMPERATURE -> "f"
        ConversionCategory.PRESSURE -> "psi"
        ConversionCategory.POWER -> "kw"
        ConversionCategory.ENERGY -> "kwh"
        ConversionCategory.SPEED -> "mph"
        ConversionCategory.FLOW -> "gpm"
        ConversionCategory.ANGLE -> "deg"
        ConversionCategory.RESISTANCE_LENGTH -> "okft"
        ConversionCategory.WIRE_SIZE -> ""
    }

    /**
     * The unit with this [id] in a table [category]. Unknown ids (and the calculator entry, which
     * measures in inches) fall back to the category's first unit for length, else its default unit.
     */
    fun unit(category: ConversionCategory, id: String): UnitDef {
        val units = unitsFor(category)
        require(units.isNotEmpty()) { "$category has no unit table" }
        return units.firstOrNull { it.id == id }
            ?: units.firstOrNull { it.id == defaultUnitId(category) }
            ?: units.first()
    }
}

package com.thedirepack.constructioncalc.core.measure

import com.thedirepack.constructioncalc.core.math.Rational
import java.math.BigInteger

/** The smallest fraction results are rounded to. */
enum class Precision(val denominator: Int) {
    HALF(2),
    QUARTER(4),
    EIGHTH(8),
    SIXTEENTH(16),
    THIRTY_SECOND(32),
    SIXTY_FOURTH(64);

    val label get() = "1/$denominator"

    companion object {
        val DEFAULT = SIXTEENTH
        fun fromDenominator(d: Int) = entries.firstOrNull { it.denominator == d } ?: DEFAULT
    }
}

/** How lengths are shown as the main result. */
enum class LengthStyle { FEET_INCHES, INCHES }

/** A length broken into feet, whole inches and a reduced fraction, after rounding. */
data class FeetInches(
    val negative: Boolean,
    val feet: BigInteger,
    val inches: BigInteger,
    val fractionNumerator: Int,
    val fractionDenominator: Int,
    /** False when rounding to the precision changed the value. */
    val exact: Boolean,
) {
    val hasFraction get() = fractionNumerator != 0
}

object MeasureFormat {

    fun splitInches(totalInches: Rational, precision: Precision, style: LengthStyle): FeetInches {
        val den = precision.denominator
        val steps = totalInches.roundToSteps(den.toLong())
        val exact = Rational.of(steps, BigInteger.valueOf(den.toLong())) == totalInches
        val negative = steps.signum() < 0
        val abs = steps.abs()
        val stepsPerFoot = BigInteger.valueOf(12L * den)
        val feet = if (style == LengthStyle.FEET_INCHES) abs / stepsPerFoot else BigInteger.ZERO
        val remainder = if (style == LengthStyle.FEET_INCHES) abs % stepsPerFoot else abs
        val denBig = BigInteger.valueOf(den.toLong())
        val inches = remainder / denBig
        var fracNum = (remainder % denBig).toInt()
        var fracDen = den
        if (fracNum != 0) {
            val g = gcd(fracNum, fracDen)
            fracNum /= g
            fracDen /= g
        }
        return FeetInches(negative, feet, inches, fracNum, fracDen, exact)
    }

    /**
     * Plain-text feet-inch string, e.g. `10' 4 3/16"`, `7/16"`, `−2' 0"`.
     * Fractions are written `n/d`; the UI typesets them.
     */
    fun feetInches(fi: FeetInches): String = buildString {
        if (fi.negative) append('−')
        val hasFeet = fi.feet.signum() != 0
        if (hasFeet) append(fi.feet).append("' ")
        // Always show whole inches after feet (`1' 0 1/2"`), and alone when there's no fraction.
        val showWhole = hasFeet || fi.inches.signum() != 0 || !fi.hasFraction
        if (showWhole) append(fi.inches)
        if (fi.hasFraction) {
            if (showWhole) append(' ')
            append(fi.fractionNumerator).append('/').append(fi.fractionDenominator)
        }
        append('"')
    }

    fun length(inches: Rational, precision: Precision, style: LengthStyle) =
        feetInches(splitInches(inches, precision, style))

    /** Exact-to-[maxDecimals] decimal with trailing zeros removed. */
    fun decimal(value: Rational, maxDecimals: Int): String {
        val text = value.toBigDecimal(maxDecimals).stripTrailingZeros().toPlainString()
        val clean = if (text == "-0") "0" else text
        return clean.replace('-', '−')
    }

    /** A plain number as a decimal (its fraction form is listed under [conversions]). */
    fun number(value: Rational): String = decimal(value, if (value.isInteger) 0 else 6)

    /** The main, large result string for a quantity. */
    fun primary(q: Quantity, precision: Precision, style: LengthStyle): String = when (q.dimension) {
        Dimension.NUMBER -> number(q.value)
        Dimension.LENGTH -> length(q.value, precision, style)
        Dimension.AREA -> decimal(q.value / SQ_IN_PER_SQ_FT, 4) + " sq ft"
        Dimension.VOLUME -> decimal(q.value / CU_IN_PER_CU_FT, 4) + " cu ft"
    }

    /** The main result in either system, per [format]. */
    fun primary(q: Quantity, format: LengthFormat): String {
        if (!format.isMetric) return primary(q, format.precision, format.style)
        return when (q.dimension) {
            Dimension.NUMBER -> number(q.value)
            Dimension.LENGTH -> MetricFormat.length(q.value, format.metricDisplay, format.metricResolution)
            Dimension.AREA -> MetricFormat.area(q.value)
            Dimension.VOLUME -> MetricFormat.volume(q.value)
        }
    }

    /** Secondary readouts in either system: in metric, the imperial equivalents (and the other way round). */
    fun conversions(q: Quantity, format: LengthFormat): List<Conversion> {
        if (!format.isMetric) return conversions(q, format.precision, format.style)
        val v = q.value
        return when (q.dimension) {
            Dimension.NUMBER -> conversions(q, format.precision, format.style)
            Dimension.LENGTH -> listOf(
                Conversion("Millimeters", decimal(v * Units.MM_PER_INCH, 1) + " mm"),
                Conversion("Meters", decimal(v * Units.MM_PER_INCH / THOUSAND, 4) + " m"),
                Conversion("Feet-inches", length(v, format.precision, LengthStyle.FEET_INCHES)),
                Conversion("Inches", length(v, format.precision, LengthStyle.INCHES)),
                Conversion("Decimal ft", decimal(v / Units.INCHES_PER_FOOT, 4) + " ft"),
            )
            Dimension.AREA -> listOf(
                Conversion("Square meters", decimal(v * MM2_PER_IN2 / MILLION, 4) + " m²"),
                Conversion("Square feet", decimal(v / SQ_IN_PER_SQ_FT, 4) + " sq ft"),
                Conversion("Square inches", decimal(v, 2) + " sq in"),
            )
            Dimension.VOLUME -> listOf(
                Conversion("Liters", decimal(v * MM2_PER_IN2 * Units.MM_PER_INCH / MILLION, 3) + " L"),
                Conversion("Cubic feet", decimal(v / CU_IN_PER_CU_FT, 4) + " cu ft"),
                Conversion("Cubic yards", decimal(v / CU_IN_PER_CU_YD, 4) + " cu yd"),
                Conversion("US gallons", decimal(v / Units.CUBIC_INCHES_PER_US_GALLON, 3) + " gal"),
            )
        }
    }

    /** Whether [primary] had to round a length. */
    fun isRounded(q: Quantity, precision: Precision): Boolean =
        q.dimension == Dimension.LENGTH &&
            !splitInches(q.value, precision, LengthStyle.INCHES).exact

    /**
     * How the displayed length compares with the exact value: -1 if it was rounded down
     * (shown smaller than actual), 1 if rounded up, 0 if exact or not a length.
     */
    fun roundingDirection(q: Quantity, precision: Precision): Int {
        if (q.dimension != Dimension.LENGTH) return 0
        val steps = q.value.roundToSteps(precision.denominator.toLong())
        val shown = Rational.of(steps, BigInteger.valueOf(precision.denominator.toLong()))
        return shown.compareTo(q.value)
    }

    data class Conversion(val label: String, val value: String)

    /** Secondary readouts shown under the main result. */
    fun conversions(q: Quantity, precision: Precision, style: LengthStyle): List<Conversion> {
        val v = q.value
        return when (q.dimension) {
            Dimension.NUMBER -> buildList {
                if (!v.isInteger) {
                    val fi = splitInches(v, precision, LengthStyle.INCHES)
                    val asFraction = feetInches(fi).removeSuffix("\"")
                    add(Conversion("Fraction", (if (fi.exact) "" else "≈ ") + asFraction))
                }
            }
            Dimension.LENGTH -> buildList {
                if (style == LengthStyle.FEET_INCHES) add(Conversion("Inches", length(v, precision, LengthStyle.INCHES)))
                else add(Conversion("Feet-inches", length(v, precision, LengthStyle.FEET_INCHES)))
                add(Conversion("Decimal ft", decimal(v / Units.INCHES_PER_FOOT, 4) + " ft"))
                add(Conversion("Decimal in", decimal(v, 4) + " in"))
                add(Conversion("Millimeters", decimal(v * Units.MM_PER_INCH, 1) + " mm"))
                add(Conversion("Meters", decimal(v * Units.MM_PER_INCH / THOUSAND, 4) + " m"))
            }
            Dimension.AREA -> listOf(
                Conversion("Square inches", decimal(v, 2) + " sq in"),
                Conversion("Square yards", decimal(v / SQ_IN_PER_SQ_YD, 4) + " sq yd"),
                Conversion("Square meters", decimal(v * MM2_PER_IN2 / MILLION, 4) + " m²"),
            )
            Dimension.VOLUME -> listOf(
                Conversion("Cubic yards", decimal(v / CU_IN_PER_CU_YD, 4) + " cu yd"),
                Conversion("Cubic inches", decimal(v, 2) + " cu in"),
                Conversion("US gallons", decimal(v / Units.CUBIC_INCHES_PER_US_GALLON, 3) + " gal"),
                Conversion("Cubic meters", decimal(v * MM2_PER_IN2 * Units.MM_PER_INCH / BILLION, 4) + " m³"),
            )
        }
    }

    /** Plain text for the clipboard: ASCII minus and a hyphenated fraction, e.g. `10' 4-3/16"`. */
    fun forClipboard(text: String): String =
        text.replace('−', '-').replace(WHOLE_AND_FRACTION, "$1-$2")

    private val WHOLE_AND_FRACTION = Regex("""(\d) (\d+/\d+)""")
    private val SQ_IN_PER_SQ_FT = Units.INCHES_PER_FOOT * Units.INCHES_PER_FOOT
    private val SQ_IN_PER_SQ_YD = Units.INCHES_PER_YARD * Units.INCHES_PER_YARD
    private val CU_IN_PER_CU_FT = SQ_IN_PER_SQ_FT * Units.INCHES_PER_FOOT
    private val CU_IN_PER_CU_YD = SQ_IN_PER_SQ_YD * Units.INCHES_PER_YARD
    private val MM2_PER_IN2 = Units.MM_PER_INCH * Units.MM_PER_INCH
    private val THOUSAND = Rational.of(1_000)
    private val MILLION = Rational.of(1_000_000)
    private val BILLION = Rational.of(1_000_000_000)

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}

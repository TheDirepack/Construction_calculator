package com.thedirepack.constructioncalc.core.measure

import com.thedirepack.constructioncalc.core.math.Rational
import java.math.RoundingMode

/** Imperial or metric: what every tool shows and expects by default. Stored values never change. */
enum class UnitSystem(val label: String) {
    IMPERIAL("Imperial"),
    METRIC("Metric"),
}

/** A metric length unit, in whole millimeters. */
enum class MetricLengthUnit(val symbol: String, val millimeters: Int) {
    MILLIMETERS("mm", 1),
    CENTIMETERS("cm", 10),
    METERS("m", 1000);

    companion object {
        fun fromSymbol(symbol: String): MetricLengthUnit? = entries.firstOrNull { it.symbol == symbol }
    }
}

/** Which unit metric lengths are shown in. */
enum class MetricDisplay(val label: String) {
    AUTO("Auto (mm, m from 1 m)"),
    MILLIMETERS("mm"),
    CENTIMETERS("cm"),
    METERS("m"),
}

/** What metric lengths are rounded to, in tenths of a millimeter. */
enum class MetricResolution(val label: String, val tenthsOfMm: Int) {
    MILLIMETER("1 mm", 10),
    HALF_MILLIMETER("0.5 mm", 5),
    TENTH_MILLIMETER("0.1 mm", 1),
}

/**
 * Everything needed to show a length the way the user chose, in either system. Imperial uses
 * [precision] and [style]; metric uses [metricDisplay] and [metricResolution].
 */
data class LengthFormat(
    val system: UnitSystem = UnitSystem.IMPERIAL,
    val precision: Precision = Precision.DEFAULT,
    val style: LengthStyle = LengthStyle.FEET_INCHES,
    val metricDisplay: MetricDisplay = MetricDisplay.AUTO,
    val metricResolution: MetricResolution = MetricResolution.MILLIMETER,
) {
    val isMetric: Boolean get() = system == UnitSystem.METRIC

    companion object {
        fun imperial(precision: Precision, style: LengthStyle) = LengthFormat(UnitSystem.IMPERIAL, precision, style)
    }
}

/** Metric display of exact inch-based quantities (1 in = 25.4 mm exactly). */
object MetricFormat {
    private val SQ_MM_PER_SQ_M = Rational.of(1_000_000)
    private val CU_MM_PER_LITER = Rational.of(1_000_000)
    private val LITERS_PER_CU_M = Rational.of(1000)

    fun millimeters(inches: Rational): Rational = inches * Units.MM_PER_INCH

    /** The unit [display] picks for a length of [mm]. */
    fun unitFor(mm: Rational, display: MetricDisplay): MetricLengthUnit = when (display) {
        MetricDisplay.MILLIMETERS -> MetricLengthUnit.MILLIMETERS
        MetricDisplay.CENTIMETERS -> MetricLengthUnit.CENTIMETERS
        MetricDisplay.METERS -> MetricLengthUnit.METERS
        MetricDisplay.AUTO -> if (mm.abs() < Rational.of(1000)) MetricLengthUnit.MILLIMETERS else MetricLengthUnit.METERS
    }

    /** [inches] rounded to [resolution] and shown in the unit [display] picks: `1250 mm`, `3.048 m`. */
    fun length(inches: Rational, display: MetricDisplay = MetricDisplay.AUTO, resolution: MetricResolution = MetricResolution.MILLIMETER): String {
        val stepsPerMm = 10L / resolution.tenthsOfMm
        val mm = Rational.of(millimeters(inches).roundToSteps(stepsPerMm), java.math.BigInteger.valueOf(stepsPerMm))
        val unit = unitFor(mm, display)
        val value = mm / Rational.of(unit.millimeters.toLong())
        val mmDecimals = if (resolution == MetricResolution.MILLIMETER) 0 else 1
        val decimals = mmDecimals + when (unit) {
            MetricLengthUnit.MILLIMETERS -> 0
            MetricLengthUnit.CENTIMETERS -> 1
            MetricLengthUnit.METERS -> 3
        }
        val text = if (unit == MetricLengthUnit.METERS) fixed(value, decimals) else MeasureFormat.decimal(value, decimals)
        return "$text ${unit.symbol}"
    }

    /** m² from 0.01 m² up, mm² below (conduit and wire areas). */
    fun area(squareInches: Rational): String {
        val sqMm = squareInches * Units.MM_PER_INCH * Units.MM_PER_INCH
        return if (sqMm.abs() >= Rational.of(10_000)) fixed(sqMm / SQ_MM_PER_SQ_M, 3) + " m²" else MeasureFormat.decimal(sqMm, 1) + " mm²"
    }

    /** m³ from 0.1 m³ up, liters below. */
    fun volume(cubicInches: Rational): String {
        val liters = cubicInches * Units.MM_PER_INCH * Units.MM_PER_INCH * Units.MM_PER_INCH / CU_MM_PER_LITER
        return if (liters.abs() >= Rational.of(100)) fixed(liters / LITERS_PER_CU_M, 3) + " m³" else MeasureFormat.decimal(liters, 2) + " L"
    }

    private fun fixed(value: Rational, decimals: Int): String {
        val text = value.toBigDecimal(decimals + 2).setScale(decimals, RoundingMode.HALF_UP).toPlainString()
        return (if (text.trimStart('-').all { it == '0' || it == '.' }) text.trimStart('-') else text).replace('-', '−')
    }
}

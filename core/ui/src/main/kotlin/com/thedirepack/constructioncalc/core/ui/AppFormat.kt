package com.thedirepack.constructioncalc.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.measure.Decimals
import com.thedirepack.constructioncalc.core.measure.LengthFormat
import com.thedirepack.constructioncalc.core.measure.LengthStyle
import com.thedirepack.constructioncalc.core.measure.Measure
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import com.thedirepack.constructioncalc.core.measure.MeasureFormat
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices

/** The app-wide settings, as Compose state. */
@Composable
fun rememberAppSettings(): AppSettings {
    val settings by LocalAppServices.current.settings.settings.collectAsStateWithLifecycle()
    return settings
}

/** Feet-inches or inches, per the user's length display setting. */
fun AppSettings.lengthStyle(preferInches: Boolean? = null): LengthStyle = lengthDisplay.resolve(preferInches)

/** Everything about how lengths are shown, in the user's unit system. */
fun AppSettings.lengthFormat(preferInches: Boolean? = null): LengthFormat =
    LengthFormat(unitSystem, precision, lengthStyle(preferInches), metricDisplay, metricResolution, feetInchStyle, fractionRounding)

val AppSettings.isMetric: Boolean get() = unitSystem == UnitSystem.METRIC

/** A length, area, volume or number formatted the way the user chose (system, precision, ft-in vs in). */
fun AppSettings.format(q: Quantity, preferInches: Boolean? = null): String =
    MeasureFormat.primary(q, lengthFormat(preferInches))

/** A length given in inches, formatted the way the user chose. */
fun AppSettings.formatInches(inches: Rational, preferInches: Boolean? = null): String = format(Quantity.inches(inches), preferInches)

/** A [Measure] value (in its base unit) in the user's system: `86 °F`, `30.48 m`, `4448 N`. */
fun AppSettings.show(measure: Measure, base: Double, decimals: Int? = null): String =
    if (base.isFinite()) measure.format(base, unitSystem, decimals) else "—"

/** The unit symbol a [Measure] is shown and typed in: `°F`, `m`, `kg/m`. */
fun AppSettings.unitOf(measure: Measure): String = measure.unit(unitSystem).symbol

/** A trade size as people in this system call it: `3/4"`, or `3/4" (21)` in metric. */
fun AppSettings.tradeSizeLabel(size: TradeSize): String =
    if (isMetric) "${size.display} (${size.metricDesignator})" else size.display

/** A conductor size, with its metric area in metric: `12 AWG (3.31 mm²)`. */
fun AppSettings.conductorSizeLabel(size: ConductorSize): String =
    if (isMetric) "${size.label} (${Decimals.trim(size.squareMillimeters, 2)} mm²)" else size.label

/** A decimal for display: at most [maxDecimals] places, trailing zeros removed, `−` for negatives. */
fun formatDecimal(value: Double, maxDecimals: Int = 2): String =
    if (!value.isFinite()) "—" else MeasureFormat.decimal(Rational.of(value), maxDecimals)

/** Parses what a user typed in a number field. Accepts `,` as a decimal mark and a leading `-`/`−`. */
fun String.toDecimalOrNull(): Double? = com.thedirepack.constructioncalc.core.measure.Decimals.parse(this)

/** The text a number field should show for an exact value. */
fun Rational.toFieldText(maxDecimals: Int = 4): String = MeasureFormat.decimal(this, maxDecimals).replace('−', '-')

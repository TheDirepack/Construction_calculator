package com.thedirepack.constructioncalc.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.LengthStyle
import com.thedirepack.constructioncalc.core.measure.MeasureFormat
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices
import java.math.BigDecimal

/** The app-wide settings, as Compose state. */
@Composable
fun rememberAppSettings(): AppSettings {
    val settings by LocalAppServices.current.settings.settings.collectAsStateWithLifecycle()
    return settings
}

/** Feet-inches or inches, per the user's length display setting. */
fun AppSettings.lengthStyle(preferInches: Boolean? = null): LengthStyle = lengthDisplay.resolve(preferInches)

/** A length, area, volume or number formatted the way the user chose (precision, ft-in vs in). */
fun AppSettings.format(q: Quantity, preferInches: Boolean? = null): String =
    MeasureFormat.primary(q, precision, lengthStyle(preferInches))

/** A decimal for display: at most [maxDecimals] places, trailing zeros removed, `−` for negatives. */
fun formatDecimal(value: Double, maxDecimals: Int = 2): String =
    if (!value.isFinite()) "—" else MeasureFormat.decimal(Rational.of(value), maxDecimals)

/** Parses what a user typed in a number field. Accepts `,` as a decimal mark and a leading `-`/`−`. */
fun String.toDecimalOrNull(): Double? {
    val clean = trim().replace(',', '.').replace('−', '-')
    if (clean.isEmpty() || clean == "-" || clean == ".") return null
    return runCatching { BigDecimal(clean).toDouble() }.getOrNull()
}

/** The text a number field should show for an exact value. */
fun Rational.toFieldText(maxDecimals: Int = 4): String = MeasureFormat.decimal(this, maxDecimals).replace('−', '-')

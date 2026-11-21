package com.thedirepack.constructioncalc.feature.conduitfill.domain

import com.thedirepack.constructioncalc.core.electrical.DeratingResult
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.measure.Decimals
import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.electrical.explanations
import com.thedirepack.constructioncalc.core.measure.Measure
import com.thedirepack.constructioncalc.core.ui.isMetric
import com.thedirepack.constructioncalc.core.ui.show

/** Derating as values other tools and projects can use. */
fun DeratingResult.toResults(): List<ResultValue> = buildList {
    add(
        ResultValue.number(
            "derate_ccc", "Current-carrying conductors",
            ValueKind.NUMBER,
            currentCarrying.toDouble(),
            "$currentCarrying (×${Decimals.trim(adjustmentFactor)})",
        ),
    )
    rows.forEachIndexed { i, row ->
        val usable = row.usable ?: return@forEachIndexed
        val g = row.group
        add(
            ResultValue.number(
                "derate_$i",
                "${g.count} × ${g.size.label} ${g.insulation?.code.orEmpty()} derated",
                ValueKind.CURRENT,
                kotlin.math.round(usable * 10) / 10.0,
                "${Decimals.trim(usable)} A" + (row.maxOcpd?.let { " · max $it A OCPD" } ?: ""),
            ),
        )
    }
}


private val ADDER = Regex("""\+(\d+(?:\.\d+)?) °C""")
private val CELSIUS = Regex("""(?<![\d.])(\d+(?:\.\d+)?) °C""")

/**
 * The core's derating text writes °C. In imperial, absolute temperatures become °F and the
 * rooftop adder (a difference, so ×9/5 with no +32) becomes `+60 °F`; metric is unchanged.
 */
fun localizeTemperatures(text: String, settings: AppSettings, keepRatings: Boolean = false): String {
    if (settings.isMetric) return text
    val adder = ADDER.replace(text) { m -> "+" + adderFahrenheit(m.groupValues[1].toDouble()) + " °F" }
    return CELSIUS.replace(adder) { m ->
        val c = m.groupValues[1].toDouble()
        // 60/75/90 °C are insulation and terminal rating names (like AWG), not measurements, so they stay.
        if (keepRatings && c in listOf(60.0, 75.0, 90.0)) m.value else settings.show(Measure.TEMPERATURE, c, 0)
    }
}

/** The arithmetic lines and notes under the derating results, in the user's system. */
fun DeratingResult.noticeTexts(settings: AppSettings): List<String> =
    explanations().map { localizeTemperatures(it, settings) } + notes.map { localizeTemperatures(it, settings, keepRatings = true) }

/** A temperature difference in °F; NEC 310.15(B)(2) prints its 33 °C adder as 60 °F, so that is what is shown. */
private fun adderFahrenheit(celsiusDifference: Double): String =
    if (celsiusDifference == 33.0) "60" else Decimals.trim(celsiusDifference * 9.0 / 5.0, 1)

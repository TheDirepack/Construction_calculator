package com.thedirepack.constructioncalc.feature.reellength

import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.electrical.ConductorResistance
import com.thedirepack.constructioncalc.core.electrical.ResistanceLength
import com.thedirepack.constructioncalc.core.electrical.Stranding
import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Decimals
import com.thedirepack.constructioncalc.core.measure.Measure
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.ui.components.Notice
import com.thedirepack.constructioncalc.core.ui.components.NoticeLevel
import com.thedirepack.constructioncalc.core.ui.format
import com.thedirepack.constructioncalc.core.ui.isMetric
import com.thedirepack.constructioncalc.core.ui.show
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/** How to take the reading, shown under the inputs. */
internal const val RESISTANCE_HOW_TO =
    "How to measure: use a low-ohm meter (or a DMM on its lowest range). Touch the leads together and " +
        "subtract that reading. Clip one lead to each end of one conductor. If only one end is reachable, " +
        "twist two conductors together at the far end, measure between them at your end, and turn on " +
        "\"Measured as a loop\". Let the reel sit at room temperature first and enter that temperature."

private fun exact(value: Double): Rational = Rational.of(BigDecimal(value).setScale(4, RoundingMode.HALF_UP))

/** Wire length from a measured DC resistance ([ResistanceLength], NEC Chapter 9 Table 8 via [Code.book]). */
internal fun resistanceReelOutput(inputs: ReelLengthInputs, settings: AppSettings): ReelLengthOutput {
    val size = inputs.conductorSize
    val material = inputs.wireMaterial
    val stranding = if (ConductorResistance.hasSolidOption(size)) inputs.wireStranding else Stranding.STRANDED
    val resistanceText = inputs.measuredResistance.trim()
    val resistance = Decimals.parse(resistanceText)
    val tempText = inputs.conductorTempC.trim()
    val tempC = if (tempText.isEmpty()) ResistanceLength.DEFAULT_AMBIENT_C else Decimals.parse(tempText)
    val howTo = Notice(RESISTANCE_HOW_TO, NoticeLevel.INFO, refId = "wire-length-from-resistance")

    val problems = buildList {
        if (resistanceText.isNotEmpty() && (resistance == null || !resistance.isFinite() || resistance <= 0.0)) {
            add(Notice("Measured resistance must be a positive number.", NoticeLevel.ERROR))
        }
        if (tempC == null || !tempC.isFinite()) add(Notice("Conductor temperature isn't a number.", NoticeLevel.ERROR))
    }
    if (problems.isNotEmpty()) return ReelLengthOutput(emptyList(), problems + howTo, "")
    if (resistance == null || tempC == null) {
        return ReelLengthOutput(emptyList(), listOf(Notice("Enter the measured resistance to find the length."), howTo), "")
    }

    val calc = ResistanceLength.calculate(
        measuredResistance = resistance,
        unit = inputs.resistanceUnit,
        size = size,
        material = material,
        stranding = stranding,
        ambientC = tempC,
        roundTrip = inputs.measuredLoop,
    ) ?: return ReelLengthOutput(
        emptyList(),
        listOf(Notice("That temperature is out of range for ${size.label} ${material.label}.", NoticeLevel.ERROR), howTo),
        "",
    )

    val lengthQty = Quantity.inches(exact(calc.lengthIn))
    val lengthText = settings.format(lengthQty)
    val needed = inputs.cableNeeded?.toQuantity()?.value?.toDouble()?.takeIf { it > 0 }
    val spare = needed?.let { calc.lengthIn - it }
    val perUnit = if (settings.isMetric) calc.ohmsPerKftAtTemp / 0.3048 else calc.ohmsPerKftAtTemp
    val perUnitLabel = if (settings.isMetric) "Ω/km" else "Ω/1000 ft"
    val tempShown = settings.show(Measure.TEMPERATURE, tempC, 0)

    val results = listOfNotNull(
        ResultValue.of("length", "Wire length", lengthQty, lengthText),
        spare?.let {
            val q = Quantity.inches(exact(abs(it)))
            ResultValue.of("spare_after_pull", if (it >= 0) "Left after the pull" else "Short for the pull", q, settings.format(q))
        },
        ResultValue.text("length_feet", "Wire length (${if (settings.isMetric) "meters" else "feet"})", formatRunLength(calc.lengthFt, settings)),
        ResultValue.number(
            "resistance_per_length", "Resistance at $tempShown", ValueKind.NUMBER, exact(perUnit),
            "${Decimals.trim(perUnit, 4)} $perUnitLabel",
        ),
    )

    val refTemp = settings.show(Measure.TEMPERATURE, Code.book.values.resistanceReferenceC, 0)
    val notices = buildList {
        if (inputs.measuredLoop) {
            add(Notice("Measured as a loop: the reading covers two conductors, so it was halved."))
        }
        add(
            Notice(
                "${size.label} ${material.label.lowercase()}, ${stranding.label.lowercase()}: ${Code.label} " +
                    "${CodeRule.CONDUCTOR_PROPERTIES.cite} resistance at $refTemp, corrected to $tempShown.",
                refId = "nec-conductor-properties",
            ),
        )
        if (!ConductorResistance.hasOwnColumn(material)) {
            add(Notice("${material.label} has no column of its own in ${CodeRule.CONDUCTOR_PROPERTIES.cite}; aluminum values are used.", NoticeLevel.WARNING))
        }
        if (needed != null && spare != null) {
            val neededText = settings.format(Quantity.inches(exact(needed)))
            if (spare >= 0) {
                add(Notice("Enough for the $neededText needed, with ${settings.format(Quantity.inches(exact(spare)))} to spare.", NoticeLevel.INFO))
            } else {
                add(Notice("Short by ${settings.format(Quantity.inches(exact(-spare)))} for the $neededText needed.", NoticeLevel.WARNING))
            }
        }
        add(howTo)
    }

    val summary = "${size.label} ${material.shortLabel}, ${Decimals.trim(resistance, 4)} ${inputs.resistanceUnit.label}" +
        (if (inputs.measuredLoop) " loop" else "") + " at $tempShown: $lengthText"
    return ReelLengthOutput(results, notices, summary)
}

package com.thedirepack.constructioncalc.feature.reellength

import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.ui.components.Notice
import com.thedirepack.constructioncalc.core.ui.components.NoticeLevel
import com.thedirepack.constructioncalc.core.ui.format
import com.thedirepack.constructioncalc.core.ui.formatDecimal
import com.thedirepack.constructioncalc.feature.reellength.domain.ReelLength
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/** Everything the screen shows for one set of inputs. */
internal data class ReelLengthOutput(
    val results: List<ResultValue>,
    val notices: List<Notice>,
    /** One-line description for a saved item; blank when there are no results. */
    val summary: String,
)

/** "1,234 ft" (one decimal below 100 ft). */
internal fun formatFeet(feet: Double): String =
    String.format(Locale.US, if (feet < 100) "%,.1f ft" else "%,.0f ft", feet)

/** A double as an exact Rational, rounded to 4 decimals so saved values stay short. */
private fun exact(value: Double): Rational = Rational.of(BigDecimal(value).setScale(4, RoundingMode.HALF_UP))

internal fun reelLengthOutput(inputs: ReelLengthInputs, settings: AppSettings): ReelLengthOutput {
    val parsed = inputs.parse()
    val errors = parsed.problems.map { Notice(it, NoticeLevel.ERROR) }
    val request = parsed.request
    val result = request?.let { ReelLength.calculate(it) }
    if (request == null || result == null) return ReelLengthOutput(emptyList(), errors, "")

    val capacity = inputs.mode == ReelMode.CAPACITY
    val lengthLabel = if (capacity) "Reel capacity" else "Cable remaining"
    val lengthQuantity = Quantity.inches(exact(result.lengthInches))
    val depthQuantity = Quantity.inches(exact(result.woundDepth))
    val results = listOf(
        ResultValue.of("length", lengthLabel, lengthQuantity, settings.format(lengthQuantity)),
        ResultValue.text("length_feet", "$lengthLabel (feet)", formatFeet(result.lengthFeet)),
        ResultValue.of("wound_depth", "Wound depth", depthQuantity, settings.format(depthQuantity)),
        ResultValue.number("layers", "Layers", ValueKind.NUMBER, exact(result.layers), formatDecimal(result.layers, 1)),
        ResultValue.number(
            "turns_per_layer", "Turns per layer", ValueKind.NUMBER, exact(result.turnsPerLayer), formatDecimal(result.turnsPerLayer, 1),
        ),
    )

    val notices = buildList {
        add(
            Notice(
                "Estimate for level-wound cable: even layers, no gaps or slack, perfectly round cable. " +
                    "Verify by weight if the number is critical.",
                NoticeLevel.INFO,
            ),
        )
        if (request.packingFactor != 1.0) {
            add(Notice("Result includes your packing factor of ${formatDecimal(request.packingFactor, 3)}.", NoticeLevel.INFO))
        } else {
            add(Notice("No packing factor applied; real reels usually hold a little less.", NoticeLevel.INFO))
        }
        if (request.packingFactor > 1.0) {
            add(Notice("A packing factor above 1 is more cable than the geometry allows.", NoticeLevel.WARNING))
        }
        if (capacity) {
            add(Notice("Capacity leaves ${settings.format(Quantity.inches(exact(request.topClearance)))} between the cable and the flange edge.", NoticeLevel.INFO))
        }
    }

    val summary = buildString {
        append(lengthLabel).append(' ').append(formatFeet(result.lengthFeet)).append(": ")
        append(settings.format(Quantity.inches(exact(request.flangeDiameter)))).append(" flange, ")
        append(settings.format(Quantity.inches(exact(request.drumDiameter)))).append(" drum, ")
        append(settings.format(Quantity.inches(exact(request.traverse)))).append(" traverse, ")
        append(settings.format(Quantity.inches(exact(request.cableDiameter)))).append(" cable")
    }
    return ReelLengthOutput(results, errors + notices, summary)
}

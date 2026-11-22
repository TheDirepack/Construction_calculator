package com.thedirepack.constructioncalc.feature.drawings.domain

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.PlanDrawing
import com.thedirepack.constructioncalc.core.model.PlanMarkKind
import com.thedirepack.constructioncalc.core.model.PlanRunRoute

/** Length measurements of one group (a page, a zone, a label) and their total. */
data class LengthGroup(val name: String, val count: Int, val total: Quantity)

data class SymbolCount(val name: String, val count: Int)

/** A route sent to a run, measured with the drawing's current calibration. */
data class RouteTakeoff(
    val route: PlanRunRoute,
    /** The plan length now, or null if a leg can no longer be measured (calibration removed). */
    val current: Quantity?,
    /** The plan length when it was sent. */
    val sent: Quantity,
) {
    /** True when recalibration moved the plan length by more than 1/16″ since it was sent. */
    val changed: Boolean
        get() = current == null || (current.value - sent.value).abs() > Rational.of(1, 16)
}

data class TakeoffSummary(
    val byPage: List<Pair<Int, LengthGroup>>,
    /** Measurements grouped by their label (ignoring case and spaces at the ends); unlabeled ones are left out. */
    val byLabel: List<LengthGroup>,
    val measuredTotal: Quantity,
    val measuredCount: Int,
    /** Measurements that have no calibration (their zone or the document scale was removed). */
    val uncalibratedCount: Int,
    val symbols: List<SymbolCount>,
    val textNotes: Int,
    val routes: List<RouteTakeoff>,
) {
    val isEmpty: Boolean
        get() = measuredCount == 0 && uncalibratedCount == 0 && symbols.isEmpty() && textNotes == 0 && routes.isEmpty()
}

/**
 * Rolls a drawing's measurements, symbols and routes up into totals. Sums are exact over each
 * measurement's full value; rounding happens only where they're shown.
 */
object PlanTakeoff {
    fun summarize(drawing: PlanDrawing): TakeoffSummary {
        val lengths = drawing.measurements.map { it to drawing.length(it) }
        val measured = lengths.filter { it.second != null }
        fun total(items: List<Pair<*, Quantity?>>) =
            Quantity.inches(items.fold(Rational.ZERO) { sum, (_, q) -> sum + q!!.value })

        val byPage = measured.groupBy { it.first.page }.toSortedMap().map { (page, items) ->
            page to LengthGroup("Page ${page + 1}", items.size, total(items))
        }
        val byLabel = measured.filter { it.first.label.isNotBlank() }
            .groupBy { it.first.label.trim().lowercase() }
            .map { (_, items) -> LengthGroup(items.first().first.label.trim(), items.size, total(items)) }
            .sortedBy { it.name.lowercase() }
        val symbols = drawing.marks.filter { it.kind == PlanMarkKind.SYMBOL }
            .groupingBy { it.text.trim().ifBlank { "Symbol" } }
            .eachCount()
            .map { (name, count) -> SymbolCount(name, count) }
            .sortedBy { it.name.lowercase() }
        val routes = drawing.routes.map { route ->
            val summary = PlanRoute.measure(drawing, route.page, route.points)
            RouteTakeoff(
                route = route,
                current = summary?.totalHorizontalQuantity,
                sent = Quantity.inches(Rational.of(route.sentInches)),
            )
        }
        return TakeoffSummary(
            byPage = byPage,
            byLabel = byLabel,
            measuredTotal = total(measured),
            measuredCount = measured.size,
            uncalibratedCount = lengths.size - measured.size,
            symbols = symbols,
            textNotes = drawing.marks.count { it.kind == PlanMarkKind.TEXT },
            routes = routes,
        )
    }
}

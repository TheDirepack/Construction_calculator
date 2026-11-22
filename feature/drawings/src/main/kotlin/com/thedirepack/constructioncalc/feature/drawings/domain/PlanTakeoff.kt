package com.thedirepack.constructioncalc.feature.drawings.domain

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Dimension
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.PlanDrawing
import com.thedirepack.constructioncalc.core.model.PlanMark
import com.thedirepack.constructioncalc.core.model.PlanMarkKind
import com.thedirepack.constructioncalc.core.model.PlanPathMeasurement
import com.thedirepack.constructioncalc.core.model.PlanRunRoute
import com.thedirepack.constructioncalc.core.model.PlanSheetSource
import com.thedirepack.constructioncalc.core.model.measurePath
import com.thedirepack.constructioncalc.core.model.sheetSource

/** Length measurements of one group (a page, a zone, a label) and their total. */
data class LengthGroup(val name: String, val count: Int, val total: Quantity)

data class SymbolCount(
    val name: String,
    val count: Int,
    val circuit: String = "",
    val area: String = "",
)

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

/** Devices and routes belonging to the same circuit tag, grouped together for takeoff. */
data class CircuitTakeoff(
    val circuit: String,
    val symbols: List<SymbolCount>,
    val routes: List<RouteTakeoff>,
)

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
    val symbolsByCircuit: List<SymbolCount> = emptyList(),
    val routes: List<RouteTakeoff>,
    val sources: List<PlanSheetSource>,
    val symbolsByArea: List<SymbolCount> = emptyList(),
    val byCircuitCombined: List<CircuitTakeoff> = emptyList(),
    val pathPolylineCount: Int = 0,
    val pathPolylineTotal: Quantity = Quantity.inches(Rational.ZERO),
    val pathPolygonCount: Int = 0,
    val pathPolygonPerimeter: Quantity = Quantity.inches(Rational.ZERO),
    val pathPolygonArea: Quantity = Quantity(Rational.ZERO, Dimension.AREA),
    val uncalibratedPathCount: Int = 0,
    val multiScalePathCount: Int = 0,
) {
    val isEmpty: Boolean
        get() = measuredCount == 0 && uncalibratedCount == 0 && symbols.isEmpty() && textNotes == 0 &&
            routes.isEmpty() && pathPolylineCount == 0 && pathPolygonCount == 0 &&
            uncalibratedPathCount == 0 && multiScalePathCount == 0
}

/**
 * Rolls a drawing's measurements, paths, symbols and routes up into totals. Sums are exact over
 * each measurement's full value; rounding happens only where they're shown.
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
        val symbolMarks = drawing.marks.filter { it.kind == PlanMarkKind.SYMBOL }
        fun counts(marks: List<PlanMark>, circuit: String = "", area: String = "") =
            marks.groupingBy { it.text.trim().ifBlank { "Symbol" } }
                .eachCount()
                .map { (name, count) -> SymbolCount(name, count, circuit = circuit, area = area) }
                .sortedBy { it.name.lowercase() }
        val byCircuit = symbolMarks.filter { it.circuitTag.isNotBlank() }
            .groupBy { it.circuitTag.trim().uppercase() }
            .toSortedMap()
            .flatMap { (circuit, marks) -> counts(marks, circuit = circuit) }
        val byArea = symbolMarks.filter { it.areaTag.isNotBlank() }
            .groupBy { it.areaTag.trim() }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
            .flatMap { (area, marks) -> counts(marks, area = area) }
        val routes = drawing.routes.map { route ->
            val summary = PlanRoute.measure(drawing, route.page, route.points)
            RouteTakeoff(
                route = route,
                current = summary?.totalHorizontalQuantity,
                sent = Quantity.inches(Rational.of(route.sentInches)),
            )
        }
        val routesByCircuit = routes.filter { it.route.circuitTag.isNotBlank() }
            .groupBy { it.route.circuitTag.trim().uppercase() }
        val symbolsByCircuitMap = byCircuit.groupBy { it.circuit }
        val allCircuits = (symbolsByCircuitMap.keys + routesByCircuit.keys).toSortedSet()
        val combinedCircuits = allCircuits.map { circuit ->
            CircuitTakeoff(
                circuit = circuit,
                symbols = symbolsByCircuitMap[circuit].orEmpty(),
                routes = routesByCircuit[circuit].orEmpty(),
            )
        }

        var polylineCount = 0
        var polylineTotal = Rational.ZERO
        var polygonCount = 0
        var polygonPerimeter = Rational.ZERO
        var polygonArea = Rational.ZERO
        var uncalibratedPaths = 0
        var multiScalePaths = 0
        for (path in drawing.paths) {
            when (val m = drawing.measurePath(path)) {
                PlanPathMeasurement.CrossesZones -> multiScalePaths++
                PlanPathMeasurement.Uncalibrated -> uncalibratedPaths++
                is PlanPathMeasurement.Measured -> {
                    if (path.closed) {
                        polygonCount++
                        polygonPerimeter += m.length.value
                        m.area?.let { polygonArea += it.value }
                    } else {
                        polylineCount++
                        polylineTotal += m.length.value
                    }
                }
            }
        }

        val noteKinds = setOf(PlanMarkKind.TEXT, PlanMarkKind.CALLOUT, PlanMarkKind.CLOUD_CALLOUT)
        return TakeoffSummary(
            byPage = byPage,
            byLabel = byLabel,
            measuredTotal = total(measured),
            measuredCount = measured.size,
            uncalibratedCount = lengths.size - measured.size,
            symbols = counts(symbolMarks),
            textNotes = drawing.marks.count { it.kind in noteKinds },
            symbolsByCircuit = byCircuit,
            routes = routes,
            sources = (
                drawing.measurements.map { it.page } +
                    symbolMarks.map { it.page } +
                    drawing.routes.map { it.page } +
                    drawing.paths.map { it.page } +
                    drawing.marks.filter { it.kind in noteKinds }.map { it.page }
                ).distinct().sorted().map { drawing.sheetSource(it) },
            symbolsByArea = byArea,
            byCircuitCombined = combinedCircuits,
            pathPolylineCount = polylineCount,
            pathPolylineTotal = Quantity.inches(polylineTotal),
            pathPolygonCount = polygonCount,
            pathPolygonPerimeter = Quantity.inches(polygonPerimeter),
            pathPolygonArea = Quantity(polygonArea, Dimension.AREA),
            uncalibratedPathCount = uncalibratedPaths,
            multiScalePathCount = multiScalePaths,
        )
    }
}

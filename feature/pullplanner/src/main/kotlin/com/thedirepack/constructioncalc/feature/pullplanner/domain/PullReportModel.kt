package com.thedirepack.constructioncalc.feature.pullplanner.domain

import com.thedirepack.constructioncalc.core.electrical.ConduitFill
import com.thedirepack.constructioncalc.core.electrical.PullFormulas
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Decimals
import com.thedirepack.constructioncalc.core.measure.Measure
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import com.thedirepack.constructioncalc.core.model.PathSegment
import com.thedirepack.constructioncalc.core.model.design.PullAnalysis
import com.thedirepack.constructioncalc.core.model.design.PullConductor
import com.thedirepack.constructioncalc.core.model.design.PullDirection
import com.thedirepack.constructioncalc.core.model.design.PullResult
import com.thedirepack.constructioncalc.core.model.design.PullText

/** Summary row for one pull direction (forward or reverse) in a Wire Pull PDF report. */
data class DirectionSummaryRow(
    val direction: String,
    val recommended: Boolean,
    val peakTension: String,
    val peakPressure: String,
    val status: String,
)

/** One path-segment row showing forward and reverse tensions and sidewall pressures. */
data class SegmentReportRow(
    val number: Int,
    val description: String,
    val forwardIn: String,
    val forwardOut: String,
    val forwardSwbp: String,
    val reverseIn: String,
    val reverseOut: String,
    val reverseSwbp: String,
)

/** Complete, pre-formatted model for rendering a dedicated Wire Pull Planner PDF report. */
data class PullPdfReport(
    val title: String,
    val subtitle: String,
    val companyHeader: List<String>,
    val summaryLine: String,
    val runFacts: List<Pair<String, String>>,
    val directionSummary: List<DirectionSummaryRow>,
    val cables: List<String>,
    val limits: List<Pair<String, String>>,
    val unratedEquipment: List<String>,
    val segments: List<SegmentReportRow>,
    val warnings: List<String>,
    val infoNotes: List<String>,
    val date: String,
)

/** Builds a pure, unit-aware [PullPdfReport] from a [PullResult]. */
object PullReportModel {

    fun build(
        result: PullResult,
        projectName: String?,
        companyHeader: List<String>,
        date: String,
        units: UnitSystem = UnitSystem.IMPERIAL,
        lengthText: (Quantity) -> String,
    ): PullPdfReport {
        val runName = result.run.name.ifBlank { "Standalone pull" }
        val cleanProject = projectName?.trim()?.takeIf { it.isNotEmpty() }
        val title = "Wire Pull Report: $runName"
        val subtitle = "${cleanProject ?: "Standalone pull"} · $date"

        val summaryLine = PullText.summary(result, units)
            ?: "Pull not calculated: ${result.notes.firstOrNull { it.isWarning }?.text ?: "add the path and cables"}"

        val directionSummary = listOfNotNull(result.forward, result.reverse).map { pass ->
            val overTension = result.tensionLimit?.let { pass.peakTension > it.value } == true
            val overPressure = result.pressureLimit?.let { limit ->
                pass.peakPressure?.let { it > limit.value }
            } == true
            DirectionSummaryRow(
                direction = pass.direction.label,
                recommended = pass.direction == result.recommended,
                peakTension = PullAnalysis.lb(pass.peakTension, units),
                peakPressure = pass.peakPressure?.let { PullAnalysis.pressure(it, units) } ?: "—",
                status = if (overTension || overPressure) "Exceeds limits" else "Within limits",
            )
        }

        val runFacts = buildRunFacts(result, cleanProject, units, lengthText)
        val cables = result.conductors.map { formatConductor(it, units) }

        val governingIndex = result.tensionLimits.indices.minByOrNull { result.tensionLimits[it].value }
        val limits = buildList {
            result.tensionLimits.forEachIndexed { index, limit ->
                val governing = if (index == governingIndex && result.tensionLimits.size > 1) " (governing)" else ""
                add("Tension · ${limit.label}" to "${PullAnalysis.lb(limit.value, units)}$governing — ${limit.source}")
            }
            result.pressureLimit?.let { limit ->
                add("SWBP · ${limit.label}" to "${PullAnalysis.pressure(limit.value, units)} — ${limit.source}")
            }
        }

        val conduitType = result.run.raceway?.conduitType
        val tradeSize = result.conduit?.tradeSize ?: result.run.raceway?.tradeSize
        val segments = result.run.path.indices.map { index ->
            val segment = result.run.path[index]
            val fwd = PullReport.tensionAt(result, PullDirection.FORWARD, index)
            val rev = PullReport.tensionAt(result, PullDirection.REVERSE, index)
            SegmentReportRow(
                number = index + 1,
                description = PullReport.describe(segment, conduitType, tradeSize, lengthText),
                forwardIn = fwd?.let { PullAnalysis.lb(it.tensionIn, units) } ?: "—",
                forwardOut = fwd?.let { PullAnalysis.lb(it.tensionOut, units) } ?: "—",
                forwardSwbp = fwd?.pressure?.let { PullAnalysis.pressure(it, units) } ?: "—",
                reverseIn = rev?.let { PullAnalysis.lb(it.tensionIn, units) } ?: "—",
                reverseOut = rev?.let { PullAnalysis.lb(it.tensionOut, units) } ?: "—",
                reverseSwbp = rev?.pressure?.let { PullAnalysis.pressure(it, units) } ?: "—",
            )
        }

        val (warningNotes, informationalNotes) = result.notes.partition { it.isWarning }

        return PullPdfReport(
            title = title,
            subtitle = subtitle,
            companyHeader = companyHeader,
            summaryLine = summaryLine,
            runFacts = runFacts,
            directionSummary = directionSummary,
            cables = cables,
            limits = limits,
            unratedEquipment = result.missingRatings,
            segments = segments,
            warnings = warningNotes.map { it.text },
            infoNotes = informationalNotes.map { it.text },
            date = date,
        )
    }

    private fun buildRunFacts(
        result: PullResult,
        projectName: String?,
        units: UnitSystem,
        lengthText: (Quantity) -> String,
    ): List<Pair<String, String>> = buildList {
        add("Project" to (projectName ?: "Standalone"))

        val raceway = result.run.raceway
        val conduitText = when {
            result.conduit != null -> {
                val c = result.conduit!!
                val sizeLabel = if (units == UnitSystem.METRIC) {
                    "${c.tradeSize.display} (${c.tradeSize.metricDesignator})"
                } else {
                    c.tradeSize.display
                }
                val autoTag = if (raceway?.tradeSize == null) " (auto)" else ""
                val nippleTag = if (raceway?.nipple == true) ", nipple" else ""
                val idText = PullAnalysis.smallLength(c.internalDiameterIn, units, if (units == UnitSystem.METRIC) 2 else 3)
                "$sizeLabel ${c.type.displayName}$autoTag$nippleTag (ID $idText)"
            }
            raceway != null -> "${raceway.conduitType.displayName} (size not set)"
            else -> "Not set"
        }
        add("Conduit" to conduitText)

        add("Routed length" to (result.routedLengthIn?.let { lengthText(Quantity.inches(Rational.of(it))) } ?: "—"))

        val slackText = result.setup.slackPerEnd
            ?.takeIf { it.toQuantity().value.signum > 0 }
            ?.let { lengthText(it.toQuantity()) }
            ?: "None"
        add("Slack per end" to slackText)

        add("Cable needed / conductor" to (result.cableLengthIn?.let { lengthText(Quantity.inches(Rational.of(it))) } ?: "—"))

        val bendCount = result.run.path.count { it is PathSegment.Bend }
        val pullPointCount = result.run.path.count { it is PathSegment.PullPoint }
        val bendsText = "${PullReport.num(result.totalDegrees)}° ($bendCount bend${if (bendCount == 1) "" else "s"}), " +
            "$pullPointCount pull point${if (pullPointCount == 1) "" else "s"}"
        add("Bends & pull points" to bendsText)

        val wText = result.weightCorrection?.let { "W = ${PullReport.num(it, 3)}" } ?: "W = —"
        val configSource = if (result.setup.configuration != null) "selected" else "auto"
        add("Configuration & W" to "${result.configuration.label}, $wText ($configSource)")

        val frictionSource = if (result.setup.frictionOverride != null) {
            "entered for this run"
        } else {
            "${result.setup.friction.label} (guidance)"
        }
        add("Friction μ" to "${PullReport.num(result.friction, 2)} — $frictionSource")

        val jamText = result.jamRatio?.let { jam ->
            val low = Decimals.trim(ConduitFill.JAM_LOW)
            val high = Decimals.trim(ConduitFill.JAM_HIGH)
            val status = when {
                result.jamWarning -> "Jam risk ($low–$high for 3 cables)"
                PullFormulas.inJamRange(jam) -> "In $low–$high range (${result.cableCount} cables; check applies to 3)"
                else -> "OK (outside $low–$high)"
            }
            "${PullReport.num(jam, 2)} — $status"
        } ?: "—"
        add("Jam ratio (${PullFormulas.OVALITY_LABEL})" to jamText)

        val clearanceText = result.clearanceIn?.let { c ->
            val formatted = PullAnalysis.smallLength(c, units, 2)
            val minText = PullAnalysis.smallLength(PullFormulas.CLEARANCE_MINIMUM_IN, units, 2)
            val recText = PullAnalysis.smallLength(PullFormulas.CLEARANCE_RECOMMENDED_IN, units, 2)
            val status = when {
                c < PullFormulas.CLEARANCE_MINIMUM_IN -> "Below $minText minimum"
                c < PullFormulas.CLEARANCE_RECOMMENDED_IN -> "Below $recText recommended"
                else -> "OK (>= $recText recommended)"
            }
            "$formatted — $status"
        } ?: "—"
        add("Clearance" to clearanceText)
    }

    private fun formatConductor(c: PullConductor, units: UnitSystem): String {
        val name = if (c.label.isNotBlank()) "${c.count} × ${c.label}" else c.text
        val details = buildList {
            c.diameterIn?.let {
                add("OD ${PullAnalysis.smallLength(it, units, if (units == UnitSystem.METRIC) 2 else 3)}")
            }
            c.weightLbPerFt?.let {
                val weightText = Measure.LINEAR_WEIGHT.format(it, units, 3)
                add(if (c.weightEstimated) "$weightText (est.)" else weightText)
            }
            if (c.fromCircuits) add("from circuits")
            if (!c.bearsTension && !c.ground) add("does not bear tension")
        }
        return if (details.isEmpty()) name else "$name · ${details.joinToString(" · ")}"
    }
}

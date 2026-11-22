package com.thedirepack.constructioncalc.feature.pullplanner.domain

import com.thedirepack.constructioncalc.core.electrical.PullFormulas
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.FrictionPreset
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Decimals
import com.thedirepack.constructioncalc.core.measure.Measure
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import com.thedirepack.constructioncalc.core.model.BendPlane
import com.thedirepack.constructioncalc.core.model.PathSegment
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.PullSetup
import com.thedirepack.constructioncalc.core.model.Raceway
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.model.Run
import com.thedirepack.constructioncalc.core.model.RunPath
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.model.design.NoteLevel
import com.thedirepack.constructioncalc.core.model.design.PullAnalysis
import com.thedirepack.constructioncalc.core.model.design.PullDirection
import com.thedirepack.constructioncalc.core.model.design.PullResult
import com.thedirepack.constructioncalc.core.model.design.PullText
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.core.model.withRaceway
import com.thedirepack.constructioncalc.core.model.DEFAULT_INCOMING_LB
import com.thedirepack.constructioncalc.core.model.withRunPath

/** The planner's options from its Settings page, used for runs that have no pull settings yet. */
data class PullDefaults(
    val friction: FrictionPreset = FrictionPreset.LUBRICATED,
    val slackPerEndFeet: Double = 0.0,
    val incomingTension: Double = DEFAULT_INCOMING_LB,
) {
    fun setup(fromCircuits: Boolean = true) = PullSetup(
        friction = friction,
        slackPerEnd = slackPerEndFeet.takeIf { it > 0.0 }?.let { Quantity.feet(Rational.of(it)).stored() },
        incomingTension = listOf(incomingTension),
        cablesFromCircuits = fromCircuits,
    )
}

/** Editing a run's path and pull from the planner (pure). */
object RunEdits {
    /**
     * [project] with [change] applied to [runId], keeping the run's length and path in step:
     * pull settings are stored as they are, a new conduit goes through [withRaceway] and a new path
     * through [withRunPath]. [defaults] stand in for a run without pull settings.
     */
    fun apply(project: Project, runId: String, now: Long, defaults: PullSetup, change: (Run) -> Run): Project {
        val old = project.run(runId) ?: return project
        val before = old.copy(pull = old.pull ?: defaults)
        val after = change(before)
        var p = project
        if (after.pull != before.pull || old.pull == null) p = p.updateRun(runId, now) { it.copy(pull = after.pull) }
        if (after.raceway != before.raceway && after.raceway != null) p = p.withRaceway(runId, after.raceway!!, now)
        if (after.path != before.path) p = p.withRunPath(runId, after.path, now)
        return p
    }

    fun move(path: List<PathSegment>, from: Int, to: Int): List<PathSegment> {
        if (from !in path.indices || to !in path.indices) return path
        return path.toMutableList().also { it.add(to, it.removeAt(from)) }
    }

    fun remove(path: List<PathSegment>, index: Int): List<PathSegment> = path.filterIndexed { i, _ -> i != index }

    fun replace(path: List<PathSegment>, index: Int, segment: PathSegment): List<PathSegment> =
        if (index in path.indices) path.toMutableList().also { it[index] = segment } else path + segment
}

/** Text and results for the planner, the saved item and the pull report (pure). */
object PullReport {
    const val KEY_MAX_TENSION = "max_tension"
    const val KEY_MAX_PRESSURE = "max_swbp"
    const val KEY_CABLE_NEEDED = "cable_needed"
    const val KEY_BEND_DEGREES = "total_bend_degrees"

    fun lb(v: Double, units: UnitSystem = UnitSystem.IMPERIAL) = PullAnalysis.lb(v, units)
    fun num(v: Double, decimals: Int = 1) = Decimals.trim(v, decimals)

    /**
     * Published values, headline first: max tension (FORCE, lb), max sidewall pressure (NUMBER,
     * lb/ft), cable needed per conductor (LENGTH, inches), total bend degrees (ANGLE).
     */
    fun results(r: PullResult, units: UnitSystem = UnitSystem.IMPERIAL, lengthText: (Quantity) -> String): List<ResultValue> = buildList {
        val pass = r.recommendedPass
        pass?.let { p ->
            add(ResultValue.number(KEY_MAX_TENSION, "Max tension (${p.direction.label.lowercase()})", ValueKind.FORCE, p.peakTension, lb(p.peakTension, units)))
            p.peakPressure?.let { add(ResultValue.number(KEY_MAX_PRESSURE, "Max sidewall pressure (${Measure.SIDEWALL_PRESSURE.unit(units).symbol})", ValueKind.NUMBER, it, PullAnalysis.pressure(it, units))) }
        }
        r.cableLengthIn?.let {
            val q = Quantity.inches(Rational.of(it))
            add(ResultValue.of(KEY_CABLE_NEEDED, "Cable needed per conductor", q, lengthText(q)))
        }
        if (r.run.path.isNotEmpty()) add(ResultValue.number(KEY_BEND_DEGREES, "Total bends", ValueKind.ANGLE, r.totalDegrees, "${num(r.totalDegrees)}°"))
        r.tensionLimit?.let { add(ResultValue.number("tension_limit", "Tension limit (${it.label})", ValueKind.FORCE, it.value, lb(it.value, units))) }
        r.jamRatio?.let { add(ResultValue.number("jam_ratio", "Jam ratio (${PullFormulas.OVALITY_LABEL})", ValueKind.NUMBER, it, num(it, 2))) }
        r.clearanceIn?.let { add(ResultValue.number("clearance", "Clearance", ValueKind.LENGTH, it, Measure.SMALL_LENGTH.format(it, units, if (units == UnitSystem.METRIC) 1 else 2))) }
    }

    /** The saved item's summary: the pull line, then every limit and preset with its source. */
    fun summary(r: PullResult, units: UnitSystem = UnitSystem.IMPERIAL): String = buildString {
        appendLine(PullText.summary(r, units) ?: "Pull not calculated: ${r.notes.firstOrNull { it.isWarning }?.text ?: "add the path and cables"}")
        r.forward?.let { f -> r.reverse?.let { b -> appendLine("Peak: toward the end ${lb(f.peakTension, units)}, toward the start ${lb(b.peakTension, units)}") } }
        appendLine("Cables: " + r.conductors.joinToString { it.text }.ifEmpty { "none" })
        appendLine("Friction μ ${num(r.friction, 2)}" + if (r.setup.frictionOverride != null) " (entered)" else " (${r.setup.friction.label}, guidance)")
        r.tensionLimits.forEach { appendLine("Tension limit: ${it.label} ${lb(it.value, units)} — ${it.source}") }
        if (r.missingRatings.isNotEmpty()) appendLine("Not rated yet: ${r.missingRatings.joinToString()}")
        r.pressureLimit?.let { appendLine("Pressure limit: ${PullAnalysis.pressure(it.value, units)} — ${it.source}") }
        r.notes.filter { it.level == NoteLevel.WARNING }.forEach { appendLine("⚠ ${it.text}") }
    }.trimEnd()

    /** `Straight 100' level`, `Bend 90° horizontal, R 24" (Table 2)`, `Pull point PB-1`. */
    fun describe(segment: PathSegment, type: ConduitType?, size: TradeSize?, lengthText: (Quantity) -> String): String = when (segment) {
        is PathSegment.Straight -> "Straight ${lengthText(segment.length.toQuantity())}" + when {
            segment.slopeDegrees > 0 -> ", up ${num(segment.slopeDegrees)}°"
            segment.slopeDegrees < 0 -> ", down ${num(-segment.slopeDegrees)}°"
            else -> ""
        }
        is PathSegment.Bend -> {
            val plane = when (segment.plane) {
                BendPlane.HORIZONTAL -> ""
                BendPlane.VERTICAL_UP -> ", vertical sag"
                BendPlane.VERTICAL_DOWN -> ", vertical hump"
            }
            val radius = segment.radius?.let { "R ${lengthText(it.toQuantity())}" }
                ?: RunPath.radiusInches(segment, type, size)?.let { "R ${lengthText(Quantity.inches(Rational.of(it)))} (${CodeRule.BEND_RADIUS.cite})" }
                ?: "R from ${CodeRule.BEND_RADIUS.cite} once the size is known"
            "Bend ${num(segment.angleDegrees)}°$plane, $radius" + if (segment.sourceItemId != null) " · from Bending" else ""
        }
        is PathSegment.PullPoint -> "Pull point" + segment.label.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
    }

    /** Tension leaving [pathIndex] in [direction], or null. */
    fun tensionAt(r: PullResult, direction: PullDirection, pathIndex: Int) =
        r.pass(direction)?.steps?.firstOrNull { it.pathIndex == pathIndex }
}

/** The planner's standalone conduit when no project run is picked: 1" EMT. */
val STANDALONE_RACEWAY = Raceway(ConduitType.EMT, TradeSize.S_1)

package com.thedirepack.constructioncalc.core.model.design

import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.CableConfiguration
import com.thedirepack.constructioncalc.core.electrical.CableWeight
import com.thedirepack.constructioncalc.core.electrical.ConductorGroup
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.ConduitFill
import com.thedirepack.constructioncalc.core.electrical.ConduitSize
import com.thedirepack.constructioncalc.core.electrical.ConduitTable
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.electrical.FillConductor
import com.thedirepack.constructioncalc.core.electrical.InsulatedConductorArea
import com.thedirepack.constructioncalc.core.electrical.InsulationType
import com.thedirepack.constructioncalc.core.electrical.PullFormulas
import com.thedirepack.constructioncalc.core.electrical.PullRatings
import com.thedirepack.constructioncalc.core.electrical.RacewayBends
import com.thedirepack.constructioncalc.core.measure.Decimals
import com.thedirepack.constructioncalc.core.model.BendPlane
import com.thedirepack.constructioncalc.core.model.PathSegment
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.PullCable
import com.thedirepack.constructioncalc.core.model.PullSetup
import com.thedirepack.constructioncalc.core.model.Run
import com.thedirepack.constructioncalc.core.model.RunPath
import kotlin.math.PI
import kotlin.math.abs

/** Which end the cable is pulled from. FORWARD pulls toward the run's end (the path's order), REVERSE toward its start. */
enum class PullDirection(val label: String) {
    FORWARD("Toward the end"),
    REVERSE("Toward the start"),
}

/**
 * One conductor group in a pull.
 *
 * @property diameterIn one conductor's outside diameter (catalog value, else NEC Chapter 9 Table 5), null if unknown.
 * @property weightLbPerFt one conductor's weight; [weightEstimated] when it's [CableWeight]'s estimate.
 * @property fromCircuits true when it comes from the circuits assigned to the run.
 */
data class PullConductor(
    val count: Int,
    val size: ConductorSize,
    val insulation: InsulationType,
    val material: ConductorMaterial,
    val diameterIn: Double?,
    val weightLbPerFt: Double?,
    val weightEstimated: Boolean,
    val bearsTension: Boolean,
    val ground: Boolean,
    val fromCircuits: Boolean,
    val label: String = "",
) {
    /** `3 × 12 AWG THHN Cu` */
    val text: String get() = "$count × ${size.label} ${insulation.code}" + (if (material.isCopper) "" else " ${material.shortLabel}") + if (ground) " (ground)" else ""
}

/**
 * The pull through one path segment, in the order the cable travels.
 *
 * @property pathIndex the segment's index in [Run.path].
 * @property section the pull section (counted from the run's start) it belongs to.
 * @property radiusFt a bend's centerline radius; [pressure] its sidewall bearing pressure, lb/ft.
 */
data class PullStep(
    val pathIndex: Int,
    val segment: PathSegment,
    val section: Int,
    val tensionIn: Double,
    val tensionOut: Double,
    val lengthFt: Double,
    val radiusFt: Double? = null,
    val pressure: Double? = null,
)

/** The pull in one direction. [sectionTensions] is the tension at the far end of each section, by section index. */
data class PullPass(
    val direction: PullDirection,
    val steps: List<PullStep>,
    val sectionTensions: List<Double>,
) {
    val peakTensionStep: PullStep? get() = steps.maxByOrNull { it.tensionOut }
    val peakTension: Double get() = steps.maxOfOrNull { it.tensionOut } ?: 0.0
    val peakPressureStep: PullStep? get() = steps.filter { it.pressure != null }.maxByOrNull { it.pressure!! }
    val peakPressure: Double? get() = peakPressureStep?.pressure
}

/** A limit and where it came from. */
data class PullLimit(val label: String, val value: Double, val source: String)

/** Everything the Wire Pull Planner, Panels › Runs and exports show for one run's pull. */
data class PullResult(
    val run: Run,
    val setup: PullSetup,
    val conduit: ConduitSize?,
    val conductors: List<PullConductor>,
    val cableCount: Int,
    /** Largest conductor outside diameter, inches (d in the W, jam and clearance formulas). */
    val cableOdIn: Double?,
    /** Total weight of every conductor, lb/ft. */
    val weightLbPerFt: Double,
    val friction: Double,
    val configuration: CableConfiguration,
    val weightCorrection: Double?,
    val jamRatio: Double?,
    val jamWarning: Boolean,
    val clearanceIn: Double?,
    val forward: PullPass?,
    val reverse: PullPass?,
    val recommended: PullDirection?,
    /** Every known tension limit; [tensionLimit] is the lowest. */
    val tensionLimits: List<PullLimit>,
    /** Equipment with no rating entered yet: the tension limit doesn't cover them. */
    val missingRatings: List<String>,
    val pressureLimit: PullLimit?,
    val sectionDegrees: List<Double>,
    /** Routed length, inches (the path; without a path, the run's conduit length). */
    val routedLengthIn: Double?,
    /** Cable needed per conductor: routed length plus slack at both ends, inches. */
    val cableLengthIn: Double?,
    val notes: List<DesignNote>,
) {
    val tensionLimit: PullLimit? get() = tensionLimits.minByOrNull { it.value }
    val totalDegrees: Double get() = sectionDegrees.sum()
    fun pass(direction: PullDirection): PullPass? = if (direction == PullDirection.FORWARD) forward else reverse
    val recommendedPass: PullPass? get() = recommended?.let(::pass)

    /** Worst tension and pressure over the recommended direction (or the forward one). */
    val maxTension: Double? get() = (recommendedPass ?: forward)?.peakTension
    val maxPressure: Double? get() = (recommendedPass ?: forward)?.peakPressure

    val overTension: Boolean get() = tensionLimit?.let { l -> maxTension?.let { it > l.value } } == true
    val overPressure: Boolean get() = pressureLimit?.let { l -> maxPressure?.let { it > l.value } } == true

    /** Total conductor footage for the pull (every conductor × cable needed), feet. */
    val conductorFeet: Double? get() = cableLengthIn?.let { len -> conductors.sumOf { it.count } * len / 12.0 }
}

/**
 * The cable pull for a run, both directions (pure).
 *
 * - Cables: the conductors of the circuits assigned to the run, as the design engine sized them
 *   (the run's fill entries), plus any added by hand. Diameters from NEC Chapter 9 Table 5 unless
 *   a catalog value is entered; weights estimated ([CableWeight]) unless entered.
 * - Each pull section (between pull points) starts from its incoming tension. Straights and bends
 *   use [PullFormulas]: exact horizontal bends, the derived sag and hump forms for vertical bends.
 *   Reversing the pull reverses the segment order and every incline; a bend's plane is geometry and
 *   stays the same.
 * - The recommended direction has the lower peak as a fraction of its limits (tension and sidewall
 *   pressure each against their own limit); without limits, the lower peak tension.
 */
object PullAnalysis {

    fun analyze(project: Project, runId: String, design: ProjectDesign = ProjectAnalysis.analyze(project)): PullResult? {
        val run = project.run(runId) ?: return null
        return analyze(run, design.runs[runId])
    }

    fun analyze(run: Run, runDesign: RunDesign?): PullResult {
        val setup = run.pull ?: PullSetup()
        val notes = mutableListOf<DesignNote>()
        val raceway = run.raceway
        val conduit = raceway?.let { r -> r.tradeSize?.let { ConduitTable.find(r.conduitType, it) } ?: runDesign?.conduit }
        val conductors = conductors(setup, runDesign)
        val count = conductors.sumOf { it.count }
        val od = conductors.mapNotNull { it.diameterIn }.maxOrNull()
        val weight = conductors.sumOf { it.count * (it.weightLbPerFt ?: 0.0) }
        val id = conduit?.internalDiameterIn
        val mu = setup.coefficient

        if (raceway == null) notes += warn("Set the run's conduit to plan a pull.")
        else if (conduit == null) notes += warn("Pick the conduit size (or assign circuits so the design picks one).")
        if (count == 0) notes += warn("Add cables: assign circuits to the run in Panels, or add cables here.")
        if (conductors.any { it.diameterIn == null }) notes += warn("Enter a diameter for cables with no ${CodeRule.CONDUCTOR_DIMENSIONS.cite} row.")

        val configuration = setup.configuration ?: if (id != null && od != null) PullFormulas.configuration(count, id, od) else PullFormulas.configuration(count, 0.0, 0.0)
        val w = if (id != null && od != null) PullFormulas.weightCorrection(configuration, id, od) else if (configuration == CableConfiguration.SINGLE) 1.0 else null
        if (id != null && od != null && w == null) notes += warn("The cables don't fit this conduit in the ${configuration.label.lowercase()} configuration.")

        val jam = if (id != null && od != null && count > 0) PullFormulas.jamRatio(id, od) else null
        val jamWarning = jam != null && count == 3 && PullFormulas.inJamRange(jam)
        val clearance = if (id != null && od != null && count > 0) PullFormulas.clearance(count, id, od) else null
        if (id != null && od != null && count > 0) notes += fitNotes(id, od, count)

        // Fill: the same Chapter 9 check as Conduit Fill and the design engine.
        if (conduit != null && count > 0) {
            val groups = conductors.map { ConductorGroup(it.count, FillConductor.Insulated(it.insulation, it.size)) }
            val fill = ConduitFill.analyze(conduit, groups, raceway?.nipple == true)
            if (!fill.withinLimit) notes += warn("Fill ${fmt(fill.percentFill, 1)}% is over the ${fill.limitPercent}% limit (NEC ${CodeRule.FILL_PERCENT.cite}).")
        }

        val path = run.path
        val sectionDegrees = if (path.isEmpty()) emptyList() else RunPath.degreesPerSection(path)
        val section = RacewayBends.section(raceway?.conduitType ?: ConduitType.EMT)
        sectionDegrees.forEachIndexed { i, deg ->
            if (deg > RacewayBends.MAX_DEGREES_BETWEEN_PULL_POINTS + 1e-9) {
                val where = if (sectionDegrees.size == 1) "The path has" else "Pull section ${i + 1} has"
                notes += warn("$where ${fmt(deg, 1)}° of bends: over 360° between pull points (${Code.label} $section).")
            }
        }

        // Tension in both directions.
        val size = conduit?.tradeSize ?: raceway?.tradeSize
        val canPull = path.isNotEmpty() && w != null && count > 0 && conduit != null
        if (path.isEmpty()) notes += info("Add the run's path (straights, bends, pull points) to calculate tension.")
        val missingRadius = path.any { it is PathSegment.Bend && it.radius == null } && size == null
        if (missingRadius) notes += warn("Bends without a radius need the conduit size for the ${CodeRule.BEND_RADIUS.cite} radius.")
        val forward = if (canPull && !missingRadius) pass(path, PullDirection.FORWARD, setup, raceway!!.conduitType, size, weight, mu, w!!, configuration, notes) else null
        val reverse = if (canPull && !missingRadius) pass(path, PullDirection.REVERSE, setup, raceway!!.conduitType, size, weight, mu, w!!, configuration, null) else null

        // Limits.
        val tensionLimits = mutableListOf<PullLimit>()
        conductorLimit(conductors, notes)?.let { tensionLimits += it }
        setup.equipment.filter { it.pounds != null }.forEach { tensionLimits += PullLimit(it.name, it.pounds!!, "Rating entered for this run") }
        val missing = setup.equipment.filter { it.pounds == null }.map { it.name }
        if (missing.isNotEmpty()) notes += info("No rating entered for: ${missing.joinToString()}. The tension limit covers only the conductors and the equipment rated here.")
        val pressureLimit = setup.pressureLimit?.let { PullLimit("Cable sidewall pressure rating", it, "Entered for this run") }
            ?: conductors.minOfOrNull { PullRatings.buildingWirePressure(it.size) }?.let {
                PullLimit("Building wire sidewall pressure (manufacturer guidance)", it, PullRatings.BUILDING_WIRE_PRESET_LABEL)
            }

        val tensionLimit = tensionLimits.minByOrNull { it.value }
        val recommended = recommend(forward, reverse, tensionLimit, pressureLimit)
        listOfNotNull(forward, reverse).forEach { p ->
            val peak = p.peakTension
            if (tensionLimit != null && peak > tensionLimit.value) {
                notes += warn("${p.direction.label}: peak tension ${lb(peak)} is over the ${lb(tensionLimit.value)} limit (${tensionLimit.label}).")
            }
            val peakP = p.peakPressure
            if (pressureLimit != null && peakP != null && peakP > pressureLimit.value) {
                notes += warn("${p.direction.label}: sidewall pressure ${fmt(peakP, 0)} lb/ft is over the ${fmt(pressureLimit.value, 0)} lb/ft limit (${pressureLimit.label}).")
            }
        }

        // Assumptions.
        notes += info(
            "Friction μ = ${fmt(mu, 2)}: " + if (setup.frictionOverride != null) "entered for this run." else "${setup.friction.label}, ${setup.friction.source.lowercase()} (guidance, not a code value).",
        )
        if (w != null && count > 0) {
            val how = if (setup.configuration != null) "chosen for this run" else "assumed from ${count} cable${if (count == 1) "" else "s"} and D/d"
            notes += info("${configuration.label} configuration ($how): W = ${fmt(w, 3)} (IEEE 1185).")
            if (count == 2) notes += info("Two cables use the triangular W and sidewall pressure forms (an assumption).")
            if (count >= 4) notes += info("Four or more cables: W = 1 + 2(d/(D−d))² and the cradled sidewall pressure form (an assumption; the Southwire workbook uses W = 1.4 for this case).")
        }
        if (conductors.any { it.weightEstimated }) notes += info("Cable weight is estimated from NEC ${CodeRule.CONDUCTOR_DIMENSIONS.cite} and ${CodeRule.CONDUCTOR_PROPERTIES.cite} areas and typical material densities. Enter catalog weights for a final plan.")
        if (path.any { it is PathSegment.Bend && it.radius == null } && size != null) {
            notes += info("Bends without a radius use the NEC ${CodeRule.BEND_RADIUS.cite} minimum (${RacewayBends.column(raceway?.conduitType ?: ConduitType.EMT).label.lowercase()} column).")
        }
        if (path.any { it is PathSegment.Bend && it.plane != BendPlane.HORIZONTAL }) {
            notes += info("Vertical bends assume the tension holds the cable against the inside of the bend.")
        }
        if (forward != null && setup.incomingTension.all { it == 0.0 }) notes += info("Incoming tension is 0 lb. Add the feed-in (reel back) tension if you know it.")

        val routed = if (path.isNotEmpty()) RunPath.lengthInches(path, raceway?.conduitType, size) else raceway?.length?.toQuantity()?.value?.toDouble()
        val slack = setup.slackPerEnd?.toQuantity()?.value?.toDouble() ?: 0.0
        val cableLength = routed?.let { it + 2 * slack }

        return PullResult(
            run, setup, conduit, conductors, count, od, weight, mu, configuration, w, jam, jamWarning, clearance,
            forward, reverse, recommended, tensionLimits, missing, pressureLimit, sectionDegrees, routed, cableLength,
            notes.distinct(),
        )
    }

    /** Jam and clearance notes for [count] cables of [cableOd] in a conduit of [conduitId] (IEEE 1185). */
    fun fitNotes(conduitId: Double, cableOd: Double, count: Int): List<DesignNote> = buildList {
        val jam = PullFormulas.jamRatio(conduitId, cableOd)
        if (count == 3 && PullFormulas.inJamRange(jam)) {
            add(warn("Jam ratio ${fmt(jam, 2)} (1.05 × D/d) is between 2.8 and 3.2: three cables can jam in a bend (NEC ${CodeRule.JAM_RATIO.cite}; IEEE 1185)."))
        }
        val clearance = PullFormulas.clearance(count, conduitId, cableOd)
        when {
            clearance == null -> {}
            clearance < PullFormulas.CLEARANCE_MINIMUM_IN -> add(warn("Clearance ${fmt(clearance, 2)} in. is under the 1/4 in. minimum (IEEE 1185)."))
            clearance < PullFormulas.CLEARANCE_RECOMMENDED_IN -> add(info("Clearance ${fmt(clearance, 2)} in. is under the recommended 1/2 in. (IEEE 1185)."))
        }
    }

    /** The run's conductors: the circuits' (from the design) and the hand-added ones. */
    fun conductors(setup: PullSetup, runDesign: RunDesign?): List<PullConductor> {
        val fromCircuits = if (setup.cablesFromCircuits && runDesign != null) {
            runDesign.entries
                .groupBy { listOf(it.size, it.insulation, it.material, it.ground) }
                .map { (_, list) ->
                    val e = list.first()
                    val d = InsulatedConductorArea.diameter(e.insulation, e.size)
                    val wt = CableWeight.estimate(e.size, e.insulation, e.material)
                    PullConductor(list.sumOf { it.count }, e.size, e.insulation, e.material, d, wt, true, bearsTension = !e.ground, ground = e.ground, fromCircuits = true)
                }
        } else {
            emptyList()
        }
        val manual = setup.cables.map { c -> manual(c) }
        return fromCircuits + manual
    }

    fun manual(c: PullCable): PullConductor {
        val d = c.diameterIn ?: InsulatedConductorArea.diameter(c.insulation, c.size)
        val estimate = CableWeight.estimate(c.size, c.insulation, c.material)
        return PullConductor(
            c.count, c.size, c.insulation, c.material, d, c.weightLbPerFt ?: estimate, c.weightLbPerFt == null,
            c.bearsTension, ground = false, fromCircuits = false, label = c.label,
        )
    }

    /**
     * Southwire's conductor limit for the conductors carrying the pull: S × cmil × n (× 0.8 above
     * three). For mixed sizes or metals the flyer's equation doesn't apply directly, so the smallest
     * conductor and the lower stress are used for all of them (conservative), with a note.
     */
    private fun conductorLimit(conductors: List<PullConductor>, notes: MutableList<DesignNote>): PullLimit? {
        val bearing = conductors.filter { it.bearsTension }
        val n = bearing.sumOf { it.count }
        if (n == 0) return null
        if (conductors.any { it.ground && !it.bearsTension }) notes += info("The equipment grounding conductor is left out of the conductor tension limit (Southwire tension flyer).")
        val sizes = bearing.map { it.size }.distinct()
        val metals = bearing.map { it.material.isCopper }.distinct()
        val source = "Southwire Maximum Pulling Tension flyer (2024): S × cmil × n" + if (n > 3) " × 0.8" else ""
        return if (sizes.size == 1 && metals.size == 1) {
            val c = bearing.first()
            PullLimit("Conductors (${n} × ${c.size.label})", PullRatings.conductorTension(c.material, c.size, n), source)
        } else {
            val smallest = bearing.minBy { it.size.circularMils }.size
            val material = bearing.minBy { PullRatings.conductorStress(it.material) }.material
            notes += info("Mixed conductor sizes or metals: the conductor limit uses the smallest (${smallest.label}) for all ${n} (conservative).")
            PullLimit("Conductors (${n}, smallest ${smallest.label})", PullRatings.conductorTension(material, smallest, n), source)
        }
    }

    private fun pass(
        path: List<PathSegment>,
        direction: PullDirection,
        setup: PullSetup,
        type: ConduitType,
        size: TradeSize?,
        weight: Double,
        mu: Double,
        w: Double,
        configuration: CableConfiguration,
        notes: MutableList<DesignNote>?,
    ): PullPass {
        // Sections as (section index, list of (path index, segment)).
        val sections = mutableListOf<MutableList<Pair<Int, PathSegment>>>(mutableListOf())
        path.forEachIndexed { i, s -> if (s is PathSegment.PullPoint) sections += mutableListOf<Pair<Int, PathSegment>>() else sections.last() += i to s }
        val reverse = direction == PullDirection.REVERSE
        val order = sections.indices.let { if (reverse) it.reversed() else it.toList() }
        val steps = mutableListOf<PullStep>()
        val ends = MutableList(sections.size) { 0.0 }
        var slope = 0.0 // degrees, along the direction of travel
        var lastVerticalExit: Double? = null
        order.forEach { sectionIndex ->
            var t = setup.incoming(sectionIndex)
            val segs = sections[sectionIndex].let { if (reverse) it.reversed() else it }
            segs.forEach { (pathIndex, segment) ->
                val tIn = t
                when (segment) {
                    is PathSegment.Straight -> {
                        val s = if (reverse) -segment.slopeDegrees else segment.slopeDegrees
                        if (notes != null && lastVerticalExit != null && abs(lastVerticalExit!! - s) > 1.0) {
                            notes += info("Path check: a straight slopes ${fmt(s, 1)}° right after a vertical bend that leaves at ${fmt(lastVerticalExit!!, 1)}°. Check the slopes and bend planes.")
                        }
                        lastVerticalExit = null
                        slope = s
                        val ft = segment.lengthInches / 12.0
                        t = PullFormulas.straight(t, weight, ft, mu, w, slope * PI / 180)
                        steps += PullStep(pathIndex, segment, sectionIndex, tIn, t, ft)
                    }
                    is PathSegment.Bend -> {
                        val rIn = RunPath.radiusInches(segment, type, size)!!
                        val rFt = rIn / 12.0
                        val theta = segment.angleDegrees * PI / 180
                        t = when (segment.plane) {
                            BendPlane.HORIZONTAL -> PullFormulas.horizontalBend(t, weight, rFt, theta, mu, w)
                            BendPlane.VERTICAL_UP -> PullFormulas.sagBend(t, weight, rFt, theta, mu, w, slope * PI / 180)
                                .also { slope += segment.angleDegrees; lastVerticalExit = slope }
                            BendPlane.VERTICAL_DOWN -> PullFormulas.humpBend(t, weight, rFt, theta, mu, w, slope * PI / 180)
                                .also { slope -= segment.angleDegrees; lastVerticalExit = slope }
                        }
                        val pressure = PullFormulas.sidewallPressure(configuration, w, t, rFt)
                        steps += PullStep(pathIndex, segment, sectionIndex, tIn, t, rIn * theta / 12.0, rFt, pressure)
                    }
                    is PathSegment.PullPoint -> {}
                }
            }
            ends[sectionIndex] = t
        }
        return PullPass(direction, steps, ends)
    }

    private fun recommend(forward: PullPass?, reverse: PullPass?, tension: PullLimit?, pressure: PullLimit?): PullDirection? {
        if (forward == null || reverse == null) return forward?.direction
        fun score(p: PullPass): Double {
            val t = tension?.let { p.peakTension / it.value }
            val sp = pressure?.let { l -> p.peakPressure?.let { it / l.value } }
            return listOfNotNull(t, sp).maxOrNull() ?: p.peakTension
        }
        return if (score(reverse) < score(forward) - 1e-9) PullDirection.REVERSE else PullDirection.FORWARD
    }

    private fun info(text: String) = DesignNote(text, NoteLevel.INFO)
    private fun warn(text: String) = DesignNote(text, NoteLevel.WARNING)
    internal fun fmt(v: Double, decimals: Int) = Decimals.trim(v, decimals)
    fun lb(v: Double) = String.format(java.util.Locale.US, "%,.0f lb", v)
}

/** One-line pull summaries shared by Panels › Runs, project text and the pull report. */
object PullText {
    /** `Pull 1,234 lb of 1,500 lb (Rope) · SWBP 320 of 1,000 lb/ft · bends 90°, 180° · toward the end`, or null with no path. */
    fun summary(r: PullResult): String? {
        val pass = r.recommendedPass ?: return null
        val parts = buildList {
            add("Pull ${PullAnalysis.lb(pass.peakTension)}" + (r.tensionLimit?.let { " of ${PullAnalysis.lb(it.value)} (${it.label})" } ?: ""))
            pass.peakPressure?.let { p -> add("SWBP ${PullAnalysis.fmt(p, 0)}" + (r.pressureLimit?.let { " of ${PullAnalysis.fmt(it.value, 0)}" } ?: "") + " lb/ft") }
            if (r.sectionDegrees.isNotEmpty()) add("bends " + r.sectionDegrees.joinToString(", ") { "${PullAnalysis.fmt(it, 1)}°" })
            add("pull ${pass.direction.label.lowercase()}")
            if (r.jamWarning) add("jam risk")
        }
        return parts.joinToString(" · ")
    }

    /** Warnings for the pull (over limits, jam, clearance, 360°, fit). */
    fun warnings(r: PullResult): List<String> = r.notes.filter { it.isWarning }.map { it.text }
}

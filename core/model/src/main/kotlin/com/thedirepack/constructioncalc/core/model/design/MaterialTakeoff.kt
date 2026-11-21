package com.thedirepack.constructioncalc.core.model.design

import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.InsulationType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import com.thedirepack.constructioncalc.core.model.Project

/** Wire of one size, insulation and metal across the project, in feet. */
data class WireTakeoff(
    val size: ConductorSize,
    val insulation: InsulationType,
    val material: ConductorMaterial,
    val feet: Double,
    val runNames: List<String>,
) {
    /** `12 AWG THHN Cu` */
    val label: String get() = "${size.label} ${insulation.code} ${material.shortLabel}"
}

/** Conduit of one type and size across the project, in feet; [size] null when no size is chosen or designed. */
data class ConduitTakeoff(val type: ConduitType, val size: TradeSize?, val feet: Double, val runNames: List<String>) {
    val label: String get() = label(UnitSystem.IMPERIAL)

    /** `3/4" EMT`, or `3/4" (21) EMT` in metric. */
    fun label(units: UnitSystem): String =
        listOfNotNull(size?.let { if (units == UnitSystem.METRIC) "${it.display} (${it.metricDesignator})" else it.display }, type.displayName).joinToString(" ")
}

data class MaterialTakeoff(val wire: List<WireTakeoff>, val conduit: List<ConduitTakeoff>, val notes: List<DesignNote>) {
    val isEmpty: Boolean get() = wire.isEmpty() && conduit.isEmpty()
}

/**
 * Wire and conduit quantities for a whole project (pure), for the project report, text export and
 * Reel Length. For each conduit run:
 * - wire: every conductor in the run (the circuits' conductors as designed, plus cables added in
 *   the pull settings) × (routed length + slack at both ends, from the run's pull settings);
 * - conduit: the routed length, by conduit type and size (chosen, or the size the design picked).
 * Runs with no length are left out, with a note. A circuit through several runs is counted run by
 * run, so slack is added at each run's ends.
 */
object MaterialTakeoffs {
    fun of(project: Project, design: ProjectDesign = ProjectAnalysis.analyze(project)): MaterialTakeoff {
        val wire = linkedMapOf<Triple<ConductorSize, InsulationType, ConductorMaterial>, Pair<Double, MutableList<String>>>()
        val conduit = linkedMapOf<Pair<ConduitType, TradeSize?>, Pair<Double, MutableList<String>>>()
        val notes = mutableListOf<DesignNote>()
        project.runs.filter { it.raceway != null }.forEach { run ->
            val rd = design.runs[run.id]
            val pull = PullAnalysis.analyze(run, rd)
            val cableIn = pull.cableLengthIn
            val routedIn = pull.routedLengthIn
            if (routedIn == null || routedIn <= 0.0) {
                if (pull.conductors.isNotEmpty() || rd?.circuits?.isNotEmpty() == true) notes += DesignNote("${run.name} has no length, so it's left out of the takeoff.", NoteLevel.WARNING)
                return@forEach
            }
            val key = run.raceway!!.conduitType to (run.raceway.tradeSize ?: rd?.conduit?.tradeSize)
            val (feet, names) = conduit[key] ?: (0.0 to mutableListOf())
            conduit[key] = (feet + routedIn / 12.0) to names.also { it += run.name }
            pull.conductors.forEach { c ->
                val k = Triple(c.size, c.insulation, c.material)
                val (f, n) = wire[k] ?: (0.0 to mutableListOf())
                wire[k] = (f + c.count * (cableIn ?: routedIn) / 12.0) to n.also { if (run.name !in it) it += run.name }
            }
        }
        return MaterialTakeoff(
            wire = wire.map { (k, v) -> WireTakeoff(k.first, k.second, k.third, v.first, v.second) }
                .sortedWith(compareBy<WireTakeoff>({ it.material.ordinal }, { it.size.ordinal }, { it.insulation.ordinal })),
            conduit = conduit.map { (k, v) -> ConduitTakeoff(k.first, k.second, v.first, v.second) }
                .sortedWith(compareBy<ConduitTakeoff>({ it.type.ordinal }, { it.size?.ordinal ?: -1 })),
            notes = notes,
        )
    }
}

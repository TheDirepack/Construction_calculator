package com.thedirepack.constructioncalc.core.model.design

import com.thedirepack.constructioncalc.core.electrical.AmbientCorrection
import com.thedirepack.constructioncalc.core.electrical.ConductorCountAdjustment
import com.thedirepack.constructioncalc.core.electrical.ConductorImpedance
import com.thedirepack.constructioncalc.core.electrical.ConductorResistance
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.ConductorSizing
import com.thedirepack.constructioncalc.core.electrical.ConduitSize
import com.thedirepack.constructioncalc.core.electrical.ConduitTable
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.CurrentCarryingConductors
import com.thedirepack.constructioncalc.core.electrical.EquipmentGroundingConductor
import com.thedirepack.constructioncalc.core.electrical.InsulatedConductorArea
import com.thedirepack.constructioncalc.core.electrical.InsulationType
import com.thedirepack.constructioncalc.core.electrical.Table9Conduit
import com.thedirepack.constructioncalc.core.electrical.WireSizingRequest
import com.thedirepack.constructioncalc.core.electrical.WireSizingResult
import com.thedirepack.constructioncalc.core.model.Circuit
import com.thedirepack.constructioncalc.core.model.CircuitKind
import com.thedirepack.constructioncalc.core.model.ConductorSpec
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.Run
import kotlin.math.sqrt

/** A circuit's computed design: what wire it needs and why. */
data class CircuitDesign(
    val panel: Panel,
    val circuit: Circuit,
    val conductor: ConductorSpec,
    /** Load current from the circuit's VA, if given. */
    val amps: Double?,
    /** Current-carrying conductors in the most crowded run it passes through (310.15(C)(1)). */
    val currentCarrying: Int,
    val sizing: WireSizingResult?,
    /** Minimum size for ampacity and protection. */
    val minimumSize: ConductorSize?,
    /** Size after any upsizing for voltage drop. */
    val size: ConductorSize?,
    val groundSize: ConductorSize?,
    /** One-way length in inches (circuit override, else the sum of its runs), null if unknown. */
    val lengthInches: Double?,
    val voltageDropVolts: Double?,
    val voltageDropPercent: Double?,
    val notes: List<String>,
)

/** One conductor line in a run's fill: e.g. 3 × 12 AWG THHN. */
data class FillEntry(val count: Int, val size: ConductorSize, val insulation: InsulationType, val areaEach: Double, val ground: Boolean)

/** Fill check result for a run's conduit. */
enum class FillStatus { OK, OVER, NO_CONDUIT, TOO_MANY_FOR_ANY_SIZE, EMPTY }

/** A run's computed design: everything in its conduit, its fill check and its derating. */
data class RunDesign(
    val run: Run,
    val circuits: List<Pair<Panel, Circuit>>,
    val currentCarrying: Int,
    /** Table 310.15(C)(1) factor for [currentCarrying] (1.0 for nipples ≤ 24"). */
    val adjustmentFactor: Double,
    /** Effective ambient along this run, including the rooftop adder (310.15(B)(2)). */
    val ambientC: Double,
    /** Table 310.15(B)(1)(1) factor at [ambientC] for the project's insulation column (null: too hot). */
    val ambientFactor: Double?,
    val fillStatus: FillStatus,
    val entries: List<FillEntry>,
    val totalConductors: Int,
    val totalAreaSqIn: Double,
    val fillLimitPercent: Int,
    /** The run's chosen conduit, or the smallest that fits when none is chosen. */
    val conduit: ConduitSize?,
    val fillPercent: Double?,
    val smallestFitting: ConduitSize?,
    val notes: List<String>,
)

data class PanelDesign(
    val panel: Panel,
    val phaseVa: Map<Phase, Double>,
    val totalVa: Double,
    /** Per-phase current from connected VA. */
    val phaseAmps: Map<Phase, Double>,
    val conflicts: List<String>,
    val notes: List<String>,
)

data class ProjectDesign(
    val panels: List<PanelDesign>,
    val circuits: Map<String, CircuitDesign>,
    val runs: Map<String, RunDesign>,
)

/**
 * Links a project's panels, circuits and runs into a design (NEC 2023):
 * - each run counts the current-carrying conductors of every circuit in it (310.15(E)/(F));
 * - each circuit is sized for its breaker and load at the worst conditions along its runs:
 *   most conductors, hottest ambient, rooftop adder ([ConductorSizing]);
 * - voltage drop over the circuit's length (NEC Table 9 effective Z at 0.85 PF), upsizing to
 *   the project's target if enabled, with the EGC increased per 250.122(B);
 * - each run's fill from the resulting conductors (Chapter 9 Tables 1, 4, 5).
 * Pure function of the project: call it whenever the project changes.
 */
object ProjectAnalysis {
    private const val POWER_FACTOR = 0.85
    private const val MAX_VD_UPSIZE_STEPS = 8

    fun analyze(project: Project): ProjectDesign {
        val runsById = project.runs.associateBy { it.id }
        val allCircuits = project.panels.flatMap { p -> p.circuits.map { p to it } }
            .filter { (_, c) -> c.kind != CircuitKind.SPACE }

        // 1. Current-carrying conductors per run.
        fun cccOf(panel: Panel, c: Circuit) = if (c.kind == CircuitKind.LOAD) {
            CurrentCarryingConductors.count(c.poles, c.neutral, panel.system.supply, c.nonlinear)
        } else {
            0
        }
        val runCcc = project.runs.associate { run ->
            run.id to allCircuits.filter { (_, c) -> run.id in c.runIds }.sumOf { (p, c) -> cccOf(p, c) }
        }

        // 2. Circuits.
        val circuits = allCircuits.associate { (panel, c) -> c.id to designCircuit(project, panel, c, runsById, runCcc) }

        // 3. Runs.
        val runs = project.runs.associate { run -> run.id to designRun(project, run, allCircuits, circuits, runCcc.getValue(run.id)) }

        // 4. Panels.
        val panels = project.panels.map { designPanel(it) }
        return ProjectDesign(panels, circuits, runs)
    }

    /** Load current for [c]'s VA in [panel], or null. */
    fun amps(panel: Panel, c: Circuit): Double? {
        val va = c.loadVa ?: return null
        val system = panel.system
        return when {
            c.poles <= 1 -> va / (system.lineToNeutral ?: system.lineToLine)
            c.poles == 2 || system.phases == 1 -> va / system.lineToLine
            else -> va / (sqrt(3.0) * system.lineToLine)
        }
    }

    private fun designCircuit(
        project: Project,
        panel: Panel,
        c: Circuit,
        runsById: Map<String, Run>,
        runCcc: Map<String, Int>,
    ): CircuitDesign {
        val spec = c.conductor ?: project.design.conductor
        val notes = mutableListOf<String>()
        val amps = amps(panel, c)
        val runs = c.runIds.mapNotNull { runsById[it] }
        val ownCcc = CurrentCarryingConductors.count(c.poles, c.neutral, panel.system.supply, c.nonlinear)
        // Nipples (≤ 24") aren't subject to count adjustment (310.15(C)(1)(b)).
        val ccc = (runs.filter { it.raceway?.nipple != true }.mapNotNull { runCcc[it.id] } + ownCcc).max()
        val ambient = (runs.map { it.raceway?.ambientC ?: project.design.ambientC } + project.design.ambientC).max()
        val rooftop = runs.any { it.raceway?.rooftop == true }
        val length = c.length?.toQuantity()?.value?.toDouble()
            ?: runs.takeIf { it.isNotEmpty() && it.all { r -> r.raceway?.length != null } }
                ?.sumOf { it.raceway!!.length!!.toQuantity().value.toDouble() }
        if (c.length == null && runs.any { it.raceway?.length == null }) notes += "Some runs have no length; voltage drop not checked."

        if (c.kind != CircuitKind.LOAD) {
            return CircuitDesign(panel, c, spec, amps, ccc, null, null, null, null, length, null, null, notes)
        }
        val breaker = c.breakerAmps
        if (breaker == null) {
            notes += "Set a breaker size to size the wire."
            return CircuitDesign(panel, c, spec, amps, ccc, null, null, null, null, length, null, null, notes)
        }

        val sizing = ConductorSizing.minimumSize(
            WireSizingRequest(
                ocpdAmps = breaker,
                material = spec.material,
                loadAmps = amps,
                continuousAmps = if (c.continuous) amps ?: 0.0 else 0.0,
                insulation = spec.insulationRating,
                termination = spec.terminationRating,
                ambientC = ambient,
                rooftopUnder3_4In = rooftop,
                xhhw2 = spec.insulation == InsulationType.XHHW,
                currentCarrying = ccc,
                multipleCordReceptacles = c.multipleCordReceptacles,
            ),
        )
        notes += sizing.notes
        val minimum = sizing.size
        var size = minimum
        var vd: Pair<Double, Double>? = null
        if (minimum != null && amps != null && length != null) {
            val target = project.design.voltageDropTargetPercent
            var current = voltageDrop(panel, c, amps, length, minimum, spec, runs)
            var candidate: ConductorSize = minimum
            var steps = 0
            while (project.design.upsizeForVoltageDrop && current != null && current.second > target && steps < MAX_VD_UPSIZE_STEPS) {
                val next = ConductorSize.entries.getOrNull(candidate.ordinal + 1) ?: break
                val nextVd = voltageDrop(panel, c, amps, length, next, spec, runs) ?: break
                candidate = next
                current = nextVd
                steps++
            }
            if (candidate != minimum) {
                notes += "Upsized from ${minimum.label} to ${candidate.label} for ${fmt(target)}% voltage drop."
                size = candidate
            }
            vd = current
            if (current != null && current.second > target) notes += "Voltage drop ${fmt(current.second)}% exceeds the ${fmt(target)}% target."
        } else if (amps == null) {
            notes += "Enter the load (VA) to check voltage drop."
        }
        val ground = if (minimum != null && size != null) {
            EquipmentGroundingConductor.sizeWithUpsize(breaker, spec.material, minimum, size)
        } else {
            null
        }
        return CircuitDesign(panel, c, spec, amps, ccc, sizing, minimum, size, ground, length, vd?.first, vd?.second, notes)
    }

    /** (volts, percent) for one-way [lengthInches]. */
    private fun voltageDrop(
        panel: Panel,
        c: Circuit,
        amps: Double,
        lengthInches: Double,
        size: ConductorSize,
        spec: ConductorSpec,
        runs: List<Run>,
    ): Pair<Double, Double>? {
        val table9 = runs.firstNotNullOfOrNull { it.raceway?.conduitType }?.let(::table9Conduit) ?: Table9Conduit.PVC
        val z = ConductorImpedance.row(size, spec.material)?.effectiveZ(table9, POWER_FACTOR)
            ?: ConductorResistance.ohmsPerKft(size, spec.material)
            ?: return null
        val threePhase = c.poles == 3 && panel.system.phases == 3
        val factor = if (threePhase) sqrt(3.0) else 2.0
        val volts = if (c.poles == 1) (panel.system.lineToNeutral ?: panel.system.lineToLine) else panel.system.lineToLine
        val feet = lengthInches / 12.0
        val drop = factor * amps * feet * z / 1000.0
        return drop to 100.0 * drop / volts
    }

    /** Steel-type raceways use Table 9's steel columns; nonmetallic ones use PVC. */
    fun table9Conduit(type: ConduitType): Table9Conduit = when (type) {
        ConduitType.EMT, ConduitType.RMC, ConduitType.IMC, ConduitType.FMC, ConduitType.LFMC -> Table9Conduit.STEEL
        else -> Table9Conduit.PVC
    }

    private fun designRun(
        project: Project,
        run: Run,
        all: List<Pair<Panel, Circuit>>,
        designs: Map<String, CircuitDesign>,
        ccc: Int,
    ): RunDesign {
        val notes = mutableListOf<String>()
        val members = all.filter { (_, c) -> run.id in c.runIds }
        val entries = mutableListOf<FillEntry>()
        var largestGround: Pair<ConductorSize, InsulationType>? = null
        members.forEach { (_, c) ->
            val d = designs[c.id] ?: return@forEach
            val size = d.size ?: return@forEach
            val insulation = d.conductor.insulation
            val wires = c.poles + if (c.neutral) 1 else 0
            val area = InsulatedConductorArea.area(insulation, size)
            if (area == null) {
                notes += "No Table 5 area for ${size.label} ${insulation.code}."
                return@forEach
            }
            entries += FillEntry(wires, size, insulation, area, ground = false)
            d.groundSize?.let { g -> if (largestGround == null || g > largestGround!!.first) largestGround = g to insulation }
        }
        // One EGC sized for the largest circuit serves the raceway (250.122(C)).
        largestGround?.let { (g, ins) ->
            InsulatedConductorArea.area(ins, g)?.let { entries += FillEntry(1, g, ins, it, ground = true) }
            if (members.size > 1) notes += "One equipment grounding conductor (${g.label}) sized for the largest circuit (250.122(C))."
        }
        val total = entries.sumOf { it.count }
        val area = entries.sumOf { it.count * it.areaEach }
        val raceway = run.raceway
        val limit = when {
            raceway?.nipple == true -> 60
            total == 1 -> 53
            total == 2 -> 31
            else -> 40
        }
        val smallest = raceway?.let { r -> ConduitTable.sizes(r.conduitType).firstOrNull { it.allowedAreaSqIn(limit) >= area - 1e-9 } }
        val conduit = raceway?.let { r -> r.tradeSize?.let { ConduitTable.find(r.conduitType, it) } ?: smallest }
        val fill = conduit?.let { 100.0 * area / it.area100SqIn }
        if (raceway == null && members.isNotEmpty()) notes += "Set the conduit for this run to check fill."
        if (conduit != null && fill != null && fill > limit + 1e-9) {
            notes += "Over ${limit}% fill (${fmt(fill)}%). Smallest that fits: ${smallest?.displayName ?: "none in Table 4"}."
        }
        if (raceway != null && smallest == null && area > 0) notes += "Too many conductors for any ${raceway.conduitType.displayName}."
        val insulation = project.design.conductor
        val ambient = AmbientCorrection.effectiveAmbient(
            raceway?.ambientC ?: project.design.ambientC,
            raceway?.rooftop == true,
            insulation.insulation == InsulationType.XHHW,
        )
        val ambientFactor = AmbientCorrection.factor(ambient, insulation.insulationRating)
        val adjustment = if (raceway?.nipple == true) 1.0 else ConductorCountAdjustment.factor(ccc)
        if (adjustment < 1.0) {
            notes += "$ccc current-carrying conductors: ampacity ×$adjustment (Table 310.15(C)(1)) for every circuit in this run."
        }
        if (ambientFactor != null && ambientFactor != 1.0) {
            notes += "${fmt(ambient)} °C: ampacity ×$ambientFactor (Table 310.15(B)(1)(1), ${insulation.insulationRating.label} column)."
        }
        val status = when {
            area <= 0.0 -> FillStatus.EMPTY
            raceway == null -> FillStatus.NO_CONDUIT
            smallest == null && raceway.tradeSize == null -> FillStatus.TOO_MANY_FOR_ANY_SIZE
            fill != null && fill > limit + 1e-9 -> FillStatus.OVER
            conduit == null -> FillStatus.TOO_MANY_FOR_ANY_SIZE
            else -> FillStatus.OK
        }
        return RunDesign(run, members, ccc, adjustment, ambient, ambientFactor, status, entries, total, area, limit, conduit, fill, smallest, notes)
    }

    private fun designPanel(panel: Panel): PanelDesign {
        val phases = panel.system.phases
        val va = mutableMapOf<Phase, Double>()
        panel.circuits.filter { it.kind == CircuitKind.LOAD }.forEach { c ->
            val load = c.loadVa ?: return@forEach
            val ps = PanelLayout.phasesOf(c, phases)
            ps.forEach { va[it] = (va[it] ?: 0.0) + load / ps.size }
        }
        val used = if (phases >= 3) Phase.entries else listOf(Phase.A, Phase.B)
        val phaseVa = used.associateWith { va[it] ?: 0.0 }
        val vln = panel.system.lineToNeutral?.toDouble() ?: (panel.system.lineToLine / sqrt(3.0))
        val phaseAmps = phaseVa.mapValues { (_, v) -> v / vln }
        val notes = mutableListOf<String>()
        val worst = phaseAmps.values.maxOrNull() ?: 0.0
        panel.busAmps?.let { if (worst > it) notes += "Phase current ${fmt(worst)} A exceeds the ${it} A bus." }
        panel.mainAmps?.let { if (worst > it) notes += "Phase current ${fmt(worst)} A exceeds the ${it} A main." }
        return PanelDesign(panel, phaseVa, phaseVa.values.sum(), phaseAmps, PanelLayout.conflicts(panel), notes)
    }

    private fun fmt(v: Double) = String.format("%.1f", v).removeSuffix(".0")
}

package com.thedirepack.constructioncalc.core.model.design

import com.thedirepack.constructioncalc.core.electrical.AmbientCorrection
import com.thedirepack.constructioncalc.core.electrical.ConductorCountAdjustment
import com.thedirepack.constructioncalc.core.electrical.ConductorImpedance
import com.thedirepack.constructioncalc.core.electrical.ConductorResistance
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.FillConductor
import com.thedirepack.constructioncalc.core.electrical.ConduitFill
import com.thedirepack.constructioncalc.core.electrical.ConductorGroup
import com.thedirepack.constructioncalc.core.electrical.ConductorSizing
import com.thedirepack.constructioncalc.core.electrical.ConduitSize
import com.thedirepack.constructioncalc.core.electrical.ConduitTable
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.CurrentCarryingConductors
import com.thedirepack.constructioncalc.core.electrical.EquipmentGroundingConductor
import com.thedirepack.constructioncalc.core.electrical.InsulatedConductorArea
import com.thedirepack.constructioncalc.core.electrical.InsulationType
import com.thedirepack.constructioncalc.core.electrical.RacewayBends
import com.thedirepack.constructioncalc.core.electrical.Table9Conduit
import com.thedirepack.constructioncalc.core.electrical.WireSizingRequest
import com.thedirepack.constructioncalc.core.electrical.WireSizingResult
import com.thedirepack.constructioncalc.core.model.Circuit
import com.thedirepack.constructioncalc.core.model.CircuitKind
import com.thedirepack.constructioncalc.core.model.CircuitRef
import com.thedirepack.constructioncalc.core.model.ConductorSpec
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.Run
import com.thedirepack.constructioncalc.core.model.RunPath
import kotlin.math.sqrt

/** How much a design note matters: [WARNING]s are problems to fix; [INFO] explains a figure. */
enum class NoteLevel { INFO, WARNING }

/** One line of explanation from the design engine. */
data class DesignNote(val text: String, val level: NoteLevel = NoteLevel.INFO) {
    val isWarning: Boolean get() = level == NoteLevel.WARNING
    override fun toString() = text
}

private fun info(text: String) = DesignNote(text, NoteLevel.INFO)
private fun warn(text: String) = DesignNote(text, NoteLevel.WARNING)

/** A circuit's computed design: what wire it needs and why. */
data class CircuitDesign(
    val panel: Panel,
    val circuit: Circuit,
    val conductor: ConductorSpec,
    /**
     * Load the circuit is sized for, VA: its own [Circuit.loadVa], or, for a feeder with no load
     * entered, the connected load of the panels it feeds ([loadFromFedPanels]).
     */
    val loadVa: Double?,
    /** True when [loadVa] was rolled up from the panels this circuit feeds. */
    val loadFromFedPanels: Boolean,
    /** Highest loaded pole's current, if known. */
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
    val notes: List<DesignNote>,
)

/** One conductor line in a run's fill: e.g. 3 × 12 AWG THHN. */
data class FillEntry(
    val count: Int,
    val size: ConductorSize,
    val insulation: InsulationType,
    val areaEach: Double,
    val ground: Boolean,
    /** How many of the [count] are current-carrying (310.15(E)/(F)): a neutral carrying only unbalanced current isn't. */
    val currentCarrying: Int = if (ground) 0 else count,
    val material: ConductorMaterial = ConductorMaterial.COPPER,
)

/** Fill check result for a run's conduit. */
enum class FillStatus { OK, OVER, INCOMPLETE, NO_CONDUIT, TOO_MANY_FOR_ANY_SIZE, EMPTY }

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
    val notes: List<DesignNote>,
    /** Total degrees of the run's path bends (or, without a path, the bends saved from Conduit Bending); null if none. */
    val bendDegrees: Double? = null,
    /** Bend degrees in each section between pull points (one entry without pull points); empty if no bends are known. */
    val sectionDegrees: List<Double> = emptyList(),
)

data class PanelDesign(
    val panel: Panel,
    val phaseVa: Map<Phase, Double>,
    val totalVa: Double,
    /** Sum of the connected circuits' current magnitudes on each pole. */
    val phaseAmps: Map<Phase, Double>,
    val conflicts: List<String>,
    val notes: List<DesignNote>,
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

    /** Conduit Bending's tool id and the result key its saved items carry their total bend angle in. */
    const val BEND_TOOL_ID = "conduit-bending"
    const val BEND_DEGREES_KEY = "bend_degrees"

    /** Raceway articles allow at most 360° of bends between pull points (NEC 2023 358.24(B), 344.24(B)…; see [RacewayBends]). */
    const val MAX_BEND_DEGREES = RacewayBends.MAX_DEGREES_BETWEEN_PULL_POINTS
    private data class DropSegment(val lengthInches: Double, val conduit: Table9Conduit)

    private data class PhaseLoad(
        val va: Double = 0.0,
        val continuousVa: Double = 0.0,
        val amps: Double = 0.0,
        val continuousAmps: Double = 0.0,
    ) {
        operator fun plus(other: PhaseLoad) = PhaseLoad(
            va + other.va, continuousVa + other.continuousVa, amps + other.amps, continuousAmps + other.continuousAmps,
        )
    }

    private data class Load(
        val phases: Map<Phase, PhaseLoad>,
        val fromFed: Boolean = false,
        val problem: String? = null,
    ) {
        val va get() = phases.values.sumOf { it.va }.takeIf { problem == null }
        val continuousVa get() = phases.values.sumOf { it.continuousVa }
        val amps get() = phases.values.maxOfOrNull { it.amps }
        val terminalAmps get() = phases.values.maxOfOrNull { it.amps + 0.25 * it.continuousAmps }
    }

    private fun panelPhases(panel: Panel) =
        if (panel.system.phases == 3) Phase.entries else listOf(Phase.A, Phase.B)

    /**
     * Every LOAD circuit's load. A feeder (a circuit some panel is fed from) with no VA of its own
     * carries the connected load of the panels it feeds, recursively; loops in a damaged file are cut.
     */
    private fun loads(project: Project): Map<CircuitRef, Load?> {
        val result = mutableMapOf<CircuitRef, Load?>()
        val visiting = mutableSetOf<CircuitRef>()
        val fedByCircuit = project.panels.filter { it.fedFrom != null }.groupBy { it.fedFrom }
        fun load(panel: Panel, c: Circuit): Load? {
            val ref = CircuitRef(panel.id, c.id)
            if (ref in result) return result[ref]
            if (!visiting.add(ref)) return Load(emptyMap(), problem = "The feeder links form a loop.")
            val poles = PanelLayout.phasesOf(c, panel.system.phases)
            val fed = fedByCircuit[ref].orEmpty()
            val value = when {
                c.loadVa != null -> {
                    val perPoleVa = c.loadVa / poles.size
                    val perPoleAmps = amps(panel, c, c.loadVa)
                    val phase = PhaseLoad(perPoleVa, if (c.continuous) perPoleVa else 0.0, perPoleAmps, if (c.continuous) perPoleAmps else 0.0)
                    Load(poles.associateWith { phase })
                }
                fed.isEmpty() -> null
                fed.any { it.system != panel.system || panelPhases(it).size != poles.size } ->
                    Load(emptyMap(), fromFed = true, problem = "Feeder poles or supply do not match the downstream panel. Check the link or enter feeder VA.")
                else -> {
                    val phaseLoads = mutableMapOf<Phase, PhaseLoad>()
                    var problem: String? = null
                    fed.forEach { child ->
                        val childCircuits = child.circuits.filter { it.kind == CircuitKind.LOAD }
                        val childLoads = childCircuits.mapNotNull { load(child, it) }
                        problem = problem ?: childLoads.firstNotNullOfOrNull { it.problem }
                        if (problem == null && childLoads.size != childCircuits.size) {
                            problem = "Enter the missing loads in ${child.name} or enter feeder VA."
                        }
                        panelPhases(child).zip(poles).forEach { (childPhase, parentPhase) ->
                            val total = childLoads.fold(phaseLoads[parentPhase] ?: PhaseLoad()) { acc, entry ->
                                acc + (entry.phases[childPhase] ?: PhaseLoad())
                            }
                            phaseLoads[parentPhase] = total
                        }
                    }
                    Load(if (problem == null) phaseLoads else emptyMap(), fromFed = true, problem = problem)
                }
            }
            visiting.remove(ref)
            result[ref] = value
            return value
        }
        project.panels.forEach { p -> p.circuits.filter { it.kind == CircuitKind.LOAD }.forEach { load(p, it) } }
        return result
    }

    fun analyze(project: Project): ProjectDesign {
        val runsById = project.runs.associateBy { it.id }
        val loads = loads(project)
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
        val circuits = allCircuits.associate { (panel, c) ->
            c.id to designCircuit(project, panel, c, runsById, runCcc, loads[CircuitRef(panel.id, c.id)])
        }

        // 3. Runs.
        val runs = project.runs.associate { run -> run.id to designRun(project, run, allCircuits, circuits, runCcc.getValue(run.id)) }

        // 4. Panels.
        val panels = project.panels.map { designPanel(it, loads) }
        return ProjectDesign(panels, circuits, runs)
    }

    /** Load current for [c]'s VA in [panel], or null. */
    fun amps(panel: Panel, c: Circuit): Double? = c.loadVa?.let { amps(panel, c, it) }

    /** Current for [va] on a circuit shaped like [c] in [panel]. */
    fun amps(panel: Panel, c: Circuit, va: Double): Double {
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
        load: Load?,
    ): CircuitDesign {
        val spec = c.conductor ?: project.design.conductor
        val notes = mutableListOf<DesignNote>()
        val amps = load?.amps
        load?.problem?.let { notes += warn(it) }
        val loadVa = load?.va
        if (load?.fromFed == true && loadVa != null) {
            val names = project.panels.filter { it.fedFrom == CircuitRef(panel.id, c.id) }.joinToString { it.name }
            notes += info("Feeder load from $names: ${fmt(loadVa)} VA connected, ${fmt(load.continuousVa)} VA continuous. Current magnitudes are added by feeder pole order, without demand factors.")
        }
        val runs = c.runIds.mapNotNull { runsById[it] }
        val ownCcc = CurrentCarryingConductors.count(c.poles, c.neutral, panel.system.supply, c.nonlinear)
        // Nipples (≤ 24") aren't subject to count adjustment (310.15(C)(1)(b)).
        val ccc = (runs.filter { it.raceway?.nipple != true }.mapNotNull { runCcc[it.id] } + ownCcc).max()
        // Each run's own ambient (or the project default) plus its own rooftop adder; the hottest run governs.
        val xhhw2 = spec.insulation == InsulationType.XHHW_2
        val ambient = if (runs.isEmpty()) project.design.ambientC else runs.maxOf { runAmbient(project, it, xhhw2) }
        val hottest = runs.maxByOrNull { runAmbient(project, it, xhhw2) }
        if (hottest?.raceway?.rooftop == true && !xhhw2) {
            notes += info("${hottest.name} is on a rooftop: +${AmbientCorrection.ROOFTOP_ADDER_C.toInt()} °C adder (310.15(B)(2)).")
        }
        val runConduits = runs.map { it.raceway?.conduitType?.let(::table9Conduit) ?: Table9Conduit.PVC }
        val runSegments = runs.zip(runConduits).mapNotNull { (run, conduit) ->
            run.raceway?.length?.toQuantity()?.value?.toDouble()?.let { DropSegment(it, conduit) }
        }
        val routeLength = runSegments.sumOf { it.lengthInches }
            .takeIf { runs.isNotEmpty() && runSegments.size == c.runIds.size }
        val overrideLength = c.length?.toQuantity()?.value?.toDouble()
        val length = overrideLength ?: routeLength
        if (length == null && c.runIds.isNotEmpty()) notes += info("Set the length of every assigned run to check voltage drop.")
        val dropSegments = when {
            length == null -> emptyList()
            runs.size != c.runIds.size -> {
                notes += info("An assigned run is missing. Check the route before calculating voltage drop.")
                emptyList()
            }
            runConduits.distinct().size <= 1 -> listOf(DropSegment(length, runConduits.firstOrNull() ?: Table9Conduit.PVC))
            routeLength == null || routeLength <= 0.0 -> {
                notes += info("Set each run's length to check voltage drop through mixed raceways.")
                emptyList()
            }
            overrideLength != null && overrideLength != routeLength -> {
                notes += info("Distributed the circuit length override across raceways in proportion to their entered lengths.")
                runSegments.map { it.copy(lengthInches = it.lengthInches * (overrideLength / routeLength)) }
            }
            else -> runSegments
        }

        val fromFed = load?.fromFed == true
        if (c.kind != CircuitKind.LOAD || load?.problem != null) {
            return CircuitDesign(panel, c, spec, load?.va, fromFed, amps, ccc, null, null, null, null, length, null, null, notes)
        }
        val breaker = c.breakerAmps
        if (breaker == null) {
            notes += warn("Set a breaker size to size the wire.")
            return CircuitDesign(panel, c, spec, load?.va, fromFed, amps, ccc, null, null, null, null, length, null, null, notes)
        }

        val sizing = ConductorSizing.minimumSize(
            WireSizingRequest(
                ocpdAmps = breaker,
                material = spec.material,
                loadAmps = amps,
                terminalLoadAmps = load?.terminalAmps,
                insulation = spec.insulationRating,
                termination = spec.terminationRating,
                ambientC = ambient, // already includes any rooftop adder
                rooftopUnder3_4In = false,
                xhhw2 = xhhw2,
                currentCarrying = ccc,
                multipleCordReceptacles = c.multipleCordReceptacles,
            ),
        )
        notes += sizing.notes.map { if (sizing.size == null) warn(it) else info(it) }
        val minimum = sizing.size
        var size = minimum
        var vd: Pair<Double, Double>? = null
        if (minimum != null && amps != null && dropSegments.isNotEmpty()) {
            val target = project.design.voltageDropTargetPercent
            var current = voltageDrop(panel, c, amps, dropSegments, minimum, spec)
            var candidate: ConductorSize = minimum
            var steps = 0
            while (project.design.upsizeForVoltageDrop && current != null && current.second > target && steps < MAX_VD_UPSIZE_STEPS) {
                val next = ConductorSize.entries.getOrNull(candidate.ordinal + 1) ?: break
                val nextVd = voltageDrop(panel, c, amps, dropSegments, next, spec) ?: break
                candidate = next
                current = nextVd
                steps++
            }
            if (candidate != minimum) {
                notes += info("Upsized from ${minimum.label} to ${candidate.label} for ${fmt(target)}% voltage drop.")
                size = candidate
            }
            vd = current
            if (current != null && current.second > target) notes += warn("Voltage drop ${fmt(current.second)}% exceeds the ${fmt(target)}% target.")
        } else if (amps == null) {
            notes += info("Enter the load (VA) to check voltage drop.")
        }
        val ground = if (minimum != null && size != null) {
            EquipmentGroundingConductor.sizeWithUpsize(breaker, spec.material, minimum, size)
        } else {
            null
        }
        return CircuitDesign(panel, c, spec, load?.va, fromFed, amps, ccc, sizing, minimum, size, ground, length, vd?.first, vd?.second, notes)
    }

    /** A run's effective ambient: its own temperature or the project default, plus its rooftop adder. */
    fun runAmbient(project: Project, run: Run, xhhw2: Boolean): Double =
        AmbientCorrection.effectiveAmbient(run.raceway?.ambientC ?: project.design.ambientC, run.raceway?.rooftop == true, xhhw2)

    /** (volts, percent) for the one-way path. */
    private fun voltageDrop(
        panel: Panel,
        c: Circuit,
        amps: Double,
        segments: List<DropSegment>,
        size: ConductorSize,
        spec: ConductorSpec,
    ): Pair<Double, Double>? {
        val row = ConductorImpedance.row(size, spec.material)
        val pathOhms = segments.sumOf { segment ->
            val impedance = row?.effectiveZ(segment.conduit, POWER_FACTOR)
                ?: ConductorResistance.ohmsPerKft(size, spec.material)
                ?: return null
            segment.lengthInches / 12_000.0 * impedance
        }
        val threePhase = c.poles == 3 && panel.system.phases == 3
        val factor = if (threePhase) sqrt(3.0) else 2.0
        val volts = if (c.poles == 1) (panel.system.lineToNeutral ?: panel.system.lineToLine) else panel.system.lineToLine
        val drop = factor * amps * pathOhms
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
        val notes = mutableListOf<DesignNote>()
        val members = all.filter { (_, c) -> run.id in c.runIds }
        val entries = mutableListOf<FillEntry>()
        var largestGround: Triple<ConductorSize, InsulationType, ConductorMaterial>? = null
        var incomplete = false
        val loads = members.filter { (_, c) -> c.kind == CircuitKind.LOAD }
        loads.forEach { (panel, c) ->
            val d = designs.getValue(c.id)
            val size = d.size
            if (size == null) {
                incomplete = true
                notes += info("${panel.name} ${PanelLayout.label(c)} has no calculated conductor size.")
            }
            val ground = d.groundSize
            val insulation = d.conductor.insulation
            if (ground == null) {
                incomplete = true
                if (size != null) notes += info("${panel.name} ${PanelLayout.label(c)} has no calculated equipment grounding conductor size.")
            } else if (largestGround?.let { ground > it.first } != false) {
                largestGround = Triple(ground, insulation, d.conductor.material)
            }
            if (size == null) return@forEach
            val wires = c.poles + if (c.neutral) 1 else 0
            val area = InsulatedConductorArea.area(insulation, size)
            if (area == null) {
                incomplete = true
                notes += warn("No Table 5 area for ${size.label} ${insulation.code}.")
                return@forEach
            }
            val carrying = CurrentCarryingConductors.count(c.poles, c.neutral, panel.system.supply, c.nonlinear).coerceAtMost(wires)
            entries += FillEntry(wires, size, insulation, area, ground = false, currentCarrying = carrying, material = d.conductor.material)
        }
        // One EGC sized for the largest circuit serves the raceway (250.122(C)).
        largestGround?.let { (g, ins, material) ->
            val groundArea = InsulatedConductorArea.area(ins, g)
            if (groundArea == null) {
                incomplete = true
                notes += warn("No Table 5 area for the ${g.label} ${ins.code} equipment grounding conductor.")
            } else {
                entries += FillEntry(1, g, ins, groundArea, ground = true, material = material)
            }
            if (loads.size > 1 && !incomplete) notes += info("One equipment grounding conductor (${g.label}) sized for the largest circuit (250.122(C)).")
        }
        val total = entries.sumOf { it.count }
        val area = entries.sumOf { it.count * it.areaEach }
        val raceway = run.raceway
        // The same Chapter 9 fill check as the Conduit Fill tool, Note 7 rounding included.
        val nipple = raceway?.nipple == true
        val groups = entries.map { ConductorGroup(it.count, FillConductor.Insulated(it.insulation, it.size)) }
        val limit = ConduitFill.limitPercent(total, nipple)
        val smallest = raceway?.takeIf { !incomplete && total > 0 }?.let { ConduitFill.minimumConduit(it.conduitType, groups, nipple) }
        val chosen = raceway?.tradeSize?.let { ConduitTable.find(raceway.conduitType, it) }
        if (raceway?.tradeSize != null && chosen == null) {
            notes += warn("${raceway.conduitType.displayName} has no ${raceway.tradeSize.display} size in Table 4; using the smallest that fits.")
        }
        val conduit = chosen ?: smallest
        val analysis = conduit?.takeUnless { incomplete || total == 0 }?.let { ConduitFill.analyze(it, groups, nipple) }
        val fill = analysis?.percentFill
        val over = analysis != null && !analysis.withinLimit
        if (raceway == null && members.isNotEmpty()) notes += warn("Set the conduit for this run to check fill.")
        if (over) {
            notes += warn("Over ${limit}% fill (${fmt(fill!!)}%). Smallest that fits: ${smallest?.displayName ?: "none in Table 4"}.")
        } else if (analysis?.permittedByNote7 == true) {
            notes += info("Fits only by Chapter 9 Table 1 Note 7 rounding (all conductors the same size).")
        }
        if (raceway != null && !incomplete && smallest == null && area > 0) notes += warn("Too many conductors for any ${raceway.conduitType.displayName}.")
        // Jam and clearance for pulling (IEEE 1185), from the largest conductor's Table 5 diameter.
        val largestOd = entries.mapNotNull { InsulatedConductorArea.diameter(it.insulation, it.size) }.maxOrNull()
        if (conduit != null && !incomplete && total > 0 && largestOd != null) notes += PullAnalysis.fitNotes(conduit.internalDiameterIn, largestOd, total)
        val insulation = project.design.conductor
        val ambient = runAmbient(project, run, insulation.insulation == InsulationType.XHHW_2)
        val ambientFactor = AmbientCorrection.factor(ambient, insulation.insulationRating)
        val adjustment = if (raceway?.nipple == true) 1.0 else ConductorCountAdjustment.factor(ccc)
        if (adjustment < 1.0) {
            notes += info("$ccc current-carrying conductors: ampacity ×$adjustment (Table 310.15(C)(1)) for every circuit in this run.")
        }
        if (ambientFactor != null && ambientFactor != 1.0) {
            notes += info("${fmt(ambient)} °C: ampacity ×$ambientFactor (Table 310.15(B)(1)(1), ${insulation.insulationRating.label} column).")
        }
        val status = when {
            incomplete -> FillStatus.INCOMPLETE
            area <= 0.0 -> FillStatus.EMPTY
            raceway == null -> FillStatus.NO_CONDUIT
            smallest == null && raceway.tradeSize == null -> FillStatus.TOO_MANY_FOR_ANY_SIZE
            over -> FillStatus.OVER
            conduit == null -> FillStatus.TOO_MANY_FOR_ANY_SIZE
            else -> FillStatus.OK
        }
        // Bends: the run's path when it has one (split at its pull points), else the bends saved from Conduit Bending.
        val sectionDegrees = if (run.path.isNotEmpty()) {
            RunPath.degreesPerSection(run.path)
        } else {
            run.items.filter { it.toolId == BEND_TOOL_ID }
                .mapNotNull { item -> item.results.firstOrNull { it.key == BEND_DEGREES_KEY }?.rational?.toDouble() }
                .takeIf { it.isNotEmpty() }?.let { listOf(it.sum()) }.orEmpty()
        }
        val bendDegrees = sectionDegrees.takeIf { it.isNotEmpty() }?.sum()
        val section = RacewayBends.section(raceway?.conduitType ?: ConduitType.EMT)
        sectionDegrees.forEachIndexed { i, degrees ->
            if (degrees > MAX_BEND_DEGREES + 1e-9) {
                val where = when {
                    run.path.isEmpty() -> "Saved bends total"
                    sectionDegrees.size == 1 -> "The path's bends total"
                    else -> "Pull section ${i + 1} has"
                }
                notes += warn("$where ${fmt(degrees)}°: more than ${MAX_BEND_DEGREES.toInt()}° between pull points (NEC $section). Add a pull point.")
            }
        }
        return RunDesign(run, members, ccc, adjustment, ambient, ambientFactor, status, entries, total, area, limit, conduit, fill, smallest, notes, bendDegrees, sectionDegrees)
    }

    private fun designPanel(panel: Panel, loads: Map<CircuitRef, Load?>): PanelDesign {
        val panelLoads = panel.circuits.filter { it.kind == CircuitKind.LOAD }
            .mapNotNull { c -> loads[CircuitRef(panel.id, c.id)] }
        val totals = panelPhases(panel).associateWith { phase ->
            panelLoads.fold(PhaseLoad()) { acc, load -> acc + (load.phases[phase] ?: PhaseLoad()) }
        }
        val phaseVa = totals.mapValues { it.value.va }
        val phaseAmps = totals.mapValues { it.value.amps }
        val notes = mutableListOf<DesignNote>()
        if (panelLoads.any { it.problem != null }) notes += warn("Phase totals are incomplete because a feeder load could not be calculated.")
        val worst = phaseAmps.values.maxOrNull() ?: 0.0
        panel.busAmps?.let { if (worst > it) notes += warn("Phase current ${fmt(worst)} A exceeds the ${it} A bus.") }
        panel.mainAmps?.let { if (worst > it) notes += warn("Phase current ${fmt(worst)} A exceeds the ${it} A main.") }
        return PanelDesign(panel, phaseVa, phaseVa.values.sum(), phaseAmps, PanelLayout.conflicts(panel), notes)
    }

    private fun fmt(v: Double) = com.thedirepack.constructioncalc.core.measure.Decimals.trim(v, 1)
}

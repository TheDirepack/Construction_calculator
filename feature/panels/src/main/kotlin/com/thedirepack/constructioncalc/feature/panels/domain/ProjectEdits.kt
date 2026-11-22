package com.thedirepack.constructioncalc.feature.panels.domain

import com.thedirepack.constructioncalc.core.model.Circuit
import com.thedirepack.constructioncalc.core.model.CircuitGroup
import com.thedirepack.constructioncalc.core.model.CircuitKind
import com.thedirepack.constructioncalc.core.model.CircuitRef
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.model.PanelSystem
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.Raceway
import com.thedirepack.constructioncalc.core.model.Run
import com.thedirepack.constructioncalc.core.model.newId
import com.thedirepack.constructioncalc.core.model.design.PanelLayout

/*
 * Edits on a Project for the Panels tool. Each returns a new Project with updatedAt = now, and
 * returns the project unchanged when the edit isn't possible (unknown ids, no room).
 */

/** Position of a circuit within the project. */
data class CircuitAt(val panel: Panel, val circuit: Circuit)

fun Project.findCircuit(circuitId: String): CircuitAt? =
    panels.firstNotNullOfOrNull { p -> p.circuits.firstOrNull { it.id == circuitId }?.let { CircuitAt(p, it) } }

fun Project.allCircuits(): List<CircuitAt> = panels.flatMap { p -> p.circuits.map { CircuitAt(p, it) } }

/** A new panel with the app's defaults: 208Y/120 V, 42 spaces, 225 A bus, main lugs only. */
fun newPanel(name: String): Panel = Panel(name = name.trim().ifEmpty { "Panel" })

/** A suggested name that isn't taken yet: "Panel 1", "Panel 2"… */
fun Project.suggestedPanelName(): String {
    val names = panels.map { it.name }.toSet()
    return generateSequence(panels.size + 1) { it + 1 }.map { "Panel $it" }.first { it !in names }
}

/** A new 20 A 1-pole circuit at the first free position (or [slot]), or null if nothing fits. */
fun newCircuit(panel: Panel, poles: Int = 1, slot: Int? = null): Circuit? {
    val at = slot?.takeIf { PanelLayout.canPlace(panel, it, poles) } ?: PanelLayout.firstFree(panel, poles) ?: return null
    return Circuit(slot = at, poles = poles, breakerAmps = 20)
}

/** Positions where a breaker of [poles] fits in [panel], ignoring [ignore] (the circuit being edited). */
fun freeSlots(panel: Panel, poles: Int, ignore: String? = null): List<Int> =
    (1..panel.spaces).filter { PanelLayout.canPlace(panel, it, poles, ignore) }

/** Pole counts a panel can hold at all: 1, 2, and 3 on a three-phase panel. */
fun supportedPoles(panel: Panel, ignore: String? = null): List<Int> {
    val max = if (panel.system.phases >= 3) 3 else 2
    return (1..max).filter { freeSlots(panel, it, ignore).isNotEmpty() }
}

/**
 * Adds or replaces [circuit] in [panelId]. If a circuit with the same id is in another panel it is
 * moved (and anything fed from it keeps pointing at it). Returns this if [panelId] doesn't exist.
 */
fun Project.upsertCircuit(panelId: String, circuit: Circuit, now: Long): Project {
    if (panel(panelId) == null) return this
    val moved = panels.firstOrNull { p -> p.id != panelId && p.circuits.any { it.id == circuit.id } }
    val updated = panels.map { p ->
        when {
            p.id == panelId -> {
                val others = p.circuits.filterNot { it.id == circuit.id }
                p.copy(circuits = (others + circuit).sortedBy { it.slot })
            }
            p.id == moved?.id -> p.copy(circuits = p.circuits.filterNot { it.id == circuit.id })
            else -> p
        }
    }
    val withRefs = if (moved == null) updated else updated.map { p ->
        val ref = p.fedFrom
        if (ref != null && ref.circuitId == circuit.id) p.copy(fedFrom = CircuitRef(panelId, circuit.id)) else p
    }
    return copy(panels = withRefs, updatedAt = now)
}

/** Moves a circuit to [toSlot] of [toPanelId]. Does nothing if its poles don't fit there. */
fun Project.moveCircuit(circuitId: String, toPanelId: String, toSlot: Int, now: Long): Project {
    val at = findCircuit(circuitId) ?: return this
    val target = panel(toPanelId) ?: return this
    if (target.system.phases == 1 && at.circuit.poles > 2) return this
    if (!PanelLayout.canPlace(target, toSlot, at.circuit.poles, ignore = circuitId)) return this
    return upsertCircuit(toPanelId, at.circuit.copy(slot = toSlot), now)
}

/** Removes a circuit, and clears "fed from" on any panel it fed. */
fun Project.deleteCircuit(circuitId: String, now: Long): Project = copy(
    panels = panels.map { p ->
        p.copy(
            circuits = p.circuits.filterNot { it.id == circuitId },
            fedFrom = p.fedFrom?.takeUnless { it.circuitId == circuitId },
        )
    },
    updatedAt = now,
)

/** Replaces a panel's settings. Switching to a single-phase system turns 3-pole breakers into 2-pole. */
fun Project.changePanelSystem(panelId: String, system: PanelSystem, now: Long): Project =
    updatePanel(panelId, now) { p ->
        if (system.phases == 1) {
            p.copy(system = system, circuits = p.circuits.map { if (it.poles > 2) it.copy(poles = 2, neutral = false) else it })
        } else {
            p.copy(system = system)
        }
    }

/**
 * A copy of a panel (new ids throughout) placed right after the original. It isn't fed from anything,
 * and its circuits aren't in any conduit run: sharing the original's conduits would silently double
 * their conductor count (derating, fill and wire sizes would all change).
 */
fun Project.duplicatePanel(panelId: String, now: Long): Project {
    val original = panel(panelId) ?: return this
    val copy = original.copy(
        id = newId(),
        name = "${original.name} (copy)",
        fedFrom = null,
        circuits = original.circuits.map { it.copy(id = newId(), runIds = emptyList()) },
    )
    val index = panels.indexOfFirst { it.id == panelId }
    return copy(panels = panels.toMutableList().apply { add(index + 1, copy) }, updatedAt = now)
}

/**
 * Makes exactly [circuitIds] the circuits that pass through [runId]: it is added to those circuits
 * (across every panel) and removed from every other.
 */
fun Project.assignRun(runId: String, circuitIds: Set<String>, now: Long): Project {
    if (run(runId) == null) return this
    return copy(
        panels = panels.map { p ->
            p.copy(
                circuits = p.circuits.map { c ->
                    val has = runId in c.runIds
                    when {
                        c.id in circuitIds && !has -> c.copy(runIds = c.runIds + runId)
                        c.id !in circuitIds && has -> c.copy(runIds = c.runIds - runId)
                        else -> c
                    }
                },
            )
        },
        updatedAt = now,
    )
}

/**
 * Circuits that could feed [panelId]: any circuit in another panel, except panels that are
 * themselves fed (directly or not) from [panelId], which would make a loop.
 */
fun Project.feedCandidates(panelId: String): List<CircuitAt> {
    val downstream = mutableSetOf(panelId)
    do {
        val before = downstream.size
        panels.filter { it.fedFrom?.panelId in downstream }.forEach { downstream += it.id }
    } while (downstream.size > before)
    return allCircuits().filter { it.panel.id !in downstream && it.circuit.kind != CircuitKind.SPACE }
}

/** Circuits (every panel) that pass through [runId]. */
fun Project.circuitsInRun(runId: String): List<CircuitAt> = allCircuits().filter { runId in it.circuit.runIds }

/** Deletes a run and takes it out of every circuit. */
fun Project.deleteRunAndUnassign(runId: String, now: Long): Project = removeRun(runId, now)

/** A new conduit run (a project run with a [Raceway]). */
fun Project.addConduitRun(name: String, now: Long, raceway: Raceway = Raceway()): Pair<Project, Run> {
    val run = Run(name = name.trim().ifEmpty { "Conduit run" }, raceway = raceway)
    return addRun(run, now) to run
}

/** Runs shown in the Runs tab: conduit runs, plus any run a circuit passes through. */
fun Project.designRuns(): List<Run> {
    val used = allCircuits().flatMap { it.circuit.runIds }.toSet()
    return runs.filter { it.raceway != null || it.id in used }
}

// --- Groups ---

fun Project.addGroup(group: CircuitGroup, now: Long): Project = copy(groups = groups + group, updatedAt = now)

/** A new group named [name] in the next unused palette colour. */
fun Project.newGroup(name: String): CircuitGroup =
    CircuitGroup(name = name.trim().ifEmpty { "Group" }, color = GroupColors.next(groups.map { it.color }))

fun Project.updateGroup(groupId: String, now: Long, change: (CircuitGroup) -> CircuitGroup): Project =
    copy(groups = groups.map { if (it.id == groupId) change(it) else it }, updatedAt = now)

/** Deletes a group and untags its circuits. */
fun Project.deleteGroup(groupId: String, now: Long): Project = copy(
    groups = groups.filterNot { it.id == groupId },
    panels = panels.map { p ->
        p.copy(circuits = p.circuits.map { if (groupId in it.groupIds) it.copy(groupIds = it.groupIds - groupId) else it })
    },
    updatedAt = now,
)

fun Project.circuitsInGroup(groupId: String): List<CircuitAt> = allCircuits().filter { groupId in it.circuit.groupIds }

/** "LP-1 #5 Kitchen" / "LP-1 #2,4 Water heater". */
fun circuitLabel(panel: Panel, circuit: Circuit): String = buildString {
    append(panel.name).append(" #").append(circuit.slots.joinToString(","))
    when {
        circuit.kind == CircuitKind.SPARE && circuit.description.isBlank() -> append(" Spare")
        circuit.description.isNotBlank() -> append(' ').append(circuit.description.trim())
    }
}

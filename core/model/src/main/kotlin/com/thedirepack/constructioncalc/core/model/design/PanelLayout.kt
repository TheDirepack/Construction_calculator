package com.thedirepack.constructioncalc.core.model.design

import com.thedirepack.constructioncalc.core.model.Circuit
import com.thedirepack.constructioncalc.core.model.CircuitKind
import com.thedirepack.constructioncalc.core.model.Panel

enum class Phase { A, B, C }

/**
 * Breaker-position rules for a standard panelboard: odd positions on the left, even on the right,
 * numbered top to bottom (1-2 is the first row, 3-4 the second…). Rows rotate through the phases
 * (A, B, C on three-phase; A, B on single-phase), so a 2-pole breaker at 1 takes 1 and 3 (A and B)
 * and a 3-pole at 1 takes 1, 3, 5 (A, B, C).
 */
object PanelLayout {

    fun phaseOf(slot: Int, phases: Int): Phase {
        val row = (slot - 1) / 2
        return Phase.entries[row % if (phases >= 3) 3 else 2]
    }

    fun phasesOf(circuit: Circuit, phases: Int): List<Phase> = circuit.slots.map { phaseOf(it, phases) }

    /** Position → the circuit occupying it. Later circuits don't overwrite earlier ones. */
    fun occupancy(panel: Panel): Map<Int, Circuit> {
        val map = LinkedHashMap<Int, Circuit>()
        panel.circuits.forEach { c -> c.slots.forEach { slot -> map.putIfAbsent(slot, c) } }
        return map
    }

    /** True if [poles] positions starting at [slot] are free and inside the panel. [ignore] lets a circuit be moved. */
    fun canPlace(panel: Panel, slot: Int, poles: Int, ignore: String? = null): Boolean {
        val wanted = (0 until poles).map { slot + 2 * it }
        if (wanted.any { it < 1 || it > panel.spaces }) return false
        val taken = panel.circuits.filter { it.id != ignore }.flatMap { it.slots }.toSet()
        return wanted.none { it in taken }
    }

    /** The first position (top-left first) where a breaker of [poles] fits, or null. */
    fun firstFree(panel: Panel, poles: Int): Int? = (1..panel.spaces).firstOrNull { canPlace(panel, it, poles) }

    /** Problems a user must fix: overlaps and breakers running past the last position. */
    fun conflicts(panel: Panel): List<String> = buildList {
        val seen = mutableMapOf<Int, Circuit>()
        panel.circuits.forEach { c ->
            c.slots.forEach { slot ->
                if (slot > panel.spaces) add("${label(c)} runs past position ${panel.spaces}.")
                seen[slot]?.let { other -> add("${label(c)} overlaps ${label(other)} at position $slot.") }
                seen.putIfAbsent(slot, c)
            }
        }
        if (panel.system.phases == 1) {
            panel.circuits.filter { it.poles > 2 }.forEach { add("${label(it)}: a single-phase panel has no 3-pole circuits.") }
        }
        if (panel.system.lineToNeutral == null) {
            panel.circuits.filter { it.poles == 1 && it.kind == CircuitKind.LOAD }
                .forEach { add("${label(it)}: a ${panel.system.label} panel has no neutral for 1-pole loads.") }
        }
    }

    /** "Circuit 5" or "Circuits 2,4,6". */
    fun label(c: Circuit): String =
        if (c.poles <= 1) "Circuit ${c.slot}" else "Circuits ${c.slots.joinToString(",")}"
}

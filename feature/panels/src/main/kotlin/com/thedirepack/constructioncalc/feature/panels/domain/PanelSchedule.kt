package com.thedirepack.constructioncalc.feature.panels.domain

import com.thedirepack.constructioncalc.core.model.Circuit
import com.thedirepack.constructioncalc.core.model.CircuitKind
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.model.design.PanelLayout
import com.thedirepack.constructioncalc.core.model.design.Phase

/**
 * One breaker position (or a multi-pole breaker's run of positions) on one side of the schedule.
 *
 * @property firstRow zero-based row the block starts on
 * @property rowSpan how many rows it covers (a 3-pole breaker covers 3 rows on its side)
 * @property slot the position number of the first row
 * @property circuit what occupies it, or null for an empty position
 */
data class ScheduleBlock(val firstRow: Int, val rowSpan: Int, val slot: Int, val circuit: Circuit?) {
    val isEmpty: Boolean get() = circuit == null
}

/** One row of the two-column schedule: odd position on the left, even on the right. */
data class ScheduleRow(val index: Int, val phase: Phase, val leftSlot: Int, val rightSlot: Int?)

/**
 * A panel laid out like a printed panel schedule: odd positions down the left, even down the right,
 * row by row (1-2, 3-4…), each row on one phase.
 *
 * @property unplaced circuits that don't appear in the grid: they sit past the last position or lost
 *   every position to an overlapping circuit. They are listed separately so nothing is hidden.
 */
data class PanelSchedule(
    val rows: List<ScheduleRow>,
    val left: List<ScheduleBlock>,
    val right: List<ScheduleBlock>,
    val unplaced: List<Circuit>,
) {
    fun blocks(): List<ScheduleBlock> = left + right
}

object ScheduleLayout {

    fun build(panel: Panel): PanelSchedule {
        val phases = panel.system.phases
        val rowCount = (panel.spaces + 1) / 2
        val rows = (0 until rowCount).map { r ->
            val left = 2 * r + 1
            ScheduleRow(r, PanelLayout.phaseOf(left, phases), left, (left + 1).takeIf { it <= panel.spaces })
        }
        val occupancy = PanelLayout.occupancy(panel)
        val left = sideBlocks(occupancy, firstSlot = 1, rows = rowCount)
        val right = sideBlocks(occupancy, firstSlot = 2, rows = panel.spaces / 2)
        val shown = (left + right).mapNotNull { it.circuit?.id }.toSet()
        return PanelSchedule(rows, left, right, panel.circuits.filter { it.id !in shown })
    }

    private fun sideBlocks(occupancy: Map<Int, Circuit>, firstSlot: Int, rows: Int): List<ScheduleBlock> {
        val blocks = mutableListOf<ScheduleBlock>()
        var r = 0
        while (r < rows) {
            val slot = firstSlot + 2 * r
            val circuit = occupancy[slot]
            if (circuit == null) {
                blocks += ScheduleBlock(r, 1, slot, null)
                r++
            } else {
                var span = 1
                while (r + span < rows && occupancy[slot + 2 * span]?.id == circuit.id) span++
                blocks += ScheduleBlock(r, span, slot, circuit)
                r += span
            }
        }
        return blocks
    }

    /** "20/1" for a breaker, "—" when there is none. */
    fun breakerText(c: Circuit): String = when {
        c.kind == CircuitKind.SPACE -> "space"
        c.breakerAmps == null -> "—/${c.poles}"
        else -> "${c.breakerAmps}/${c.poles}"
    }
}

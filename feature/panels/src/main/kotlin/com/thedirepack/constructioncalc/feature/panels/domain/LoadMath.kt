package com.thedirepack.constructioncalc.feature.panels.domain

import com.thedirepack.constructioncalc.core.model.PanelSystem
import kotlin.math.sqrt

/**
 * Volt-amperes ↔ amperes for a breaker of [poles] in a panel of the given [PanelSystem].
 * Uses the same rules as `ProjectAnalysis.amps`: a 1-pole circuit sees line-to-neutral (line-to-line
 * where the system has no neutral), a 2-pole circuit or any circuit in a single-phase panel sees
 * line-to-line, and a 3-pole circuit is balanced three-phase (√3 × line-to-line).
 */
object LoadMath {

    private fun divisor(system: PanelSystem, poles: Int): Double = when {
        poles <= 1 -> (system.lineToNeutral ?: system.lineToLine).toDouble()
        poles == 2 || system.phases == 1 -> system.lineToLine.toDouble()
        else -> sqrt(3.0) * system.lineToLine
    }

    fun amps(system: PanelSystem, poles: Int, va: Double): Double = va / divisor(system, poles)

    fun va(system: PanelSystem, poles: Int, amps: Double): Double = amps * divisor(system, poles)

    /** Percent a panel's phases are out of balance: the largest deviation from the average, over the average. */
    fun imbalancePercent(phaseVa: Collection<Double>): Double? {
        if (phaseVa.isEmpty()) return null
        val average = phaseVa.average()
        if (average <= 0.0) return null
        return 100.0 * phaseVa.maxOf { kotlin.math.abs(it - average) } / average
    }
}

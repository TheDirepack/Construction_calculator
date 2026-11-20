package com.thedirepack.constructioncalc.feature.panels.domain

import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.model.Circuit
import com.thedirepack.constructioncalc.core.model.design.CircuitDesign
import java.util.Locale

/** How wire is written on a panel schedule: `2#12, 1#12G`. */
object Wire {

    private fun isKcmil(size: ConductorSize) = size.label.endsWith("kcmil")

    /** `12` for AWG sizes, `250k` for kcmil. */
    fun sizeToken(size: ConductorSize): String = if (isKcmil(size)) "${size.shortLabel}k" else size.shortLabel

    /** `2#12` or `3-250k`. */
    fun run(count: Int, size: ConductorSize): String =
        if (isKcmil(size)) "$count-${sizeToken(size)}" else "$count#${sizeToken(size)}"

    /** Conductors in the circuit's cable or conduit: ungrounded poles plus the neutral, if any. */
    fun hotAndNeutral(c: Circuit): Int = c.poles + if (c.neutral) 1 else 0

    /**
     * `2#12, 1#12G` for a sized circuit (plus ` Al` for aluminum), or null when there is no size
     * (spare/space, no breaker, or wire that can't be sized).
     */
    fun text(d: CircuitDesign): String? {
        val size = d.size ?: return null
        return buildString {
            append(run(hotAndNeutral(d.circuit), size))
            d.groundSize?.let { append(", ").append(run(1, it)).append('G') }
            if (!d.conductor.material.isCopper) append(" Al")
        }
    }

    // --- Number formatting shared by the text and PDF exports ---

    fun va(value: Double): String = String.format(Locale.US, "%,.0f VA", value)

    fun amps(value: Double): String = number(value, 1) + " A"

    /** At most [decimals] places, no trailing zeros. */
    fun number(value: Double, decimals: Int = 1): String = com.thedirepack.constructioncalc.core.measure.Decimals.trim(value, decimals)

    fun percent(value: Double): String = number(value, 1) + "%"
}

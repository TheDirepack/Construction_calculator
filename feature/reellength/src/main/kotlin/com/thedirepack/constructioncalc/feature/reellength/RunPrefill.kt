package com.thedirepack.constructioncalc.feature.reellength

import com.thedirepack.constructioncalc.core.electrical.NamedCable
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Dimension
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.design.PullAnalysis
import com.thedirepack.constructioncalc.core.model.stored

/**
 * [current] with the cable a project run's pull needs: "cable needed" per conductor and the cable
 * outside diameter, from the same pull analysis the Wire Pull Planner shows. The reel's own
 * measurements are kept. Null when the run is gone or its pull has neither value.
 */
fun reelInputsForRun(current: ReelLengthInputs, project: Project, runId: String): ReelLengthInputs? {
    val pull = PullAnalysis.analyze(project, runId) ?: return null
    val needed = pull.cableLengthIn
    val od = pull.cableOdIn
    if (needed == null && od == null) return null
    return current.copy(
        cableNeeded = needed?.let { Quantity.inches(Rational.of(it)).stored() } ?: current.cableNeeded,
        cableDiameter = od?.let { Quantity.inches(Rational.of(it)).stored() } ?: current.cableDiameter,
    )
}

/** The cable diameter typed in, as a named cable to save; null while there is none. */
fun namedCableFor(inputs: ReelLengthInputs): NamedCable? {
    val od = inputs.cableDiameter?.takeIf { it.dimension == Dimension.LENGTH }?.toQuantity()?.value?.toDouble() ?: return null
    return runCatching { NamedCable(id = "reel-cable", name = "Reel cable", diameterIn = od) }.getOrNull()
}

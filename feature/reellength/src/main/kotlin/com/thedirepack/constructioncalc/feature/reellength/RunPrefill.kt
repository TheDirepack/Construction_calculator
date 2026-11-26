package com.thedirepack.constructioncalc.feature.reellength

import com.thedirepack.constructioncalc.core.electrical.NamedCable
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Dimension
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.design.PullAnalysis
import com.thedirepack.constructioncalc.core.model.stored

/**
 * [current] with the cable a project run's pull needs: "cable needed" per conductor, the cable
 * outside diameter, and (when the pull uses one size and metal) the wire size and material for the
 * resistance method, from the same pull analysis the Wire Pull Planner shows. The reel's own
 * measurements and the reading are kept. Null when the run is gone or its pull has none of these.
 */
fun reelInputsForRun(current: ReelLengthInputs, project: Project, runId: String): ReelLengthInputs? {
    val pull = PullAnalysis.analyze(project, runId) ?: return null
    val needed = pull.cableLengthIn
    val od = pull.cableOdIn
    if (needed == null && od == null) return null
    val bearing = pull.conductors.filter { it.bearsTension }.ifEmpty { pull.conductors }
    val size = bearing.map { it.size }.distinct().singleOrNull()
    val material = bearing.map { it.material }.distinct().singleOrNull()
    return current.copy(
        wireSize = size?.label ?: current.wireSize,
        wireMaterial = material ?: current.wireMaterial,
        cableNeeded = needed?.let { Quantity.inches(Rational.of(it)).stored() } ?: current.cableNeeded,
        cableDiameter = od?.let { Quantity.inches(Rational.of(it)).stored() } ?: current.cableDiameter,
    )
}

/** The cable diameter typed in, as a named cable to save; null while there is none. */
fun namedCableFor(inputs: ReelLengthInputs): NamedCable? {
    val od = inputs.cableDiameter?.takeIf { it.dimension == Dimension.LENGTH }?.toQuantity()?.value?.toDouble() ?: return null
    return runCatching { NamedCable(id = "reel-cable", name = "Reel cable", diameterIn = od) }.getOrNull()
}

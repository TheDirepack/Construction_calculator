package com.thedirepack.constructioncalc.feature.conduitbending

import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.design.ProjectAnalysis
import com.thedirepack.constructioncalc.feature.conduitbending.domain.Bendable

/**
 * Conduit Bending started from a project run: the run's conduit type and size (its chosen size, or
 * the size the design picked) when a hand bender handles them. Null if the run is gone or has no
 * conduit.
 */
fun bendingInputsForRun(project: Project, runId: String, current: ConduitBendingInputs = ConduitBendingInputs()): ConduitBendingInputs? {
    val raceway = project.run(runId)?.raceway ?: return null
    val type = Bendable.typeOrDefault(raceway.conduitType)
    val size = raceway.tradeSize ?: ProjectAnalysis.analyze(project).runs[runId]?.conduit?.tradeSize
    return current.copy(conduitType = type.code, conduitSize = Bendable.sizeOrNearest(type, size).label)
}

package com.thedirepack.constructioncalc.feature.conduitfill

import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.measure.Decimals
import com.thedirepack.constructioncalc.core.model.PanelSystem
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.design.ProjectAnalysis
import com.thedirepack.constructioncalc.feature.conduitfill.domain.VoltageSystem

/**
 * Conduit Fill started from a project run: its conduit (chosen size, or the size the design
 * picked), nipple, ambient and rooftop, and every conductor the design puts in it, split into
 * current-carrying and not (neutrals carrying only unbalanced current, the EGC). Null if the run
 * is gone or has no conduit.
 */
fun conduitFillInputsForRun(project: Project, runId: String): ConduitFillInputs? {
    val run = project.run(runId) ?: return null
    val raceway = run.raceway ?: return null
    val rd = ProjectAnalysis.analyze(project).runs[runId] ?: return null
    val groups = rd.entries.flatMap { e ->
        listOf(e.currentCarrying to true, e.count - e.currentCarrying to false)
            .filter { (n, _) -> n > 0 }
            .map { (n, carrying) -> Triple(e.insulation.code, e.size.label, carrying) to n }
    }.groupBy({ it.first }, { it.second })
        .map { (key, counts) -> ConductorGroupInput(counts.sum().toString(), key.first, key.second, key.third) }
        .take(ConduitFillViewModel.MAX_GROUPS)
    val runPanels = project.panels.filter { p -> p.circuits.any { runId in it.runIds } }
    val runCircuits = runPanels.flatMap { p -> p.circuits.filter { runId in it.runIds } }
    val voltageSystem = when (runPanels.firstOrNull()?.system) {
        PanelSystem.SINGLE_120_240 -> VoltageSystem.V240
        PanelSystem.WYE_208_120 -> VoltageSystem.WYE_208_120
        PanelSystem.WYE_480_277 -> VoltageSystem.WYE_480_277
        PanelSystem.DELTA_240 -> VoltageSystem.V240
        PanelSystem.DELTA_480 -> VoltageSystem.CUSTOM
        null -> VoltageSystem.V120
    }
    val design = project.design.conductor
    return ConduitFillInputs(
        conduitType = raceway.conduitType.code,
        tradeSize = (raceway.tradeSize ?: rd.conduit?.tradeSize ?: TradeSize.S_3_4).label,
        nipple = raceway.nipple,
        groups = groups.ifEmpty { ConduitFillInputs().groups },
        ambientC = Decimals.trim(raceway.ambientC ?: project.design.ambientC, 1),
        rooftop = raceway.rooftop,
        material = design.material.name,
        termination = design.terminationRating.celsius,
        voltageSystem = voltageSystem.name,
        circuitCount = runCircuits.size.coerceAtLeast(1),
        nonlinearHarmonic = runCircuits.any { it.nonlinear },
        threePhaseCircuits = runCircuits.any { it.poles == 3 },
    )
}

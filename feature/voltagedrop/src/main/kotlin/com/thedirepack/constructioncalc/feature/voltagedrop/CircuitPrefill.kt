package com.thedirepack.constructioncalc.feature.voltagedrop

import com.thedirepack.constructioncalc.core.electrical.AcPhases
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.Table9Conduit
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Decimals
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.design.ProjectAnalysis
import com.thedirepack.constructioncalc.core.model.stored

/**
 * Voltage Drop started from a project circuit: its voltage and phases, its load current (from VA,
 * or the panels a feeder serves), its length (override or the sum of its runs), its wire as the
 * design sized it, the first run's conduit for Table 9, and the project's drop target. Null if the
 * circuit is gone.
 */
fun voltageDropInputsForCircuit(project: Project, circuitId: String): VoltageDropInputs? {
    val panel = project.panels.firstOrNull { p -> p.circuits.any { it.id == circuitId } } ?: return null
    val circuit = panel.circuits.first { it.id == circuitId }
    val design = ProjectAnalysis.analyze(project).circuits[circuitId] ?: return null
    val volts = if (circuit.poles <= 1) panel.system.lineToNeutral ?: panel.system.lineToLine else panel.system.lineToLine
    val preset = volts.takeIf { it in VoltageDropInputs.VOLTAGE_PRESETS }
    val conduit = circuit.runIds.firstNotNullOfOrNull { project.run(it)?.raceway?.conduitType }
        ?.let(ProjectAnalysis::table9Conduit) ?: Table9Conduit.PVC
    return VoltageDropInputs(
        system = if (circuit.poles >= 3 && panel.system.phases == 3) AcPhases.THREE else AcPhases.SINGLE,
        voltagePreset = preset ?: 0,
        customVoltage = if (preset == null) volts.toString() else "",
        material = design.conductor.material.name,
        amps = design.amps?.let { Decimals.trim(it, 2) }.orEmpty(),
        length = design.lengthInches?.let { Quantity.inches(Rational.of(it)).stored() },
        size = (design.size ?: design.minimumSize ?: ConductorSize.AWG_12).label,
        conduit = conduit,
        targetPercent = Decimals.trim(project.design.voltageDropTargetPercent, 2),
    )
}

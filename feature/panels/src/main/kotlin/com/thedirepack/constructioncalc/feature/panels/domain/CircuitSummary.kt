package com.thedirepack.constructioncalc.feature.panels.domain

import com.thedirepack.constructioncalc.core.model.Circuit
import com.thedirepack.constructioncalc.core.model.CircuitKind
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.model.design.CircuitDesign
import com.thedirepack.constructioncalc.core.model.design.PanelLayout
import com.thedirepack.constructioncalc.core.model.design.Wire

/**
 * Formats the concise one-line wire, ground, and phase summary pill shown in quick circuit entry
 * (for example `#12 Cu · 1#12G · Phase A`).
 */
fun circuitWireSummaryPill(panel: Panel, circuit: Circuit, design: CircuitDesign?): String {
    val phases = PanelLayout.phasesOf(circuit, panel.system.phases)
    val phaseLabel = if (phases.size == 1) {
        "Phase ${phases.single().name}"
    } else {
        "Phases ${phases.joinToString("/") { it.name }}"
    }
    return when (circuit.kind) {
        CircuitKind.SPACE -> "Space · $phaseLabel"
        CircuitKind.SPARE -> "Spare · ${circuit.breakerAmps ?: 20}A ${circuit.poles}P · $phaseLabel"
        CircuitKind.LOAD -> when {
            design == null -> "Sizing wire… · $phaseLabel"
            design.size == null -> "Size unavailable · $phaseLabel"
            else -> {
                val size = design.size!!
                val sizeText = if (size.label.endsWith("AWG")) "#${size.shortLabel}" else size.label
                val wirePart = "$sizeText ${design.conductor.material.shortLabel}"
                val groundPart = design.groundSize?.let { "${Wire.run(1, it)}G" }
                val vdPart = design.voltageDropPercent?.let { "VD ${Wire.percent(it)}" }
                listOfNotNull(wirePart, groundPart, phaseLabel, vdPart).joinToString(" · ")
            }
        }
    }
}

package com.thedirepack.constructioncalc.feature.voltagedrop

import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.ui.tool.ToolInputsViewModel
import com.thedirepack.constructioncalc.feature.voltagedrop.domain.VoltageDrop

class VoltageDropViewModel : ToolInputsViewModel<VoltageDropInputs>(VoltageDropInputs(), VoltageDropInputs.serializer()) {
    /** If the method or material makes the chosen size unavailable, moves to the nearest listed one. */
    override fun normalize(next: VoltageDropInputs): VoltageDropInputs {
        val sizes = VoltageDrop.supportedSizes(next.conductorMaterial, next.method)
        val normalizedSize = if (next.legacyConductorSize in sizes) {
            next.size
        } else {
            nearestSize(sizes, next.legacyConductorSize).label
        }
        val manual = next.manualConductorSize
        val normalizedManualSize = when {
            next.manualSize == null -> null
            manual == null -> next.manualSize
            manual in sizes -> manual.label
            else -> nearestSize(sizes, manual).label
        }
        return if (normalizedSize == next.size && normalizedManualSize == next.manualSize) {
            next
        } else {
            next.copy(size = normalizedSize, manualSize = normalizedManualSize)
        }
    }

    private fun nearestSize(sizes: List<ConductorSize>, target: ConductorSize): ConductorSize =
        sizes.firstOrNull { it.circularMils >= target.circularMils } ?: sizes.last()
}

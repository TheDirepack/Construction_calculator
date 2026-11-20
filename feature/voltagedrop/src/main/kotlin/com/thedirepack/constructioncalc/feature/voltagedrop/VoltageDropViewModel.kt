package com.thedirepack.constructioncalc.feature.voltagedrop

import com.thedirepack.constructioncalc.core.ui.tool.ToolInputsViewModel
import com.thedirepack.constructioncalc.feature.voltagedrop.domain.VoltageDrop

class VoltageDropViewModel : ToolInputsViewModel<VoltageDropInputs>(VoltageDropInputs(), VoltageDropInputs.serializer()) {
    /** If the method or material makes the chosen size unavailable, moves to the nearest listed one. */
    override fun normalize(next: VoltageDropInputs): VoltageDropInputs {
        val sizes = VoltageDrop.supportedSizes(next.conductorMaterial, next.method)
        return if (next.conductorSize in sizes) {
            next
        } else {
            val nearest = sizes.firstOrNull { it.circularMils >= next.conductorSize.circularMils } ?: sizes.last()
            next.copy(size = nearest.label)
        }
    }

}

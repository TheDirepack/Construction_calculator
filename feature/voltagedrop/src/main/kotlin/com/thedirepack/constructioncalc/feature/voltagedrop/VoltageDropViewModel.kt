package com.thedirepack.constructioncalc.feature.voltagedrop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.thedirepack.constructioncalc.core.model.ProjectItem
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.feature.voltagedrop.domain.VoltageDrop

class VoltageDropViewModel : ViewModel() {
    var inputs by mutableStateOf(VoltageDropInputs())
        private set

    private var restoredItemId: String? = null

    /** Applies an edit. If the method or material makes the chosen size unavailable, moves to the nearest listed one. */
    fun update(change: (VoltageDropInputs) -> VoltageDropInputs) {
        val next = change(inputs)
        val sizes = VoltageDrop.supportedSizes(next.conductorMaterial, next.method)
        inputs = if (next.conductorSize in sizes) {
            next
        } else {
            val nearest = sizes.firstOrNull { it.circularMils >= next.conductorSize.circularMils } ?: sizes.last()
            next.copy(size = nearest.label)
        }
    }

    /** Loads a saved item once (the screen may recompose many times). */
    fun restore(item: ProjectItem) {
        if (restoredItemId == item.id) return
        restoredItemId = item.id
        ToolInputs.decode<VoltageDropInputs>(item.inputs)?.let { inputs = it }
    }
}

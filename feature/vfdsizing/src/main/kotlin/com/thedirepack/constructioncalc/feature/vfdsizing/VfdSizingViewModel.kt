package com.thedirepack.constructioncalc.feature.vfdsizing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.thedirepack.constructioncalc.core.model.ProjectItem
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.feature.vfdsizing.domain.VfdSizing

class VfdSizingViewModel : ViewModel() {
    var inputs by mutableStateOf(VfdSizingInputs())
        private set

    private var restoredItemId: String? = null

    /** Applies an edit. If the new voltage has no value for the chosen horsepower, moves to the nearest listed one. */
    fun update(change: (VfdSizingInputs) -> VfdSizingInputs) {
        inputs = normalized(change(inputs))
    }

    /** Loads a saved item once (the screen may recompose many times). */
    fun restore(item: ProjectItem) {
        if (restoredItemId == item.id) return
        restoredItemId = item.id
        ToolInputs.decode<VfdSizingInputs>(item.inputs)?.let { inputs = normalized(it) }
    }

    private fun normalized(next: VfdSizingInputs): VfdSizingInputs {
        val volts = if (next.volts in VfdSizing.MOTOR_VOLTAGES) next.volts else VfdSizingInputs().volts
        val hp = VfdSizing.nearestHorsepower(next.hp, volts)
        return if (volts == next.volts && hp == next.hp) next else next.copy(volts = volts, hp = hp)
    }
}

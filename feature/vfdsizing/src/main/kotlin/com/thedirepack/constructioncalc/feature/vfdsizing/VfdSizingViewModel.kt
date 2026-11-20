package com.thedirepack.constructioncalc.feature.vfdsizing

import com.thedirepack.constructioncalc.core.ui.tool.ToolInputsViewModel
import com.thedirepack.constructioncalc.feature.vfdsizing.domain.VfdSizing

class VfdSizingViewModel : ToolInputsViewModel<VfdSizingInputs>(VfdSizingInputs(), VfdSizingInputs.serializer()) {
    /** If the voltage has no value for the chosen horsepower, moves to the nearest listed one. */
    override fun normalize(next: VfdSizingInputs): VfdSizingInputs {
        val volts = if (next.volts in VfdSizing.MOTOR_VOLTAGES) next.volts else VfdSizingInputs().volts
        val hp = VfdSizing.nearestHorsepower(next.hp, volts)
        return if (volts == next.volts && hp == next.hp) next else next.copy(volts = volts, hp = hp)
    }
}

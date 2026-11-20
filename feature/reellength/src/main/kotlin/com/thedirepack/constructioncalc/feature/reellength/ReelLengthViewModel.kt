package com.thedirepack.constructioncalc.feature.reellength

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.thedirepack.constructioncalc.core.model.ProjectItem
import com.thedirepack.constructioncalc.core.model.ToolInputs

class ReelLengthViewModel : ViewModel() {
    var inputs by mutableStateOf(ReelLengthInputs())
        private set

    private var restoredItemId: String? = null

    fun update(change: (ReelLengthInputs) -> ReelLengthInputs) {
        inputs = change(inputs)
    }

    /** Loads a saved item once (the screen may recompose many times). */
    fun restore(item: ProjectItem) {
        if (restoredItemId == item.id) return
        restoredItemId = item.id
        ToolInputs.decode<ReelLengthInputs>(item.inputs)?.let { inputs = it }
    }
}

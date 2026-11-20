package com.thedirepack.constructioncalc.feature.conduitbending

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.thedirepack.constructioncalc.core.model.ProjectItem
import com.thedirepack.constructioncalc.core.model.ToolInputs

/** Holds the inputs so rotation keeps them, one instance per opened saved item. */
class ConduitBendingViewModel : ViewModel() {
    var inputs by mutableStateOf(ConduitBendingInputs())
        private set

    private var restoredItemId: String? = null

    fun update(change: (ConduitBendingInputs) -> ConduitBendingInputs) {
        inputs = change(inputs)
    }

    /** Loads a saved item once (the screen may recompose many times). */
    fun restore(item: ProjectItem) {
        if (restoredItemId == item.id) return
        restoredItemId = item.id
        ToolInputs.decode<ConduitBendingInputs>(item.inputs)?.let { inputs = it }
    }
}

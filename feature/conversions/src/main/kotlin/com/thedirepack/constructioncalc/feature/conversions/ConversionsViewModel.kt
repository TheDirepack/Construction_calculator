package com.thedirepack.constructioncalc.feature.conversions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.thedirepack.constructioncalc.core.model.ProjectItem
import com.thedirepack.constructioncalc.core.model.ToolInputs

class ConversionsViewModel : ViewModel() {
    var inputs by mutableStateOf(ConversionsInputs())
        private set

    private var restoredItemId: String? = null

    fun update(change: (ConversionsInputs) -> ConversionsInputs) {
        inputs = change(inputs)
    }

    /** Loads a saved item once (the screen may recompose many times). */
    fun restore(item: ProjectItem) {
        if (restoredItemId == item.id) return
        restoredItemId = item.id
        ToolInputs.decode<ConversionsInputs>(item.inputs)?.let { inputs = it }
    }
}

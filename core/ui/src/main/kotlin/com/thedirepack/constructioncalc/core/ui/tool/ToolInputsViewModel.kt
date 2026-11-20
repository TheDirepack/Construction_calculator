package com.thedirepack.constructioncalc.core.ui.tool

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thedirepack.constructioncalc.core.model.ProjectItem
import com.thedirepack.constructioncalc.core.model.ToolInputs
import kotlinx.serialization.KSerializer

/**
 * The state holder every form-style tool needs: its `@Serializable` inputs, kept across rotation,
 * one instance per opened saved item (see [toolViewModel]). Subclass with a no-argument constructor:
 *
 * ```
 * class ReelLengthViewModel : ToolInputsViewModel<ReelLengthInputs>(ReelLengthInputs(), ReelLengthInputs.serializer())
 * ```
 * Override [normalize] to keep inputs consistent after every change (e.g. a size the new
 * material doesn't have).
 */
open class ToolInputsViewModel<T : Any>(initial: T, private val serializer: KSerializer<T>) : ViewModel() {
    var inputs: T by mutableStateOf(initial)
        private set

    private var restoredItemId: String? = null

    fun update(change: (T) -> T) {
        inputs = normalize(change(inputs))
    }

    /** Replaces the inputs (e.g. prefilled from a project circuit or run). */
    fun load(value: T) {
        inputs = normalize(value)
    }

    /** Loads a saved item once (the screen may recompose many times). Unreadable inputs are ignored. */
    fun restore(item: ProjectItem) {
        if (restoredItemId == item.id) return
        restoredItemId = item.id
        runCatching { ToolInputs.json.decodeFromJsonElement(serializer, item.inputs) }.getOrNull()?.let(::load)
    }

    protected open fun normalize(next: T): T = next
}

/**
 * The tool's [ToolInputsViewModel] for this screen: keyed by the saved item it shows (so each item
 * keeps its own state), restored from that item once.
 */
@Composable
inline fun <reified VM : ToolInputsViewModel<*>> toolViewModel(toolId: String, host: ToolHost): VM {
    val opened = host.openedItem?.takeIf { it.toolId == toolId }
    val vm: VM = viewModel(key = "$toolId:${opened?.id}")
    LaunchedEffect(opened) { opened?.let(vm::restore) }
    return vm
}

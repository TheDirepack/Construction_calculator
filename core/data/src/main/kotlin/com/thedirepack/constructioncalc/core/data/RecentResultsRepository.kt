package com.thedirepack.constructioncalc.core.data

import android.content.Context
import androidx.core.content.edit
import com.thedirepack.constructioncalc.core.model.ProjectCodec
import com.thedirepack.constructioncalc.core.model.RecentResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer

/**
 * Results recently produced by any tool, newest first. Tools [publish] when they finish a
 * calculation; input fields everywhere offer these as one-tap values.
 */
class RecentResultsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("recent_results", Context.MODE_PRIVATE)
    private val serializer = ListSerializer(RecentResult.serializer())
    private val state = MutableStateFlow(load())
    val recents: StateFlow<List<RecentResult>> = state.asStateFlow()

    fun publish(results: List<RecentResult>) {
        if (results.isEmpty()) return
        // Drop older copies of the same value from the same tool, newest first, keep the list short.
        val incoming = results.map { it.identity }.toSet()
        state.update { current -> (results + current.filterNot { it.identity in incoming }).take(MAX) }
        persist()
    }

    /** Restores recents from a backup: replaces, or merges keeping the newest of each value. */
    fun importAll(results: List<RecentResult>, replace: Boolean) {
        state.update { current ->
            val all = if (replace) results else results + current
            all.sortedByDescending { it.at }.distinctBy { it.identity }.take(MAX)
        }
        persist()
    }

    fun clear() {
        state.value = emptyList()
        persist()
    }

    private fun persist() = prefs.edit { putString(KEY, ProjectCodec.json.encodeToString(serializer, state.value)) }

    private fun load(): List<RecentResult> = prefs.getString(KEY, null)
        ?.let { runCatching { ProjectCodec.json.decodeFromString(serializer, it) }.getOrNull() }
        .orEmpty()

    private val RecentResult.identity get() = Triple(toolId, value.key, value.exact ?: value.text)

    private companion object {
        const val KEY = "recent"
        const val MAX = 40
    }
}

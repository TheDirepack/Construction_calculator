package com.thedirepack.constructioncalc.feature.footinch

import android.content.Context
import androidx.core.content.edit
import com.thedirepack.constructioncalc.core.data.ToolBackup
import com.thedirepack.constructioncalc.core.measure.Operator
import com.thedirepack.constructioncalc.core.measure.Token
import com.thedirepack.constructioncalc.core.model.ProjectCodec
import com.thedirepack.constructioncalc.core.model.StoredQuantity
import com.thedirepack.constructioncalc.core.model.stored
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement

/** A calculator token in saved form (project items keep the whole expression, not just the answer). */
@Serializable
sealed interface SavedToken {
    @Serializable @SerialName("literal")
    data class Literal(val text: String) : SavedToken

    @Serializable @SerialName("op")
    data class Op(val operator: Operator) : SavedToken

    @Serializable @SerialName("value")
    data class Value(val quantity: StoredQuantity, val preferInches: Boolean? = null) : SavedToken

    fun toToken(): Token = when (this) {
        is Literal -> Token.Literal(text)
        is Op -> Token.Op(operator)
        is Value -> Token.Value(quantity.toQuantity(), preferInches)
    }

    companion object {
        fun of(token: Token): SavedToken = when (token) {
            is Token.Literal -> Literal(token.text)
            is Token.Op -> Op(token.operator)
            is Token.Value -> Value(token.quantity.stored(), token.preferInches)
        }
    }
}

/** What a saved Foot-Inch item stores in [com.thedirepack.constructioncalc.core.model.ProjectItem.inputs]. */
@Serializable
data class FootInchInputs(
    val tokens: List<SavedToken> = emptyList(),
    val answer: StoredQuantity? = null,
    val preferInches: Boolean? = null,
)

@Serializable
data class HistoryEntry(
    val expression: String,
    val result: StoredQuantity,
    val preferInches: Boolean? = null,
)

/** Foot-Inch's section of a full backup. Add fields with defaults; never rename. */
@Serializable
data class FootInchBackup(val history: List<HistoryEntry> = emptyList())

/** This tool's calculation history (separate from projects). One instance per process. */
class FootInchHistory private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("foot_inch_history", Context.MODE_PRIVATE)
    private val serializer = ListSerializer(HistoryEntry.serializer())
    private val state = MutableStateFlow(load())
    val entries: StateFlow<List<HistoryEntry>> = state.asStateFlow()

    fun add(entry: HistoryEntry) {
        state.update { (listOf(entry) + it).take(MAX) }
        persist()
    }

    fun clear() {
        state.value = emptyList()
        persist()
    }

    /** Backup hook: history goes into full backups and comes back on restore. */
    val backup: ToolBackup = object : ToolBackup {
        override fun export(): JsonElement? =
            if (state.value.isEmpty()) null else ProjectCodec.json.encodeToJsonElement(FootInchBackup.serializer(), FootInchBackup(state.value))

        override fun import(data: JsonElement, replace: Boolean) {
            val restored = ProjectCodec.json.decodeFromJsonElement(FootInchBackup.serializer(), data).history
            state.update { current -> (if (replace) restored else (restored + current).distinct()).take(MAX) }
            persist()
        }
    }

    private fun persist() = prefs.edit { putString(KEY, ProjectCodec.json.encodeToString(serializer, state.value)) }

    private fun load() = prefs.getString(KEY, null)
        ?.let { runCatching { ProjectCodec.json.decodeFromString(serializer, it) }.getOrNull() }
        .orEmpty()

    companion object {
        private const val KEY = "entries"
        private const val MAX = 100

        @Volatile private var instance: FootInchHistory? = null

        fun get(context: Context): FootInchHistory =
            instance ?: synchronized(this) { instance ?: FootInchHistory(context.applicationContext).also { instance = it } }
    }
}

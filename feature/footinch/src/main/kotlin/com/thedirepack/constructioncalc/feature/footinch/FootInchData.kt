package com.thedirepack.constructioncalc.feature.footinch

import android.content.Context
import androidx.core.content.edit
import com.thedirepack.constructioncalc.core.data.ModuleSettings
import com.thedirepack.constructioncalc.core.data.ToolBackup
import com.thedirepack.constructioncalc.core.measure.Operator
import com.thedirepack.constructioncalc.core.measure.Precision
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

    @Serializable @SerialName("open_paren")
    data object OpenParen : SavedToken

    @Serializable @SerialName("close_paren")
    data object CloseParen : SavedToken

    @Serializable @SerialName("sqrt")
    data object Sqrt : SavedToken

    @Serializable @SerialName("square")
    data object Square : SavedToken

    fun toToken(): Token = when (this) {
        is Literal -> Token.Literal(text)
        is Op -> Token.Op(operator)
        is Value -> Token.Value(quantity.toQuantity(), preferInches)
        OpenParen -> Token.OpenParen
        CloseParen -> Token.CloseParen
        Sqrt -> Token.Sqrt
        Square -> Token.Square
    }

    companion object {
        fun of(token: Token): SavedToken = when (token) {
            is Token.Literal -> Literal(token.text)
            is Token.Op -> Op(token.operator)
            is Token.Value -> Value(token.quantity.stored(), token.preferInches)
            Token.OpenParen -> OpenParen
            Token.CloseParen -> CloseParen
            Token.Sqrt -> Sqrt
            Token.Square -> Square
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
    val label: String = "",
    val id: Long = 0L,
)

/** Foot-Inch's section of a full backup. Add fields with defaults; never rename. */
@Serializable
data class FootInchBackup(val history: List<HistoryEntry> = emptyList())

/** Pure list operations for the calculation tape (unit-tested without Android SharedPreferences). */
internal object HistoryTapeOps {
    const val MAX = 500

    fun ensureUniqueIds(entries: List<HistoryEntry>): List<HistoryEntry> {
        val used = HashSet<Long>(entries.size)
        var nextId = (entries.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(0L) + 1L
        return entries.map { entry ->
            if (entry.id > 0L && used.add(entry.id)) {
                entry
            } else {
                while (!used.add(nextId)) nextId++
                entry.copy(id = nextId++)
            }
        }
    }

    fun add(current: List<HistoryEntry>, entry: HistoryEntry): List<HistoryEntry> {
        val nextId = ((current.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(0L)) + 1L
        val assigned = if (entry.id > 0L && current.none { it.id == entry.id }) entry else entry.copy(id = nextId)
        return (listOf(assigned) + current).take(MAX)
    }

    fun remove(current: List<HistoryEntry>, id: Long): List<HistoryEntry> =
        current.filterNot { it.id == id }

    fun setLabel(current: List<HistoryEntry>, id: Long, label: String): List<HistoryEntry> {
        val clean = label.trim()
        return current.map { if (it.id == id) it.copy(label = clean) else it }
    }

    fun merge(current: List<HistoryEntry>, restored: List<HistoryEntry>, replace: Boolean): List<HistoryEntry> {
        val combined = if (replace) {
            restored
        } else {
            (restored + current).distinctBy { Triple(it.expression, it.result, it.label) }
        }
        return ensureUniqueIds(combined.take(MAX))
    }
}

/** This tool's calculation history / tape (separate from projects). One instance per process. */
class FootInchHistory private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("foot_inch_history", Context.MODE_PRIVATE)
    private val serializer = ListSerializer(HistoryEntry.serializer())
    private val state = MutableStateFlow(load())
    val entries: StateFlow<List<HistoryEntry>> = state.asStateFlow()

    fun add(entry: HistoryEntry) {
        state.update { HistoryTapeOps.add(it, entry) }
        persist()
    }

    fun remove(id: Long) {
        state.update { HistoryTapeOps.remove(it, id) }
        persist()
    }

    fun setLabel(id: Long, label: String) {
        state.update { HistoryTapeOps.setLabel(it, id, label) }
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
            state.update { current -> HistoryTapeOps.merge(current, restored, replace) }
            persist()
        }
    }

    private fun persist() = prefs.edit { putString(KEY, ProjectCodec.json.encodeToString(serializer, state.value)) }

    private fun load(): List<HistoryEntry> = prefs.getString(KEY, null)
        ?.let { runCatching { ProjectCodec.json.decodeFromString(serializer, it) }.getOrNull() }
        ?.let(HistoryTapeOps::ensureUniqueIds)
        .orEmpty()

    companion object {
        private const val KEY = "entries"

        @Volatile private var instance: FootInchHistory? = null

        fun get(context: Context): FootInchHistory =
            instance ?: synchronized(this) { instance ?: FootInchHistory(context.applicationContext).also { instance = it } }
    }
}

/** Keypad layout options for Foot-Inch. */
enum class KeypadLayout(val label: String) {
    CLASSIC("Classic"),
    DUAL_SPLIT("Dual-Split");

    companion object {
        val DEFAULT = CLASSIC
        fun parse(raw: String?): KeypadLayout =
            entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}

/** Input fraction-key precision on the Foot-Inch keypad, decoupled from output rounding. */
enum class InputFractionPrecision(val label: String, val precision: Precision?) {
    MATCH_OUTPUT("Match output", null),
    EIGHTHS("1/8\"", Precision.EIGHTH),
    SIXTEENTHS("1/16\"", Precision.SIXTEENTH),
    THIRTY_SECONDS("1/32\"", Precision.THIRTY_SECOND);

    fun resolve(outputPrecision: Precision): Precision = precision ?: outputPrecision

    companion object {
        val DEFAULT = MATCH_OUTPUT
        fun parse(raw: String?): InputFractionPrecision =
            entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}

/** Resolved per-tool customization preferences for Foot-Inch. */
internal data class FootInchSettings(
    val keypadLayout: KeypadLayout = KeypadLayout.DEFAULT,
    val showFractionKeys: Boolean = true,
    val showInlineTape: Boolean = true,
    val inputFractionPrecision: InputFractionPrecision = InputFractionPrecision.DEFAULT,
    val showFunctionStrip: Boolean = true,
    val colorBlindShapes: Boolean = false,
) {
    fun effectiveFractionPrecision(outputPrecision: Precision): Precision =
        inputFractionPrecision.resolve(outputPrecision)

    companion object {
        fun read(store: ModuleSettings): FootInchSettings = FootInchSettings(
            keypadLayout = KeypadLayout.parse(store.getString(FootInchPrefs.KEYPAD_LAYOUT, KeypadLayout.DEFAULT.name)),
            showFractionKeys = store.getBoolean(FootInchPrefs.SHOW_FRACTION_KEYS, true),
            showInlineTape = store.getBoolean(FootInchPrefs.SHOW_INLINE_TAPE, true),
            inputFractionPrecision = InputFractionPrecision.parse(
                store.getString(FootInchPrefs.INPUT_FRACTION_PRECISION, InputFractionPrecision.DEFAULT.name),
            ),
            showFunctionStrip = store.getBoolean(FootInchPrefs.SHOW_FUNCTION_STRIP, true),
            colorBlindShapes = store.getBoolean(FootInchPrefs.COLOR_BLIND_SHAPES, false),
        )
    }
}

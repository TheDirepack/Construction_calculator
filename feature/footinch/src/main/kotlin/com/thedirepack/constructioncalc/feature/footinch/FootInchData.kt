package com.thedirepack.constructioncalc.feature.footinch

import android.content.Context
import androidx.core.content.edit
import com.thedirepack.constructioncalc.core.data.ModuleSettings
import com.thedirepack.constructioncalc.core.data.ToolBackup
import com.thedirepack.constructioncalc.core.measure.CalcException
import com.thedirepack.constructioncalc.core.measure.Operator
import com.thedirepack.constructioncalc.core.measure.Precision
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.Token
import com.thedirepack.constructioncalc.core.model.ProjectCodec
import com.thedirepack.constructioncalc.core.model.StoredQuantity
import com.thedirepack.constructioncalc.core.model.stored
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement

/** A calculator token in saved form (project items and tape steps keep exact expressions). */
@Serializable
sealed interface SavedToken {
    @Serializable @SerialName("literal")
    data class Literal(val text: String) : SavedToken

    @Serializable @SerialName("op")
    data class Op(val operator: Operator) : SavedToken

    @Serializable @SerialName("value")
    data class Value(val quantity: StoredQuantity, val preferInches: Boolean? = null) : SavedToken

    /** In a tape step: the result of the step before it. */
    @Serializable @SerialName("prev_answer")
    data object PrevAnswer : SavedToken

    @Serializable @SerialName("open_paren")
    data object OpenParen : SavedToken

    @Serializable @SerialName("close_paren")
    data object CloseParen : SavedToken

    @Serializable @SerialName("sqrt")
    data object Sqrt : SavedToken

    @Serializable @SerialName("square")
    data object Square : SavedToken

    /** The calculator token; [PrevAnswer] becomes [prevAnswer], and fails without one. */
    fun toToken(prevAnswer: Quantity? = null, prevPreferInches: Boolean? = null): Token = when (this) {
        is Literal -> Token.Literal(text)
        is Op -> Token.Op(operator)
        is Value -> Token.Value(quantity.toQuantity(), preferInches)
        PrevAnswer -> {
            val prev = prevAnswer ?: throw CalcException.Syntax("Needs a step before it")
            Token.Value(prev, prevPreferInches, fromPreviousAnswer = true)
        }
        OpenParen -> Token.OpenParen
        CloseParen -> Token.CloseParen
        Sqrt -> Token.Sqrt
        Square -> Token.Square
    }

    companion object {
        /** Saved form of [token]; a carried previous answer is kept as its value. */
        fun of(token: Token): SavedToken = when (token) {
            is Token.Literal -> Literal(token.text)
            is Token.Op -> Op(token.operator)
            is Token.Value -> Value(token.quantity.stored(), token.preferInches)
            Token.OpenParen -> OpenParen
            Token.CloseParen -> CloseParen
            Token.Sqrt -> Sqrt
            Token.Square -> Square
        }

        /** Saved form of [token] in a tape step: a carried previous answer becomes [PrevAnswer]. */
        fun ofForTape(token: Token): SavedToken =
            if (token is Token.Value && token.fromPreviousAnswer) PrevAnswer else of(token)
    }
}

/** What a saved Foot-Inch item stores in [com.thedirepack.constructioncalc.core.model.ProjectItem.inputs]. */
@Serializable
data class FootInchInputs(
    val tokens: List<SavedToken> = emptyList(),
    val answer: StoredQuantity? = null,
    val preferInches: Boolean? = null,
)

/** Foot-Inch's section of a full backup: the calculation tape and named memory. */
@Serializable
data class FootInchBackup(
    val groups: List<CalcGroup> = emptyList(),
    val memory: List<MemoryEntry> = emptyList(),
)

/**
 * This tool's calculation tape (grouped, editable steps) and named memory, kept across launches
 * and separate from projects. One instance per process.
 */
class FootInchHistory private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("foot_inch_history", Context.MODE_PRIVATE)
    private val groupsSerializer = ListSerializer(CalcGroup.serializer())
    private val memorySerializer = ListSerializer(MemoryEntry.serializer())

    private val groupState = MutableStateFlow(loadGroups())
    val groups: StateFlow<List<CalcGroup>> = groupState.asStateFlow()

    private val memoryState = MutableStateFlow(loadMemory())
    val memory: StateFlow<List<MemoryEntry>> = memoryState.asStateFlow()

    // --- Calculation tape ---

    fun startNewGroup(expressionTokens: List<Token>): CalcGroup {
        val (updated, group) = CalculationTapeOps.startNewGroup(groupState.value, expressionTokens)
        setGroups(updated)
        return group
    }

    /** Returns how many steps were added (0 when the group is gone). */
    fun appendToGroup(groupId: Long, expressionTokens: List<Token>): Int {
        val outcome = CalculationTapeOps.appendToGroup(groupState.value, groupId, expressionTokens) ?: return 0
        setGroups(outcome.groups)
        return outcome.appendedCount
    }

    /** Returns how many steps replaced the old ones (0 when the group is gone). */
    fun replaceLastSteps(groupId: Long, replaceCount: Int, expressionTokens: List<Token>): Int {
        val outcome = CalculationTapeOps.replaceLastSteps(groupState.value, groupId, replaceCount, expressionTokens) ?: return 0
        setGroups(outcome.groups)
        return outcome.appendedCount
    }

    fun editStep(groupId: Long, stepId: Long, newTokens: List<SavedToken>): TapeEditResult =
        CalculationTapeOps.editStep(groupState.value, groupId, stepId, newTokens).also {
            if (it is TapeEditResult.Updated) setGroups(it.groups)
        }

    fun deleteStep(groupId: Long, stepId: Long): TapeEditResult =
        CalculationTapeOps.deleteStep(groupState.value, groupId, stepId).also {
            if (it is TapeEditResult.Updated) setGroups(it.groups)
        }

    fun toggleFavorite(groupId: Long) = setGroups(CalculationTapeOps.toggleFavorite(groupState.value, groupId))

    fun setGroupLabel(groupId: Long, label: String) = setGroups(CalculationTapeOps.setGroupLabel(groupState.value, groupId, label))

    fun setStepLabel(groupId: Long, stepId: Long, label: String) =
        setGroups(CalculationTapeOps.setStepLabel(groupState.value, groupId, stepId, label))

    fun removeGroup(groupId: Long) = setGroups(CalculationTapeOps.removeGroup(groupState.value, groupId))

    /** Clears the tape except starred calculations. */
    fun clearRecent() = setGroups(CalculationTapeOps.clearRecent(groupState.value))

    fun clear() = setGroups(emptyList())

    // --- Named memory ---

    /** Saves [value] as [label] (blank: next free `M1`, `M2`…); returns the slot id. */
    fun storeMemory(value: Quantity, preferInches: Boolean?, label: String = ""): Long {
        val (updated, id) = MemoryOps.store(memoryState.value, value, preferInches, label)
        setMemory(updated)
        return id
    }

    fun applyMemoryOp(op: Operator, operand: Quantity, operandPreferInches: Boolean?): MemoryOpResult =
        MemoryOps.applyOperator(memoryState.value, op, operand, operandPreferInches).also {
            if (it is MemoryOpResult.Success) setMemory(it.entries)
        }

    fun touchMemory(id: Long) = setMemory(MemoryOps.touch(memoryState.value, id))

    fun renameMemory(id: Long, label: String) = setMemory(MemoryOps.rename(memoryState.value, id, label))

    fun removeMemory(id: Long) = setMemory(MemoryOps.remove(memoryState.value, id))

    fun clearMemory() = setMemory(emptyList())

    /** Backup hook: the tape and memory go into full backups and come back on restore. */
    val backup: ToolBackup = object : ToolBackup {
        override fun export(): JsonElement? {
            if (groupState.value.isEmpty() && memoryState.value.isEmpty()) return null
            return ProjectCodec.json.encodeToJsonElement(
                FootInchBackup.serializer(),
                FootInchBackup(groups = groupState.value, memory = memoryState.value),
            )
        }

        override fun import(data: JsonElement, replace: Boolean) {
            val doc = ProjectCodec.json.decodeFromJsonElement(FootInchBackup.serializer(), data)
            setGroups(CalculationTapeOps.mergeGroups(groupState.value, doc.groups, replace))
            setMemory(MemoryOps.merge(memoryState.value, doc.memory, replace))
        }
    }

    private fun setGroups(groups: List<CalcGroup>) {
        groupState.value = groups
        prefs.edit { putString(KEY_GROUPS, ProjectCodec.json.encodeToString(groupsSerializer, groups)) }
    }

    private fun setMemory(entries: List<MemoryEntry>) {
        memoryState.value = entries
        prefs.edit { putString(KEY_MEMORY, ProjectCodec.json.encodeToString(memorySerializer, entries)) }
    }

    private fun loadGroups(): List<CalcGroup> = prefs.getString(KEY_GROUPS, null)
        ?.let { runCatching { ProjectCodec.json.decodeFromString(groupsSerializer, it) }.getOrNull() }
        ?.let(CalculationTapeOps::ensureUniqueIds)
        .orEmpty()

    private fun loadMemory(): List<MemoryEntry> = prefs.getString(KEY_MEMORY, null)
        ?.let { runCatching { ProjectCodec.json.decodeFromString(memorySerializer, it) }.getOrNull() }
        ?.let(MemoryOps::ensureUniqueIds)
        .orEmpty()

    companion object {
        private const val KEY_GROUPS = "groups"
        private const val KEY_MEMORY = "memory"

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

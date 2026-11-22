package com.thedirepack.constructioncalc.feature.footinch

import android.content.Context
import androidx.core.content.edit
import com.thedirepack.constructioncalc.core.data.ToolBackup
import com.thedirepack.constructioncalc.core.measure.CalcException
import com.thedirepack.constructioncalc.core.measure.Operator
import com.thedirepack.constructioncalc.core.measure.Quantity
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

/** A calculator token in saved form (project items and tape steps keep exact expressions). */
@Serializable
sealed interface SavedToken {
    @Serializable @SerialName("literal")
    data class Literal(val text: String) : SavedToken

    @Serializable @SerialName("op")
    data class Op(val operator: Operator) : SavedToken

    @Serializable @SerialName("value")
    data class Value(val quantity: StoredQuantity, val preferInches: Boolean? = null) : SavedToken

    /** Placeholder for the immediately preceding step's result inside a grouped calculation tape. */
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

    fun toToken(prevAnswer: Quantity? = null, prevPreferInches: Boolean? = null): Token = when (this) {
        is Literal -> Token.Literal(text)
        is Op -> Token.Op(operator)
        is Value -> Token.Value(quantity.toQuantity(), preferInches)
        PrevAnswer -> {
            val prev = prevAnswer ?: throw CalcException.Syntax("Needs a previous step")
            Token.Value(prev, prevPreferInches, fromPreviousAnswer = true)
        }
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

        fun ofForTape(token: Token): SavedToken = when {
            token is Token.Value && token.fromPreviousAnswer -> PrevAnswer
            else -> of(token)
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
data class FootInchBackup(
    val history: List<HistoryEntry> = emptyList(),
    val groups: List<CalcGroup>? = null,
    val memory: List<MemoryEntry> = emptyList(),
    val activeMemoryId: Long? = null,
) {
    internal fun restoredGroups(): List<CalcGroup> =
        groups ?: CalculationTapeOps.migrateLegacyHistory(history)
}

/** Legacy flat calculation tape helpers preserved for backward compatibility. */
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

/**
 * Persisted storage for Foot-Inch grouped calculations and multi-slot memory registers.
 * One instance per process.
 */
class FootInchHistory private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("foot_inch_history", Context.MODE_PRIVATE)
    private val legacySerializer = ListSerializer(HistoryEntry.serializer())
    private val groupsSerializer = ListSerializer(CalcGroup.serializer())
    private val memorySerializer = ListSerializer(MemoryEntry.serializer())

    private val groupState = MutableStateFlow(loadGroups())
    val groups: StateFlow<List<CalcGroup>> = groupState.asStateFlow()

    private val memoryState = MutableStateFlow(loadMemory())
    val memoryEntries: StateFlow<List<MemoryEntry>> = memoryState.asStateFlow()

    private val activeMemoryState = MutableStateFlow(loadActiveMemoryId(memoryState.value))
    val activeMemoryId: StateFlow<Long?> = activeMemoryState.asStateFlow()

    /** Legacy flat view derived from [groups] when inspected directly. */
    private val entryState = MutableStateFlow(loadLegacyEntries())
    val entries: StateFlow<List<HistoryEntry>> = entryState.asStateFlow()

    // --- Grouped calculation tape operations ---

    fun startNewGroup(expressionTokens: List<Token>): CalcGroup {
        val (updated, group) = CalculationTapeOps.startNewGroup(groupState.value, expressionTokens)
        groupState.value = updated
        persistGroups()
        return group
    }

    fun appendToGroup(groupId: Long, expressionTokens: List<Token>): Int {
        val outcome = CalculationTapeOps.appendToGroup(groupState.value, groupId, expressionTokens)
            ?: return 0
        groupState.value = outcome.groups
        persistGroups()
        return outcome.appendedCount
    }

    fun replaceLastSteps(groupId: Long, replaceCount: Int, expressionTokens: List<Token>): Int {
        val outcome = CalculationTapeOps.replaceLastSteps(groupState.value, groupId, replaceCount, expressionTokens)
            ?: return 0
        groupState.value = outcome.groups
        persistGroups()
        return outcome.appendedCount
    }

    fun editStep(groupId: Long, stepId: Long, newTokens: List<SavedToken>): TapeEditResult {
        val result = CalculationTapeOps.editStep(groupState.value, groupId, stepId, newTokens)
        if (result is TapeEditResult.Updated) {
            groupState.value = result.groups
            persistGroups()
        }
        return result
    }

    fun deleteStep(groupId: Long, stepId: Long): TapeEditResult {
        val result = CalculationTapeOps.deleteStep(groupState.value, groupId, stepId)
        if (result is TapeEditResult.Updated) {
            groupState.value = result.groups
            persistGroups()
        }
        return result
    }

    fun toggleFavorite(groupId: Long) {
        groupState.update { CalculationTapeOps.toggleFavorite(it, groupId) }
        persistGroups()
    }

    fun setGroupLabel(groupId: Long, label: String) {
        groupState.update { CalculationTapeOps.setGroupLabel(it, groupId, label) }
        persistGroups()
    }

    fun setStepLabel(groupId: Long, stepId: Long, label: String) {
        groupState.update { CalculationTapeOps.setStepLabel(it, groupId, stepId, label) }
        persistGroups()
    }

    fun removeGroup(groupId: Long) {
        groupState.update { CalculationTapeOps.removeGroup(it, groupId) }
        persistGroups()
    }

    fun clearRecentGroups() {
        groupState.update(CalculationTapeOps::clearRecent)
        persistGroups()
    }

    fun clear() {
        groupState.value = CalculationTapeOps.clearAll()
        entryState.value = emptyList()
        persistGroups()
        persistLegacy()
    }

    // --- Legacy flat helpers preserved ---

    fun add(entry: HistoryEntry) {
        entryState.update { HistoryTapeOps.add(it, entry) }
        persistLegacy()
    }

    fun remove(id: Long) {
        entryState.update { HistoryTapeOps.remove(it, id) }
        persistLegacy()
    }

    fun setLabel(id: Long, label: String) {
        entryState.update { HistoryTapeOps.setLabel(it, id, label) }
        persistLegacy()
    }

    // --- Persisted multi-slot memory operations ---

    fun storeMemory(value: Quantity, preferInches: Boolean?, label: String = ""): Long {
        val (updated, newId) = MemoryOps.store(memoryState.value, value, preferInches, label)
        memoryState.value = updated
        activeMemoryState.value = newId
        persistMemory()
        return newId
    }

    fun applyMemoryOp(
        op: Operator,
        operand: Quantity,
        operandPreferInches: Boolean?,
        targetId: Long? = null,
    ): MemoryOpResult {
        val result = MemoryOps.applyOperator(
            current = memoryState.value,
            activeId = activeMemoryState.value,
            targetId = targetId,
            op = op,
            operand = operand,
            operandPreferInches = operandPreferInches,
        )
        if (result is MemoryOpResult.Success) {
            memoryState.value = result.entries
            activeMemoryState.value = result.activeId
            persistMemory()
        }
        return result
    }

    fun removeMemory(id: Long) {
        val (updated, nextActive) = MemoryOps.remove(memoryState.value, activeMemoryState.value, id)
        memoryState.value = updated
        activeMemoryState.value = nextActive
        persistMemory()
    }

    fun setMemoryLabel(id: Long, label: String) {
        memoryState.update { MemoryOps.setLabel(it, id, label) }
        persistMemory()
    }

    fun selectActiveMemory(id: Long) {
        activeMemoryState.value = MemoryOps.resolveActiveId(memoryState.value, id)
        persistMemory()
    }

    fun clearAllMemory() {
        memoryState.value = emptyList()
        activeMemoryState.value = null
        persistMemory()
    }

    /** Backup hook: grouped tape and memory go into full backups and come back on restore. */
    val backup: ToolBackup = object : ToolBackup {
        override fun export(): JsonElement? {
            if (groupState.value.isEmpty() && memoryState.value.isEmpty() && entryState.value.isEmpty()) {
                return null
            }
            return ProjectCodec.json.encodeToJsonElement(
                FootInchBackup.serializer(),
                FootInchBackup(
                    history = entryState.value,
                    groups = groupState.value,
                    memory = memoryState.value,
                    activeMemoryId = activeMemoryState.value,
                ),
            )
        }

        override fun import(data: JsonElement, replace: Boolean) {
            val backupDoc = ProjectCodec.json.decodeFromJsonElement(FootInchBackup.serializer(), data)
            groupState.update { current -> CalculationTapeOps.mergeGroups(current, backupDoc.restoredGroups(), replace) }
            memoryState.update { current -> MemoryOps.merge(current, backupDoc.memory, replace) }
            activeMemoryState.value = MemoryOps.resolveActiveId(
                memoryState.value,
                backupDoc.activeMemoryId ?: activeMemoryState.value,
            )
            entryState.update { current -> HistoryTapeOps.merge(current, backupDoc.history, replace) }
            persistGroups()
            persistMemory()
            persistLegacy()
        }
    }

    private fun persistGroups() =
        prefs.edit { putString(KEY_GROUPS, ProjectCodec.json.encodeToString(groupsSerializer, groupState.value)) }

    private fun persistMemory() = prefs.edit {
        putString(KEY_MEMORY, ProjectCodec.json.encodeToString(memorySerializer, memoryState.value))
        val id = activeMemoryState.value
        if (id != null) putLong(KEY_ACTIVE_MEMORY_ID, id) else remove(KEY_ACTIVE_MEMORY_ID)
    }

    private fun persistLegacy() =
        prefs.edit { putString(KEY, ProjectCodec.json.encodeToString(legacySerializer, entryState.value)) }

    private fun loadGroups(): List<CalcGroup> {
        val raw = prefs.getString(KEY_GROUPS, null)
        if (raw != null) {
            return runCatching { ProjectCodec.json.decodeFromString(groupsSerializer, raw) }
                .getOrNull()
                ?.let(CalculationTapeOps::ensureUniqueIds)
                .orEmpty()
        }
        val legacy = loadLegacyEntries()
        if (legacy.isEmpty()) return emptyList()
        val migrated = CalculationTapeOps.migrateLegacyHistory(legacy)
        prefs.edit { putString(KEY_GROUPS, ProjectCodec.json.encodeToString(groupsSerializer, migrated)) }
        return migrated
    }

    private fun loadMemory(): List<MemoryEntry> = prefs.getString(KEY_MEMORY, null)
        ?.let { runCatching { ProjectCodec.json.decodeFromString(memorySerializer, it) }.getOrNull() }
        ?.let(MemoryOps::ensureUniqueIds)
        .orEmpty()

    private fun loadActiveMemoryId(entries: List<MemoryEntry>): Long? {
        val raw = if (prefs.contains(KEY_ACTIVE_MEMORY_ID)) prefs.getLong(KEY_ACTIVE_MEMORY_ID, -1L) else -1L
        return MemoryOps.resolveActiveId(entries, raw.takeIf { it > 0L })
    }

    private fun loadLegacyEntries(): List<HistoryEntry> = prefs.getString(KEY, null)
        ?.let { runCatching { ProjectCodec.json.decodeFromString(legacySerializer, it) }.getOrNull() }
        ?.let(HistoryTapeOps::ensureUniqueIds)
        .orEmpty()

    companion object {
        private const val KEY = "entries"
        private const val KEY_GROUPS = "groups"
        private const val KEY_MEMORY = "memory"
        private const val KEY_ACTIVE_MEMORY_ID = "active_memory_id"

        @Volatile private var instance: FootInchHistory? = null

        fun get(context: Context): FootInchHistory =
            instance ?: synchronized(this) { instance ?: FootInchHistory(context.applicationContext).also { instance = it } }
    }
}

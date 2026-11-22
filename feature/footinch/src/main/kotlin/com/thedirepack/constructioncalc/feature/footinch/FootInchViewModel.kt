package com.thedirepack.constructioncalc.feature.footinch

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.measure.Operator
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.Token
import com.thedirepack.constructioncalc.core.measure.input.CalcInput
import com.thedirepack.constructioncalc.core.measure.input.CalcState
import com.thedirepack.constructioncalc.core.measure.input.Calculation
import com.thedirepack.constructioncalc.core.measure.input.Key
import com.thedirepack.constructioncalc.core.model.ProjectItem
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.core.ui.format
import com.thedirepack.constructioncalc.core.ui.tool.ItemDraft
import kotlinx.coroutines.flow.StateFlow

class FootInchViewModel(val history: FootInchHistory) : ViewModel() {

    var state by mutableStateOf(CalcState())
        private set

    /** Currently active grouped calculation, or null when starting from scratch. */
    var activeGroupId by mutableStateOf<Long?>(null)
        private set

    /** Most recent completed answer for the `Ans` key. */
    var lastAnswer by mutableStateOf<Register?>(null)
        private set

    private var restoredItemId: String? = null
    private var lastAppendedCount: Int = 0
    private var editingLastStepCount: Int = 0

    val groups: StateFlow<List<CalcGroup>> get() = history.groups
    val memoryEntries: StateFlow<List<MemoryEntry>> get() = history.memoryEntries
    val activeMemoryId: StateFlow<Long?> get() = history.activeMemoryId

    fun activeGroup(list: List<CalcGroup> = history.groups.value): CalcGroup? =
        activeGroupId?.let { id -> list.firstOrNull { it.id == id } }

    fun activeMemory(
        list: List<MemoryEntry> = history.memoryEntries.value,
        id: Long? = history.activeMemoryId.value,
    ): MemoryEntry? = MemoryOps.activeEntry(list, id)

    /** Stored active memory register (`MS`, `M+`, `M−`, `M×`, `M÷`, `MR`, `MC`). */
    val memory: Register?
        get() = activeMemory()?.let { Register(it.value.toQuantity(), it.preferInches) }

    val hasMemory: Boolean
        get() = history.memoryEntries.value.isNotEmpty()

    /** What `Ans` inserts: the last answer, else the newest group's final result. */
    val effectiveAns: Register?
        get() = lastAnswer
            ?: history.groups.value.firstOrNull()?.finalStep?.let { Register(it.result.toQuantity(), it.preferInches) }

    /** Applies a key. Returns the finished calculation when `=` succeeded. */
    fun onKey(key: Key, settings: AppSettings): CalcInput.Outcome {
        val wasAnswered = state.answer != null
        val startsFreshFromAnswered = wasAnswered && when (key) {
            is Key.Char, is Key.Text, is Key.Fraction, Key.OpenParen -> true
            is Key.Recall -> !key.fromPreviousAnswer
            else -> false
        }
        if (key == Key.Clear || startsFreshFromAnswered) {
            activeGroupId = null
            lastAppendedCount = 0
            editingLastStepCount = 0
        } else if (wasAnswered && key == Key.Backspace && activeGroupId != null) {
            editingLastStepCount = lastAppendedCount.coerceAtLeast(1)
        }

        val outcome = CalcInput.reduce(state, key)
        state = outcome.state

        if (outcome.state.tokens.isEmpty() && outcome.state.answer == null) {
            activeGroupId = null
            lastAppendedCount = 0
            editingLastStepCount = 0
        }

        outcome.completed?.let { calc ->
            val currentGroupId = activeGroupId
            val hasActiveGroup = currentGroupId != null && history.groups.value.any { it.id == currentGroupId }
            val committedSteps = when {
                hasActiveGroup && editingLastStepCount > 0 ->
                    history.replaceLastSteps(currentGroupId, editingLastStepCount, calc.expression)
                hasActiveGroup && calc.expression.any { (it as? Token.Value)?.fromPreviousAnswer == true } ->
                    history.appendToGroup(currentGroupId, calc.expression)
                else -> 0
            }
            if (committedSteps > 0) {
                lastAppendedCount = committedSteps
            } else {
                val group = history.startNewGroup(calc.expression)
                activeGroupId = group.id
                lastAppendedCount = group.steps.size
            }
            editingLastStepCount = 0
            val inches = CalcInput.prefersInches(calc.expression)
            lastAnswer = Register(calc.result, inches)
        }
        return outcome
    }

    // --- Group / step editing actions ---

    fun editStep(groupId: Long, stepId: Long, newTokens: List<SavedToken>): String? =
        when (val result = history.editStep(groupId, stepId, newTokens)) {
            is TapeEditResult.Invalid -> result.message
            is TapeEditResult.Updated -> {
                result.group?.let { syncActiveStateWithGroup(it) }
                null
            }
        }

    fun deleteStep(groupId: Long, stepId: Long): String? =
        when (val result = history.deleteStep(groupId, stepId)) {
            is TapeEditResult.Invalid -> result.message
            is TapeEditResult.Updated -> {
                if (result.group != null) {
                    syncActiveStateWithGroup(result.group)
                } else if (groupId == activeGroupId) {
                    activeGroupId = null
                    lastAppendedCount = 0
                    editingLastStepCount = 0
                    state = CalcState()
                }
                null
            }
        }

    fun deleteGroup(groupId: Long) {
        history.removeGroup(groupId)
        if (groupId == activeGroupId) {
            activeGroupId = null
            lastAppendedCount = 0
            editingLastStepCount = 0
        }
    }

    fun toggleFavorite(groupId: Long) = history.toggleFavorite(groupId)

    fun renameGroup(groupId: Long, label: String) = history.setGroupLabel(groupId, label)

    fun renameStep(groupId: Long, stepId: Long, label: String) = history.setStepLabel(groupId, stepId, label)

    fun clearRecentGroups() {
        history.clearRecentGroups()
        if (activeGroupId != null && history.groups.value.none { it.id == activeGroupId }) {
            activeGroupId = null
            lastAppendedCount = 0
            editingLastStepCount = 0
        }
    }

    fun clearAllGroups() {
        history.clear()
        activeGroupId = null
        lastAppendedCount = 0
        editingLastStepCount = 0
    }

    /** Continues working from the final answer of [group] inside that same group. */
    fun continueGroup(group: CalcGroup) {
        val lastIdx = group.steps.lastIndex
        if (lastIdx < 0) return
        val lastStep = group.steps[lastIdx]
        val qty = lastStep.result.toQuantity()
        activeGroupId = group.id
        editingLastStepCount = 0
        lastAppendedCount = 1
        state = CalcState(
            tokens = CalculationTapeOps.resolvedTokensForStep(group, lastIdx),
            answer = qty,
        )
        lastAnswer = Register(qty, lastStep.preferInches)
    }

    private fun syncActiveStateWithGroup(updatedGroup: CalcGroup) {
        val finalStep = updatedGroup.finalStep ?: return
        val finalQty = finalStep.result.toQuantity()
        if (updatedGroup.id != activeGroupId) return
        lastAnswer = Register(finalQty, finalStep.preferInches)
        if (state.answer != null) {
            state = CalcState(
                tokens = CalculationTapeOps.resolvedTokensForStep(updatedGroup, updatedGroup.steps.lastIndex),
                answer = finalQty,
            )
            lastAppendedCount = 1
        } else if (state.tokens.any { (it as? Token.Value)?.fromPreviousAnswer == true }) {
            val source = if (editingLastStepCount > 0) {
                updatedGroup.steps.getOrNull(updatedGroup.steps.lastIndex - editingLastStepCount)
            } else finalStep
            if (source == null) return
            val patched = state.tokens.map { tok ->
                if (tok is Token.Value && tok.fromPreviousAnswer) {
                    Token.Value(source.result.toQuantity(), source.preferInches, fromPreviousAnswer = true)
                } else {
                    tok
                }
            }
            state = CalcState(tokens = patched)
        }
    }

    // --- Memory register actions ---

    fun memoryStore(label: String = ""): Boolean {
        val current = state.result ?: return false
        history.storeMemory(current, preferInches(), label)
        return true
    }

    fun memoryAdd(targetId: Long? = null): Boolean = applyMemoryOp(Operator.ADD, targetId)

    fun memorySubtract(targetId: Long? = null): Boolean = applyMemoryOp(Operator.SUBTRACT, targetId)

    fun memoryMultiply(targetId: Long? = null): Boolean = applyMemoryOp(Operator.MULTIPLY, targetId)

    fun memoryDivide(targetId: Long? = null): Boolean = applyMemoryOp(Operator.DIVIDE, targetId)

    /** Applies [op] between a memory slot and the current display value; returns error text on incompatibility or null. */
    fun applyMemoryOpWithError(op: Operator, targetId: Long? = null): String? {
        val current = state.result ?: return "Calculate a value first"
        return when (val res = history.applyMemoryOp(op, current, preferInches(), targetId)) {
            is MemoryOpResult.Success -> null
            MemoryOpResult.Empty -> "No memory slot selected"
            is MemoryOpResult.Error -> res.message
        }
    }

    private fun applyMemoryOp(op: Operator, targetId: Long?): Boolean {
        val current = state.result ?: return false
        return when (val res = history.applyMemoryOp(op, current, preferInches(), targetId)) {
            is MemoryOpResult.Success -> true
            MemoryOpResult.Empty -> false
            is MemoryOpResult.Error -> {
                state = state.copy(error = res.message)
                false
            }
        }
    }

    /** Inserts the stored active (or chosen) memory value into the expression (`MR`). */
    fun memoryRecall(entry: MemoryEntry? = null): Boolean {
        val chosen = entry ?: activeMemory() ?: return false
        history.selectActiveMemory(chosen.id)
        return recallQuantity(chosen.value.toQuantity(), chosen.preferInches, fromPreviousAnswer = false)
    }

    /** Clears the active or targeted memory register (`MC`). */
    fun memoryClear(targetId: Long? = null): Boolean {
        val chosenId = targetId ?: history.activeMemoryId.value ?: history.memoryEntries.value.firstOrNull()?.id ?: return false
        history.removeMemory(chosenId)
        return true
    }

    fun memoryClearAll() = history.clearAllMemory()

    fun memoryRename(id: Long, label: String) = history.setMemoryLabel(id, label)

    fun memorySelectActive(id: Long) = history.selectActiveMemory(id)

    /** Inserts the most recent answer (`Ans`) into the expression, preserving group continuity if an active group exists. */
    fun recallAns(): Boolean {
        val group = activeGroup()
        val finalStep = group?.finalStep
        if (finalStep != null) {
            return recallQuantity(finalStep.result.toQuantity(), finalStep.preferInches, fromPreviousAnswer = true)
        }
        val ans = effectiveAns ?: return false
        return recallQuantity(ans.value, ans.preferInches, fromPreviousAnswer = false)
    }

    /** Inserts an exact [Quantity] (e.g. from Helpers, Tape, or Memory) into the current expression. */
    fun recallQuantity(
        quantity: Quantity,
        preferInches: Boolean? = null,
        fromPreviousAnswer: Boolean = false,
    ): Boolean {
        if (state.answer != null && !fromPreviousAnswer) {
            activeGroupId = null
            lastAppendedCount = 0
            editingLastStepCount = 0
        }
        val outcome = CalcInput.reduce(state, Key.Recall(quantity, preferInches, fromPreviousAnswer))
        state = outcome.state
        return outcome.accepted
    }

    /** Loads a saved item once (the screen may recompose many times). */
    fun restore(item: ProjectItem) {
        if (restoredItemId == item.id) return
        restoredItemId = item.id
        val inputs = ToolInputs.decode<FootInchInputs>(item.inputs) ?: return
        val tokens = inputs.tokens.map { it.toToken() }
        val answer = inputs.answer?.toQuantity()
        activeGroupId = null
        lastAppendedCount = 0
        editingLastStepCount = 0
        state = CalcState(tokens = tokens, answer = answer)
        if (answer != null) lastAnswer = Register(answer, inputs.preferInches)
    }

    fun preferInches(): Boolean? = CalcInput.prefersInches(state.tokens)

    fun formatExpression(tokens: List<Token>, settings: AppSettings): String =
        FootInchFormat.expression(tokens, settings, CalcInput.prefersInches(tokens))

    /** The result as a value other tools can use. */
    fun resultValue(calc: Calculation, settings: AppSettings): ResultValue {
        val inches = CalcInput.prefersInches(calc.expression)
        return ResultValue.of("result", "Foot-Inch result", calc.result, settings.format(calc.result, inches))
    }

    /** A project item for the current answer, or null if there is none yet. */
    fun draft(settings: AppSettings): ItemDraft? {
        val answer: Quantity = state.answer ?: return null
        val inches = preferInches()
        val expression = formatExpression(state.tokens, settings)
        val answerText = settings.format(answer, inches)
        return ItemDraft(
            toolId = FootInchTool.ID,
            title = "Measurement",
            inputs = ToolInputs.encode(
                FootInchInputs(state.tokens.map(SavedToken::of), answer.stored(), inches),
            ),
            results = listOf(ResultValue.of("result", "Result", answer, answerText)),
            summary = "$expression = $answerText",
        )
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { FootInchViewModel(FootInchHistory.get(this[APPLICATION_KEY]!!)) }
        }
    }
}

/** A remembered value (memory or `Ans`) with how it was entered, so it's shown the same way back. */
data class Register(val value: Quantity, val preferInches: Boolean?)

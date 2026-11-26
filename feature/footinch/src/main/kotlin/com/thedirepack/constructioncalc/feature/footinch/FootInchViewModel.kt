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

class FootInchViewModel(val history: FootInchHistory) : ViewModel() {

    var state by mutableStateOf(CalcState())
        private set

    /** Which tape group the display belongs to; see [TapeLink]. */
    var link by mutableStateOf(TapeLink.NONE)
        private set

    /** Most recent completed answer for the `Ans` key. */
    var lastAnswer by mutableStateOf<Register?>(null)
        private set

    private var restoredItemId: String? = null

    val activeGroupId: Long? get() = link.activeGroupId

    /** What `Ans` inserts: the last answer, else the newest tape result. */
    val effectiveAns: Register?
        get() = lastAnswer ?: history.groups.value.firstOrNull()?.finalStep?.let { Register(it.result.toQuantity(), it.preferInches) }

    /** Applies a key. Returns the finished calculation when `=` succeeded. */
    fun onKey(key: Key, settings: AppSettings): CalcInput.Outcome = record(CalcInput.reduce(state, key))

    /** Applies an action from the dual-split keypad via [DualSplitKeyMapper]. */
    internal fun onDualSplitAction(action: DualSplitAction, settings: AppSettings): CalcInput.Outcome =
        record(DualSplitKeyMapper.apply(state, action))

    private fun record(outcome: CalcInput.Outcome): CalcInput.Outcome {
        if (!outcome.accepted && outcome.state == state) return outcome
        val before = state
        state = outcome.state
        link = TapeLink.afterKey(link, before, outcome.state)
        outcome.completed?.let(::commit)
        return outcome
    }

    /** Puts a finished calculation on the tape: continues the active group, corrects its last steps, or starts a new one. */
    private fun commit(calc: Calculation) {
        val groupId = link.activeGroupId?.takeIf { id -> history.groups.value.any { it.id == id } }
        val continues = calc.expression.any { it is Token.Value && it.fromPreviousAnswer }
        val added = when {
            groupId != null && link.editingLastStepCount > 0 ->
                history.replaceLastSteps(groupId, link.editingLastStepCount, calc.expression)
            groupId != null && continues -> history.appendToGroup(groupId, calc.expression)
            else -> 0
        }
        link = if (added > 0) {
            TapeLink(groupId, added, 0)
        } else {
            val group = history.startNewGroup(calc.expression)
            TapeLink(group.id, group.steps.size, 0)
        }
        lastAnswer = Register(calc.result, CalcInput.prefersInches(calc.expression))
    }

    // --- Tape editing ---

    /** Applies an edited step; returns an error message, or null when it worked. */
    fun editStep(groupId: Long, stepId: Long, newTokens: List<SavedToken>): String? =
        when (val result = history.editStep(groupId, stepId, newTokens)) {
            is TapeEditResult.Invalid -> result.message
            is TapeEditResult.Updated -> {
                result.group?.let(::syncWithGroup)
                null
            }
        }

    fun deleteStep(groupId: Long, stepId: Long): String? =
        when (val result = history.deleteStep(groupId, stepId)) {
            is TapeEditResult.Invalid -> result.message
            is TapeEditResult.Updated -> {
                if (result.group != null) {
                    syncWithGroup(result.group)
                } else if (groupId == link.activeGroupId) {
                    link = TapeLink.NONE
                    if (state.answer != null) state = CalcState()
                }
                null
            }
        }

    fun deleteGroup(groupId: Long) {
        history.removeGroup(groupId)
        if (groupId == link.activeGroupId) link = TapeLink.NONE
    }

    fun clearRecentGroups() {
        history.clearRecent()
        if (history.groups.value.none { it.id == link.activeGroupId }) link = TapeLink.NONE
    }

    fun clearAllGroups() {
        history.clear()
        link = TapeLink.NONE
    }

    /** Shows [group]'s total as the answer, so the next operator keeps adding steps to it. */
    fun continueGroup(group: CalcGroup) {
        val last = group.finalStep ?: return
        val qty = last.result.toQuantity()
        link = TapeLink(group.id, 1, 0)
        state = CalcState(tokens = CalculationTapeOps.resolvedTokensForStep(group, group.steps.lastIndex), answer = qty)
        lastAnswer = Register(qty, last.preferInches)
    }

    /** After an edit changed [group]'s results, refresh a display that shows (or builds on) its answer. */
    private fun syncWithGroup(group: CalcGroup) {
        if (group.id != link.activeGroupId) return
        val finalStep = group.finalStep ?: return
        val finalQty = finalStep.result.toQuantity()
        lastAnswer = Register(finalQty, finalStep.preferInches)
        when {
            state.answer != null -> {
                state = CalcState(CalculationTapeOps.resolvedTokensForStep(group, group.steps.lastIndex), answer = finalQty)
                link = TapeLink(group.id, 1, 0)
            }
            state.tokens.any { it is Token.Value && it.fromPreviousAnswer } -> {
                val source = if (link.editingLastStepCount > 0) {
                    group.steps.getOrNull(group.steps.lastIndex - link.editingLastStepCount)
                } else {
                    finalStep
                } ?: return
                state = CalcState(
                    state.tokens.map { tok ->
                        if (tok is Token.Value && tok.fromPreviousAnswer) {
                            Token.Value(source.result.toQuantity(), source.preferInches, fromPreviousAnswer = true)
                        } else {
                            tok
                        }
                    },
                )
            }
        }
    }

    // --- Named memory ---

    /** Saves the shown value as [name] (blank: `M1`, `M2`…). Returns false when there's nothing to save. */
    fun memorySave(name: String): Boolean {
        val current = state.result ?: return false
        history.storeMemory(current, preferInches(), name)
        return true
    }

    /** `M+` on the top memory value. Returns true if accepted. */
    fun memoryAdd(): Boolean = applyMemoryOp(Operator.ADD)

    /** `M−` on the top memory value. Returns true if accepted. */
    fun memorySubtract(): Boolean = applyMemoryOp(Operator.SUBTRACT)

    private fun applyMemoryOp(op: Operator): Boolean {
        val current = state.result ?: return false
        return when (val res = history.applyMemoryOp(op, current, preferInches())) {
            is MemoryOpResult.Success -> true
            is MemoryOpResult.Error -> {
                state = state.copy(error = res.message)
                false
            }
        }
    }

    /** Inserts [entry] into the expression and moves it to the top of memory. */
    fun memoryRecall(entry: MemoryEntry): Boolean {
        history.touchMemory(entry.id)
        return recallQuantity(entry.value.toQuantity(), entry.preferInches)
    }

    /** Inserts the most recent answer (`Ans`); right after `=` it continues the same tape group. */
    fun recallAns(): Boolean {
        val active = history.groups.value.firstOrNull { it.id == link.activeGroupId }?.finalStep
        if (active != null) return recallQuantity(active.result.toQuantity(), active.preferInches, fromPreviousAnswer = true)
        val ans = effectiveAns ?: return false
        return recallQuantity(ans.value, ans.preferInches)
    }

    /** Inserts an exact [Quantity] (from Helpers, the tape, or memory) into the current expression. */
    fun recallQuantity(quantity: Quantity, preferInches: Boolean? = null, fromPreviousAnswer: Boolean = false): Boolean =
        record(CalcInput.reduce(state, Key.Recall(quantity, preferInches, fromPreviousAnswer))).accepted

    /** Loads a saved item once (the screen may recompose many times). */
    fun restore(item: ProjectItem) {
        if (restoredItemId == item.id) return
        restoredItemId = item.id
        val inputs = ToolInputs.decode<FootInchInputs>(item.inputs) ?: return
        val tokens = inputs.tokens.map { it.toToken() }
        val answer = inputs.answer?.toQuantity()
        link = TapeLink.NONE
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

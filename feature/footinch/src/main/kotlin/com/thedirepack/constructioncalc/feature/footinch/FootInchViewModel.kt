package com.thedirepack.constructioncalc.feature.footinch

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.measure.CalcException
import com.thedirepack.constructioncalc.core.measure.Evaluator
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

    /** Stored memory register (`M+`, `M−`, `MR`, `MC`). Null when empty (a zero total clears it). */
    var memory by mutableStateOf<Register?>(null)
        private set

    /** Most recent completed answer for the `Ans` key. */
    var lastAnswer by mutableStateOf<Register?>(null)
        private set

    private var restoredItemId: String? = null

    val hasMemory: Boolean get() = memory != null

    /** What `Ans` inserts: the last answer, else the newest tape entry. */
    val effectiveAns: Register?
        get() = lastAnswer ?: history.entries.value.firstOrNull()?.let { Register(it.result.toQuantity(), it.preferInches) }

    /** Applies a key. Returns the finished calculation when `=` succeeded. */
    fun onKey(key: Key, settings: AppSettings): CalcInput.Outcome =
        recordOutcome(CalcInput.reduce(state, key), settings)

    /** Applies an action from the dual-split keypad via [DualSplitKeyMapper]. */
    internal fun onDualSplitAction(action: DualSplitAction, settings: AppSettings): CalcInput.Outcome =
        recordOutcome(DualSplitKeyMapper.apply(state, action), settings)

    private fun recordOutcome(outcome: CalcInput.Outcome, settings: AppSettings): CalcInput.Outcome {
        if (!outcome.accepted && outcome.state == state) return outcome
        state = outcome.state
        outcome.completed?.let { calc ->
            val inches = CalcInput.prefersInches(calc.expression)
            lastAnswer = Register(calc.result, inches)
            history.add(
                HistoryEntry(
                    expression = formatExpression(calc.expression, settings),
                    result = calc.result.stored(),
                    preferInches = inches,
                ),
            )
        }
        return outcome
    }

    /** Adds the current displayed value to memory (`M+`). Returns true if accepted. */
    fun memoryAdd(): Boolean = updateMemory(Operator.ADD)

    /** Subtracts the current displayed value from memory (`M−`). Returns true if accepted. */
    fun memorySubtract(): Boolean = updateMemory(Operator.SUBTRACT)

    private fun updateMemory(op: Operator): Boolean {
        val current = state.result ?: return false
        val existing = memory?.value
        val updated = if (existing == null) {
            if (op == Operator.SUBTRACT) current.copy(value = -current.value) else current
        } else {
            try {
                Evaluator.apply(op, existing, current)
            } catch (e: CalcException) {
                state = state.copy(error = e.message)
                return false
            }
        }
        memory = if (updated.value.isZero) null else Register(updated, memory?.preferInches ?: preferInches())
        return true
    }

    /** Inserts the stored memory value into the expression (`MR`). Returns true if accepted. */
    fun memoryRecall(): Boolean {
        val mem = memory ?: return false
        return recallQuantity(mem.value, mem.preferInches)
    }

    /** Clears the memory register (`MC`). Returns true if memory was non-empty. */
    fun memoryClear(): Boolean {
        if (memory == null) return false
        memory = null
        return true
    }

    /** Inserts the most recent answer (`Ans`) into the expression. Returns true if accepted. */
    fun recallAns(): Boolean {
        val ans = effectiveAns ?: return false
        return recallQuantity(ans.value, ans.preferInches)
    }

    /** Inserts an exact [Quantity] (e.g. from Helpers or Tape) into the current expression. */
    fun recallQuantity(quantity: Quantity, preferInches: Boolean? = null): Boolean {
        val outcome = CalcInput.reduce(state, Key.Recall(quantity, preferInches))
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

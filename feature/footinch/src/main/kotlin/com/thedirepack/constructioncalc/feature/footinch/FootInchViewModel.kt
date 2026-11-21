package com.thedirepack.constructioncalc.feature.footinch

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.thedirepack.constructioncalc.core.data.AppSettings
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

    private var restoredItemId: String? = null

    /** Applies a key. Returns the finished calculation when `=` succeeded. */
    fun onKey(key: Key, settings: AppSettings): CalcInput.Outcome {
        val outcome = CalcInput.reduce(state, key)
        state = outcome.state
        outcome.completed?.let { calc ->
            history.add(
                HistoryEntry(
                    expression = formatExpression(calc.expression, settings),
                    result = calc.result.stored(),
                    preferInches = CalcInput.prefersInches(calc.expression),
                ),
            )
        }
        return outcome
    }

    /** Loads a saved item once (the screen may recompose many times). */
    fun restore(item: ProjectItem) {
        if (restoredItemId == item.id) return
        restoredItemId = item.id
        val inputs = ToolInputs.decode<FootInchInputs>(item.inputs) ?: return
        val tokens = inputs.tokens.map { it.toToken() }
        state = CalcState(tokens = tokens, answer = inputs.answer?.toQuantity())
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

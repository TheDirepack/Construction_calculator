package com.thedirepack.constructioncalc.core.measure.input

import com.thedirepack.constructioncalc.core.measure.CalcException
import com.thedirepack.constructioncalc.core.measure.Evaluator
import com.thedirepack.constructioncalc.core.measure.LengthStyle
import com.thedirepack.constructioncalc.core.measure.MeasureLiteral
import com.thedirepack.constructioncalc.core.measure.Operator
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.Token

sealed interface Key {
    /** A digit or one of `.`, `'`, `"`, `/`. */
    data class Char(val char: kotlin.Char) : Key
    data class Op(val operator: Operator) : Key
    data object Equals : Key
    data object Backspace : Key
    data object Clear : Key
    /** A fraction-of-an-inch key: adds (or replaces) the fraction on the current measurement. */
    data class Fraction(val numerator: Int, val denominator: Int) : Key
    /** Insert a known value, e.g. from history. */
    data class Recall(val quantity: Quantity, val preferInches: Boolean? = null) : Key
}

/**
 * Keypad-driven calculator input, shared by the Foot-Inch tool and every measurement field in the
 * app (see `MeasureInputSheet` in :core:ui). Pure Kotlin: feed it [Key]s, get back a new [CalcState].
 *
 * Calculator input state.
 *
 * @property tokens the expression being typed, or the one that produced [answer].
 * @property answer set right after `=`; the next operator continues from it, the next digit starts over.
 * @property error message from the last failed `=`.
 */
data class CalcState(
    val tokens: List<Token> = emptyList(),
    val answer: Quantity? = null,
    val error: String? = null,
) {
    /** The answer, or a live result while typing. */
    val result: Quantity? get() = answer ?: Evaluator.preview(tokens)
}

/** A finished calculation, emitted when `=` succeeds. */
data class Calculation(val expression: List<Token>, val result: Quantity)

object CalcInput {

    data class Outcome(val state: CalcState, val completed: Calculation? = null, val accepted: Boolean = true)

    fun reduce(state: CalcState, key: Key): Outcome = when (key) {
        is Key.Char -> typeChar(state, key.char)
        is Key.Op -> typeOperator(state, key.operator)
        Key.Equals -> equals(state)
        Key.Backspace -> backspace(state)
        Key.Clear -> Outcome(CalcState())
        is Key.Fraction -> typeFraction(state, key.numerator, key.denominator)
        is Key.Recall -> recall(state, Token.Value(key.quantity, key.preferInches))
    }

    /**
     * Whether the expression is being worked in inches only: true if it has inch measurements
     * and none with feet, false if any measurement has feet, null if it has no units to go by.
     */
    fun prefersInches(tokens: List<Token>): Boolean? {
        var sawInches = false
        for (token in tokens) {
            when (token) {
                is Token.Literal -> when {
                    MeasureLiteral.FEET in token.text -> return false
                    MeasureLiteral.INCH in token.text -> sawInches = true
                }
                is Token.Value -> when (token.preferInches) {
                    false -> return false
                    true -> sawInches = true
                    null -> Unit
                }
                is Token.Op -> Unit
            }
        }
        return if (sawInches) true else null
    }

    private fun typeChar(state: CalcState, c: Char): Outcome {
        val tokens = if (state.answer != null) emptyList() else state.tokens
        val last = tokens.lastOrNull()
        val newTokens = when (last) {
            is Token.Literal -> {
                val text = last.text + c
                if (!MeasureLiteral.isValidPrefix(text)) return rejected(state)
                tokens.dropLast(1) + Token.Literal(text)
            }
            // Typing over a recalled value (or a field's current value) replaces it, like recall replaces a literal.
            is Token.Value -> {
                if (!MeasureLiteral.isValidPrefix(c.toString())) return rejected(state)
                tokens.dropLast(1) + Token.Literal(c.toString())
            }
            is Token.Op, null -> {
                if (!MeasureLiteral.isValidPrefix(c.toString())) return rejected(state)
                tokens + Token.Literal(c.toString())
            }
        }
        return Outcome(CalcState(newTokens))
    }

    private fun typeOperator(state: CalcState, op: Operator): Outcome {
        state.answer?.let {
            val carried = Token.Value(it, prefersInches(state.tokens))
            return Outcome(CalcState(listOf(carried, Token.Op(op))))
        }
        var tokens = state.tokens
        val last = tokens.lastOrNull()
        if (last is Token.Op) {
            val isMulDiv = last.operator == Operator.MULTIPLY || last.operator == Operator.DIVIDE
            if (op == Operator.SUBTRACT && isMulDiv) return Outcome(CalcState(tokens + Token.Op(op)))
            // Replace the trailing operator(s), e.g. `×−` followed by `+` becomes `+`.
            tokens = tokens.dropLast(1)
            if ((tokens.lastOrNull() as? Token.Op) != null) tokens = tokens.dropLast(1)
        }
        if (tokens.isEmpty()) {
            // Only a leading minus is meaningful at the start.
            return if (op == Operator.SUBTRACT) Outcome(CalcState(listOf(Token.Op(op)))) else rejected(state)
        }
        return Outcome(CalcState(tokens + Token.Op(op)))
    }

    private fun equals(state: CalcState): Outcome {
        if (state.answer != null) return Outcome(state, accepted = false)
        val tokens = state.tokens.dropLastWhile { it is Token.Op }
        if (tokens.isEmpty()) return rejected(state)
        return try {
            val result = Evaluator.evaluate(tokens)
            Outcome(CalcState(tokens, answer = result), completed = Calculation(tokens, result))
        } catch (e: CalcException) {
            Outcome(state.copy(error = e.message))
        }
    }

    private fun backspace(state: CalcState): Outcome {
        if (state.answer != null) return Outcome(state.copy(answer = null))
        val last = state.tokens.lastOrNull() ?: return rejected(state)
        val tokens = state.tokens.dropLast(1)
        val newTokens = if (last is Token.Literal && last.text.length > 1) {
            tokens + Token.Literal(last.text.dropLast(1))
        } else {
            tokens
        }
        return Outcome(CalcState(newTokens))
    }

    private fun typeFraction(state: CalcState, numerator: Int, denominator: Int): Outcome {
        val tokens = if (state.answer != null) emptyList() else state.tokens
        val fraction = "$numerator/$denominator${MeasureLiteral.INCH}"
        val newTokens = when (val last = tokens.lastOrNull()) {
            is Token.Value -> tokens.dropLast(1) + Token.Literal(fraction)
            is Token.Literal -> {
                val text = baseForFraction(last.text) + fraction
                if (!MeasureLiteral.isValidPrefix(text)) return rejected(state)
                tokens.dropLast(1) + Token.Literal(text)
            }
            is Token.Op, null -> tokens + Token.Literal(fraction)
        }
        return Outcome(CalcState(newTokens))
    }

    /**
     * The part of a literal a fraction key builds on: `5'3` → `5'3"`, `5'` → `5'`, `3` → `3"`.
     * An existing fraction is dropped so the new key replaces it: `5'3"1/4` → `5'3"`.
     */
    internal fun baseForFraction(text: String): String {
        val feetEnd = text.indexOf(MeasureLiteral.FEET) + 1 // 0 when there are no feet
        val feet = text.substring(0, feetEnd)
        val inchPart = text.substring(feetEnd)
        val inchMark = inchPart.indexOf(MeasureLiteral.INCH)
        val whole = if (inchMark >= 0) inchPart.substring(0, inchMark) else inchPart
        return when {
            MeasureLiteral.SLASH in whole -> feet // inches were only a fraction: replace it
            whole.isEmpty() -> feet
            else -> feet + whole + MeasureLiteral.INCH
        }
    }

    private fun recall(state: CalcState, value: Token.Value): Outcome {
        val tokens = if (state.answer != null) emptyList() else state.tokens
        val newTokens = when (tokens.lastOrNull()) {
            null, is Token.Op -> tokens + value
            is Token.Literal, is Token.Value -> tokens.dropLast(1) + value
        }
        return Outcome(CalcState(newTokens))
    }

    private fun rejected(state: CalcState) = Outcome(state.copy(error = null), accepted = false)
}

/** How lengths are shown. [AUTO] answers in inches when the measurements were entered in inches. */
enum class LengthDisplay(val label: String) {
    AUTO("Auto"),
    FEET_INCHES("ft-in"),
    INCHES("in");

    /** [preferInches] comes from [CalcInput.prefersInches]. */
    fun resolve(preferInches: Boolean?): LengthStyle = when (this) {
        AUTO -> if (preferInches == true) LengthStyle.INCHES else LengthStyle.FEET_INCHES
        FEET_INCHES -> LengthStyle.FEET_INCHES
        INCHES -> LengthStyle.INCHES
    }
}

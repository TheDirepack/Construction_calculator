package com.thedirepack.constructioncalc.core.measure

import com.thedirepack.constructioncalc.core.math.Rational

enum class Operator(val symbol: String, val precedence: Int) {
    ADD("+", 1),
    SUBTRACT("−", 1),
    MULTIPLY("×", 2),
    DIVIDE("÷", 2),
}

/** One piece of an expression as built by the keypad. */
sealed interface Token {
    /** A measurement or number being typed, in [MeasureLiteral] syntax. */
    data class Literal(val text: String) : Token

    data class Op(val operator: Operator) : Token

    /**
     * An already-computed value, e.g. the previous answer or one recalled from history.
     * [preferInches] remembers whether it came from an inches-only calculation (null = unknown).
     */
    data class Value(val quantity: Quantity, val preferInches: Boolean? = null) : Token
}

/**
 * Evaluates a token list with normal precedence (× ÷ before + −) and a leading or post-× / ÷ unary
 * minus.
 *
 * Unit rules:
 * - Adding or subtracting a plain number and a length treats the number as inches (`5' + 6` = 5' 6").
 * - Multiplying adds dimensions (ft × ft = sq ft), dividing subtracts them (sq ft ÷ ft = ft).
 * - Results beyond volume, or below a plain number, are rejected.
 */
object Evaluator {

    fun evaluate(tokens: List<Token>): Quantity {
        if (tokens.isEmpty()) throw CalcException.Incomplete()
        return Parser(tokens).run()
    }

    /** Like [evaluate] but ignores trailing operators, for a live preview while typing. */
    fun preview(tokens: List<Token>): Quantity? {
        val trimmed = tokens.dropLastWhile { it is Token.Op }
        if (trimmed.isEmpty()) return null
        return try {
            evaluate(trimmed)
        } catch (_: CalcException) {
            null
        }
    }

    fun apply(op: Operator, a: Quantity, b: Quantity): Quantity = when (op) {
        Operator.ADD -> addLike(a, b) { x, y -> x + y }
        Operator.SUBTRACT -> addLike(a, b) { x, y -> x - y }
        Operator.MULTIPLY -> {
            val dim = Dimension.ofPower(a.dimension.power + b.dimension.power)
                ?: throw CalcException.UnitMismatch("Result is beyond cubic units")
            Quantity(a.value * b.value, dim)
        }
        Operator.DIVIDE -> {
            if (b.value.isZero) throw CalcException.DivideByZero()
            val dim = Dimension.ofPower(a.dimension.power - b.dimension.power)
                ?: throw CalcException.UnitMismatch("Can't divide ${a.dimension.label} by ${b.dimension.label}")
            Quantity(a.value / b.value, dim)
        }
    }

    private inline fun addLike(a: Quantity, b: Quantity, f: (Rational, Rational) -> Rational): Quantity {
        val dim = when {
            a.dimension == b.dimension -> a.dimension
            // A bare number next to a length is read as inches.
            a.dimension == Dimension.NUMBER && b.dimension == Dimension.LENGTH -> Dimension.LENGTH
            a.dimension == Dimension.LENGTH && b.dimension == Dimension.NUMBER -> Dimension.LENGTH
            else -> throw CalcException.UnitMismatch(
                "Can't combine ${a.dimension.label} and ${b.dimension.label}",
            )
        }
        return Quantity(f(a.value, b.value), dim)
    }

    private val Dimension.label
        get() = when (this) {
            Dimension.NUMBER -> "a number"
            Dimension.LENGTH -> "length"
            Dimension.AREA -> "area"
            Dimension.VOLUME -> "volume"
        }

    private class Parser(private val tokens: List<Token>) {
        private var index = 0

        fun run(): Quantity {
            val result = expression()
            if (index < tokens.size) throw CalcException.Syntax()
            return result
        }

        private fun expression(): Quantity {
            var left = term()
            while (true) {
                val op = peekOp() ?: break
                if (op.precedence != 1) break
                index++
                left = apply(op, left, term())
            }
            return left
        }

        private fun term(): Quantity {
            var left = unary()
            while (true) {
                val op = peekOp() ?: break
                if (op.precedence != 2) break
                index++
                left = apply(op, left, unary())
            }
            return left
        }

        private fun unary(): Quantity {
            if (peekOp() == Operator.SUBTRACT) {
                index++
                val operand = unary()
                return operand.copy(value = -operand.value)
            }
            return operand()
        }

        private fun operand(): Quantity {
            val token = tokens.getOrNull(index) ?: throw CalcException.Incomplete()
            index++
            return when (token) {
                is Token.Literal -> MeasureLiteral.parse(token.text)
                is Token.Value -> token.quantity
                is Token.Op -> throw CalcException.Syntax()
            }
        }

        private fun peekOp(): Operator? = (tokens.getOrNull(index) as? Token.Op)?.operator
    }
}

/** Renders tokens for display, e.g. `5' 3 1/2" + −2"`. */
object ExpressionText {
    fun format(tokens: List<Token>, precision: Precision, style: LengthStyle): String =
        format(tokens, LengthFormat.imperial(precision, style))

    /** Recalled values are shown in [format]'s system; typed literals as typed. */
    fun format(tokens: List<Token>, format: LengthFormat): String = buildString {
        tokens.forEachIndexed { i, token ->
            val previous = tokens.getOrNull(i - 1)
            val afterUnaryMinus = previous is Token.Op && previous.operator == Operator.SUBTRACT &&
                (i == 1 || tokens[i - 2] is Token.Op)
            if (i > 0 && !afterUnaryMinus) append(' ')
            when (token) {
                is Token.Literal -> append(MeasureLiteral.display(token.text))
                is Token.Value -> append(MeasureFormat.primary(token.quantity, format))
                is Token.Op -> append(token.operator.symbol)
            }
        }
    }
}

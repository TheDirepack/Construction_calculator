package com.thedirepack.constructioncalc.core.measure

import com.thedirepack.constructioncalc.core.math.Rational
import java.math.BigInteger
import kotlin.math.sqrt

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
     * [fromPreviousAnswer] is true when carried directly from the previous finished result.
     */
    data class Value(
        val quantity: Quantity,
        val preferInches: Boolean? = null,
        val fromPreviousAnswer: Boolean = false,
    ) : Token

    /** Grouping open parenthesis `(`. */
    data object OpenParen : Token

    /** Grouping close parenthesis `)`. */
    data object CloseParen : Token

    /** Prefix unary square root `√`. */
    data object Sqrt : Token

    /** Postfix unary square `²`. */
    data object Square : Token
}

/**
 * Evaluates a token list with normal precedence (`²` and unary `−`/`√` before `×` `÷` before `+` `−`),
 * parentheses, and dimension tracking.
 *
 * Unit rules:
 * - Adding or subtracting a plain number and a length treats the number as inches (`5' + 6` = 5' 6").
 * - Multiplying adds dimensions (ft × ft = sq ft), dividing subtracts them (sq ft ÷ ft = ft).
 * - Squaring (`x²`) doubles the dimension power: NUMBER → NUMBER, LENGTH → AREA; AREA/VOLUME rejected.
 * - Square root (`√`) halves the dimension power: NUMBER → NUMBER, AREA → LENGTH; negative or odd dimension rejected.
 * - Results beyond volume, or below a plain number, are rejected.
 */
object Evaluator {

    fun evaluate(tokens: List<Token>): Quantity {
        if (tokens.isEmpty()) throw CalcException.Incomplete()
        return Parser(tokens).run()
    }

    /**
     * Like [evaluate] but ignores trailing operators/prefixes and auto-closes unmatched `(`,
     * for a live preview while typing.
     */
    fun preview(tokens: List<Token>): Quantity? {
        val trimmed = tokens.dropLastWhile { it is Token.Op || it is Token.Sqrt }
        if (trimmed.isEmpty()) return null
        val balanced = closeOpenParens(trimmed)
        return try {
            evaluate(balanced)
        } catch (_: CalcException) {
            null
        }
    }

    /** Appends closing parentheses for any unmatched `(` in [tokens]. */
    internal fun closeOpenParens(tokens: List<Token>): List<Token> {
        var depth = 0
        for (t in tokens) {
            when (t) {
                Token.OpenParen -> depth++
                Token.CloseParen -> if (depth > 0) depth--
                else -> Unit
            }
        }
        return if (depth > 0) tokens + List(depth) { Token.CloseParen } else tokens
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

    fun applySquare(q: Quantity): Quantity {
        val dim = Dimension.ofPower(q.dimension.power * 2)
            ?: throw CalcException.UnitMismatch("Can't square ${q.dimension.label}")
        return Quantity(q.value * q.value, dim)
    }

    fun applySqrt(q: Quantity): Quantity {
        if (q.value.signum < 0) {
            throw CalcException.Syntax("Can't take square root of a negative value")
        }
        if (q.dimension.power % 2 != 0) {
            throw CalcException.UnitMismatch("Can't take square root of ${q.dimension.label}")
        }
        val dim = Dimension.ofPower(q.dimension.power / 2)
            ?: throw CalcException.UnitMismatch("Can't take square root of ${q.dimension.label}")
        return Quantity(sqrtRational(q.value), dim)
    }

    /**
     * Exact rational square root when [value] is a perfect square of a rational number,
     * falling back to IEEE-754 [sqrt] converted cleanly via [Rational.of].
     */
    internal fun sqrtRational(value: Rational): Rational {
        if (value.isZero) return Rational.ZERO
        val numRoot = exactIntSqrt(value.numerator)
        val denRoot = exactIntSqrt(value.denominator)
        if (numRoot != null && denRoot != null) {
            return Rational.of(numRoot, denRoot)
        }
        return Rational.of(sqrt(value.toDouble()))
    }

    /** Returns the exact integer square root of a non-negative [BigInteger], or null if not a perfect square. */
    private fun exactIntSqrt(n: BigInteger): BigInteger? {
        if (n.signum() < 0) return null
        if (n == BigInteger.ZERO || n == BigInteger.ONE) return n
        // Integer Newton's method: works on all JVM / Android API levels.
        var x = BigInteger.ONE.shiftLeft((n.bitLength() + 1) / 2)
        while (true) {
            val next = (x + n / x).shiftRight(1)
            if (next >= x) break
            x = next
        }
        return if (x * x == n) x else null
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
            if (tokens.getOrNull(index) == Token.Sqrt) {
                index++
                return applySqrt(unary())
            }
            return postfix()
        }

        private fun postfix(): Quantity {
            var value = primary()
            while (tokens.getOrNull(index) == Token.Square) {
                index++
                value = applySquare(value)
            }
            return value
        }

        private fun primary(): Quantity {
            val token = tokens.getOrNull(index) ?: throw CalcException.Incomplete()
            index++
            return when (token) {
                is Token.Literal -> MeasureLiteral.parse(token.text)
                is Token.Value -> token.quantity
                Token.OpenParen -> {
                    val inner = expression()
                    val closing = tokens.getOrNull(index) ?: throw CalcException.Incomplete()
                    if (closing != Token.CloseParen) throw CalcException.Syntax()
                    index++
                    inner
                }
                is Token.Op, Token.CloseParen, Token.Sqrt, Token.Square -> throw CalcException.Syntax()
            }
        }

        private fun peekOp(): Operator? = (tokens.getOrNull(index) as? Token.Op)?.operator
    }
}

/** Renders tokens for display, e.g. `5' 3 1/2" + −2"` or `√(3'² + 4'²)`. */
object ExpressionText {
    fun format(tokens: List<Token>, precision: Precision, style: LengthStyle): String =
        format(tokens, LengthFormat.imperial(precision, style))

    /** Recalled values are shown in [format]'s system; typed literals as typed. */
    fun format(tokens: List<Token>, format: LengthFormat): String = buildString {
        tokens.forEachIndexed { i, token ->
            val previous = tokens.getOrNull(i - 1)
            val afterUnaryMinus = previous is Token.Op && previous.operator == Operator.SUBTRACT &&
                (i == 1 || tokens[i - 2] is Token.Op || tokens[i - 2] == Token.OpenParen || tokens[i - 2] == Token.Sqrt)
            val noSpaceBefore = i == 0 ||
                afterUnaryMinus ||
                previous == Token.OpenParen ||
                previous == Token.Sqrt ||
                token == Token.CloseParen ||
                token == Token.Square
            if (!noSpaceBefore) append(' ')
            when (token) {
                is Token.Literal -> append(MeasureLiteral.display(token.text))
                is Token.Value -> append(MeasureFormat.primary(token.quantity, format))
                is Token.Op -> append(token.operator.symbol)
                Token.OpenParen -> append('(')
                Token.CloseParen -> append(')')
                Token.Sqrt -> append('√')
                Token.Square -> append('²')
            }
        }
    }
}

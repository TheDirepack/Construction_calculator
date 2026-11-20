package com.thedirepack.constructioncalc.core.measure

import com.thedirepack.constructioncalc.core.math.Rational

/**
 * Parses a single typed measurement, the way it is entered on a construction calculator keypad.
 *
 * Characters: digits, `.`, `/` (fraction bar), `'` (feet) and `"` (inches).
 *
 * Accepted forms (spaces are not typed; the inch mark separates whole inches from a fraction):
 * - `12`         → the plain number 12
 * - `3/4`        → the plain number 0.75
 * - `5'`         → 5 feet
 * - `5'3` `5'3"` → 5 feet 3 inches
 * - `5'3"1/2`    → 5 feet 3½ inches (a trailing `"` is optional)
 * - `5'1/2`      → 5 feet ½ inch
 * - `3"1/2`      → 3½ inches
 * - `7/16"`      → 7/16 inch
 * - `2.5'`       → 2½ feet
 */
object MeasureLiteral {

    const val FEET = '\''
    const val INCH = '"'
    const val SLASH = '/'
    const val DOT = '.'

    /** Structured form of a literal, keeping the user's own numbers for display. */
    data class Parts(
        val feet: String?,
        val inches: String?,
        val fraction: String?,
        /** True when the literal carried a unit mark, making it a length rather than a number. */
        val isLength: Boolean,
    )

    fun parse(text: String): Quantity = toQuantity(parts(text))

    fun parts(text: String): Parts {
        val cursor = Cursor(text)
        if (cursor.atEnd) throw CalcException.Incomplete()

        val first = cursor.number() ?: throw CalcException.Syntax()
        var feet: String? = null
        var inches: String? = null
        var fraction: String? = null
        var isLength = true

        when {
            cursor.take(FEET) -> {
                feet = first.text
                cursor.number()?.let { next ->
                    if (next.isFraction) {
                        fraction = next.text
                        cursor.take(INCH)
                    } else {
                        inches = next.text
                        if (cursor.take(INCH)) fraction = cursor.fractionAfterInches()
                    }
                }
            }
            cursor.take(INCH) -> {
                if (first.isFraction) {
                    fraction = first.text
                } else {
                    inches = first.text
                    fraction = cursor.fractionAfterInches()
                }
            }
            else -> {
                isLength = false
                inches = first.text
            }
        }

        if (!cursor.atEnd) throw CalcException.Syntax()
        return Parts(feet, inches, fraction, isLength)
    }

    /** True if [text] is a complete literal or could still become one with more typing. */
    fun isValidPrefix(text: String): Boolean = try {
        parts(text)
        true
    } catch (_: CalcException.Incomplete) {
        true
    } catch (_: CalcException.Syntax) {
        false
    }

    fun toQuantity(parts: Parts): Quantity {
        val inches = (parts.inches?.let(::number) ?: Rational.ZERO) +
            (parts.fraction?.let(::number) ?: Rational.ZERO)
        if (!parts.isLength) return Quantity.number(inches)
        val feet = parts.feet?.let(::number) ?: Rational.ZERO
        return Quantity.inches(feet * Units.INCHES_PER_FOOT + inches)
    }

    /** Human-friendly rendering of what was typed, e.g. `5'3"1/2` → `5' 3 1/2"`. */
    fun display(text: String): String {
        val parts = runCatching { parts(text) }.getOrNull() ?: return text.replace("'", "' ").trimEnd()
        if (!parts.isLength) return parts.inches.orEmpty()
        return buildString {
            parts.feet?.let { append(it).append(FEET) }
            val inchText = listOfNotNull(parts.inches, parts.fraction).joinToString(" ")
            if (inchText.isNotEmpty()) {
                if (isNotEmpty()) append(' ')
                append(inchText).append(INCH)
            }
        }
    }

    private fun number(text: String): Rational {
        val slash = text.indexOf(SLASH)
        if (slash < 0) return Rational.parseDecimal(text)
        val denominator = Rational.parseDecimal(text.substring(slash + 1))
        if (denominator.isZero) throw CalcException.DivideByZero()
        return Rational.parseDecimal(text.substring(0, slash)) / denominator
    }

    private class NumberText(val text: String, val isFraction: Boolean)

    private class Cursor(private val text: String) {
        private var index = 0
        val atEnd get() = index >= text.length
        val peek get() = text.getOrNull(index)

        fun take(c: Char): Boolean {
            if (peek != c) return false
            index++
            return true
        }

        /** A decimal, optionally followed by `/` and another decimal. */
        fun number(): NumberText? {
            val start = index
            if (!decimal()) return null
            if (!take(SLASH)) return NumberText(text.substring(start, index), isFraction = false)
            if (!decimal()) throw CalcException.Incomplete()
            return NumberText(text.substring(start, index), isFraction = true)
        }

        /** After `3"`, an optional `1/2` (and optional closing `"`) completes the inches. */
        fun fractionAfterInches(): String? {
            if (atEnd) return null
            val next = number() ?: throw CalcException.Syntax()
            if (!next.isFraction) {
                // `3"1` may still become `3"1/2`.
                throw if (atEnd) CalcException.Incomplete() else CalcException.Syntax()
            }
            take(INCH)
            return next.text
        }

        private fun decimal(): Boolean {
            val start = index
            var dots = 0
            while (!atEnd && (text[index].isDigit() || text[index] == DOT)) {
                if (text[index] == DOT) dots++
                index++
            }
            val run = text.substring(start, index)
            if (run.isEmpty()) return false
            if (dots > 1) throw CalcException.Syntax()
            if (run == ".") throw if (atEnd) CalcException.Incomplete() else CalcException.Syntax()
            return true
        }
    }
}

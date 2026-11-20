package com.thedirepack.constructioncalc.core.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em

private val FRACTION = Regex("""(\d+)/(\d+)""")
private val NUMERATOR = SpanStyle(fontSize = 0.62.em, baselineShift = BaselineShift(0.42f))
private val DENOMINATOR = SpanStyle(fontSize = 0.62.em)

/**
 * Typesets every `n/d` in [text] as a stacked-looking fraction (³⁄₁₆), and tightens the space
 * between a whole number and its fraction.
 */
fun fractionText(text: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (match in FRACTION.findAll(text)) {
        val start = match.range.first
        var plainEnd = start
        // "3 1/2" → "3" + thin space + fraction
        val tightSpace = start >= 2 && text[start - 1] == ' ' && text[start - 2].isDigit()
        if (tightSpace) plainEnd = start - 1
        append(text.substring(cursor, plainEnd))
        if (tightSpace) append(' ')
        withStyle(NUMERATOR) { append(match.groupValues[1]) }
        append('⁄')
        withStyle(DENOMINATOR) { append(match.groupValues[2]) }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}

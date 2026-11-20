package com.thedirepack.constructioncalc.core.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em

private val FRACTION = Regex("""(\d+)/(\d+)""")
private val NUMERATOR = SpanStyle(fontSize = 0.82.em, baselineShift = BaselineShift(0.15f))
private val DENOMINATOR = SpanStyle(fontSize = 0.82.em, baselineShift = BaselineShift(-0.08f))

/**
 * Typesets the digits of a fraction while keeping the slash and the space in mixed numbers legible.
 */
fun fractionText(text: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (match in FRACTION.findAll(text)) {
        append(text.substring(cursor, match.range.first))
        withStyle(NUMERATOR) { append(match.groupValues[1]) }
        append('/')
        withStyle(DENOMINATOR) { append(match.groupValues[2]) }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}

package com.thedirepack.constructioncalc.core.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thedirepack.constructioncalc.core.measure.Operator
import com.thedirepack.constructioncalc.core.measure.Precision
import com.thedirepack.constructioncalc.core.measure.input.Key
import com.thedirepack.constructioncalc.core.ui.fractionText

/**
 * Wraps a key handler with haptic feedback: a tap for accepted keys, a "reject" buzz for
 * keys the input ignored. [handle] returns whether the key was accepted.
 */
@Composable
fun rememberKeyPress(handle: (Key) -> Boolean): (Key) -> Unit {
    val view = LocalView.current
    return remember(handle, view) {
        { key ->
            val feedback = when {
                handle(key) -> HapticFeedbackConstants.KEYBOARD_TAP
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> HapticFeedbackConstants.REJECT
                else -> HapticFeedbackConstants.LONG_PRESS
            }
            view.performHapticFeedback(feedback)
        }
    }
}

private enum class KeyKind { DIGIT, UNIT, OPERATOR, CLEAR, EQUALS }

private data class KeySpec(
    val label: String,
    val key: Key,
    val kind: KeyKind,
    val caption: String? = null,
    val description: String = label,
)

private fun digit(c: Char) = KeySpec(c.toString(), Key.Char(c), KeyKind.DIGIT)
private fun op(o: Operator, description: String) =
    KeySpec(o.symbol, Key.Op(o), KeyKind.OPERATOR, description = description)

private fun rows(equalsLabel: String, metric: Boolean) = listOf(
    listOf(
        KeySpec("AC", Key.Clear, KeyKind.CLEAR, description = "All clear"),
        if (metric) KeySpec("mm", Key.Text("mm"), KeyKind.UNIT, description = "Millimeters")
        else KeySpec("'", Key.Char('\''), KeyKind.UNIT, caption = "feet", description = "Feet"),
        if (metric) KeySpec("m", Key.Text("m"), KeyKind.UNIT, description = "Meters")
        else KeySpec("\"", Key.Char('"'), KeyKind.UNIT, caption = "inch", description = "Inches"),
        op(Operator.DIVIDE, "Divide"),
    ),
    listOf(digit('7'), digit('8'), digit('9'), op(Operator.MULTIPLY, "Multiply")),
    listOf(digit('4'), digit('5'), digit('6'), op(Operator.SUBTRACT, "Minus")),
    listOf(digit('1'), digit('2'), digit('3'), op(Operator.ADD, "Plus")),
    listOf(
        digit('0'),
        KeySpec(".", Key.Char('.'), KeyKind.DIGIT, description = "Decimal point"),
        if (metric) KeySpec("cm", Key.Text("cm"), KeyKind.UNIT, description = "Centimeters")
        else KeySpec("/", Key.Char('/'), KeyKind.UNIT, caption = "fraction", description = "Fraction bar"),
        KeySpec(equalsLabel, Key.Equals, KeyKind.EQUALS, description = if (equalsLabel == "=") "Equals" else equalsLabel),
    ),
)

/**
 * The feet-inch calculator keypad: digits, feet/inch marks, fraction bar, operators. In [metric]
 * mode the feet, inch and fraction keys become mm, m and cm (either system can still be typed:
 * the other system's marks are only off the keypad).
 * Used full-screen by Foot-Inch and inside [MeasureInputSheet] by every length field.
 *
 * @param equalsLabel label for the equals key (e.g. "Use" in an input sheet).
 */
@Composable
fun MeasureKeypad(onKey: (Key) -> Unit, modifier: Modifier = Modifier, equalsLabel: String = "=", metric: Boolean = false) {
    val gap = 8.dp
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        rows(equalsLabel, metric).forEach { row ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { spec -> CalcKey(spec, onClick = { onKey(spec.key) }, modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun CalcKey(spec: KeySpec, onClick: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (spec.kind) {
        KeyKind.DIGIT -> scheme.surfaceContainerHigh to scheme.onSurface
        KeyKind.UNIT -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        KeyKind.OPERATOR -> scheme.secondaryContainer to scheme.onSecondaryContainer
        KeyKind.CLEAR -> scheme.surfaceContainerHighest to scheme.error
        KeyKind.EQUALS -> scheme.primary to scheme.onPrimary
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = container,
        contentColor = content,
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = spec.description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                spec.label,
                fontSize = when {
                    spec.kind == KeyKind.CLEAR || spec.label.length > 1 -> 22.sp
                    spec.label == "'" || spec.label == "\"" -> 40.sp // tick marks are thin glyphs
                    else -> 30.sp
                },
                fontWeight = FontWeight.Medium,
            )
            spec.caption?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = content.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
                )
            }
        }
    }
}

/**
 * Fraction-of-an-inch keys up to [precision], laid out like the marks on a tape: the first row is
 * always the eighths (½ ¼ shaded strongest), the second the finer fractions (odd sixteenths;
 * at 1/32 and 1/64 it scrolls).
 */
@Composable
fun FractionPad(precision: Precision, onKey: (Key) -> Unit, modifier: Modifier = Modifier) {
    val max = precision.denominator
    val (coarse, fine) = remember(max) {
        (1 until max).map { n ->
            val g = gcd(n, max)
            (n / g) to (max / g)
        }.partition { (_, d) -> d <= 8 }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FractionRow(coarse, onKey)
        if (fine.isNotEmpty()) FractionRow(fine, onKey)
    }
}

@Composable
private fun FractionRow(fractions: List<Pair<Int, Int>>, onKey: (Key) -> Unit) {
    val rowModifier = Modifier.fillMaxWidth().height(44.dp)
    if (fractions.size <= 8) {
        Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            fractions.forEach { (n, d) -> FractionKey(n, d, onKey, Modifier.weight(1f)) }
        }
    } else {
        LazyRow(rowModifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(fractions) { (n, d) -> FractionKey(n, d, onKey, Modifier.width(58.dp)) }
        }
    }
}

@Composable
private fun FractionKey(n: Int, d: Int, onKey: (Key) -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    // Halves and quarters get the strongest fill, eighths a lighter one, finer marks an outline.
    val (container, border) = when {
        d <= 4 -> scheme.tertiaryContainer to null
        d == 8 -> scheme.tertiaryContainer.copy(alpha = 0.55f) to null
        else -> scheme.surfaceContainer to BorderStroke(1.dp, scheme.tertiaryContainer)
    }
    Surface(
        onClick = { onKey(Key.Fraction(n, d)) },
        shape = RoundedCornerShape(12.dp),
        color = container,
        contentColor = if (d <= 8) scheme.onTertiaryContainer else scheme.onSurface,
        border = border,
        modifier = modifier
            .fillMaxHeight()
            .semantics { contentDescription = "$n over $d inch" },
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Shrinks to fit so 15/16 never clips to "15/1" on narrow phones or at large font sizes.
            Text(
                fractionText("$n/$d"),
                fontWeight = if (d == 2) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 22.sp),
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

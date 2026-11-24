package com.thedirepack.constructioncalc.feature.footinch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thedirepack.constructioncalc.core.measure.Operator
import com.thedirepack.constructioncalc.core.measure.input.Key

private enum class DualKeyTone { FEET, INCHES, ACTION_CLEAR, ACTION_FRAC, OPERATOR, EQUALS }

private data class DualKeySpec(
    val label: String,
    val key: Key,
    val tone: DualKeyTone,
    val description: String,
    val isCompactText: Boolean = false,
)

private val FEET_ROWS: List<List<DualKeySpec>> = listOf(
    listOf(feetDigit('7'), feetDigit('8'), feetDigit('9')),
    listOf(feetDigit('4'), feetDigit('5'), feetDigit('6')),
    listOf(feetDigit('1'), feetDigit('2'), feetDigit('3')),
    listOf(
        feetDigit('0'),
        DualKeySpec(".", Key.FeetDigit('.'), DualKeyTone.FEET, "Feet decimal point"),
        DualKeySpec("AC", Key.Clear, DualKeyTone.ACTION_CLEAR, "All clear", isCompactText = true),
    ),
)

private val INCH_ROWS: List<List<DualKeySpec>> = listOf(
    listOf(inchDigit('7'), inchDigit('8'), inchDigit('9')),
    listOf(inchDigit('4'), inchDigit('5'), inchDigit('6')),
    listOf(inchDigit('1'), inchDigit('2'), inchDigit('3')),
    listOf(
        inchDigit('0'),
        DualKeySpec(".", Key.InchDigit('.'), DualKeyTone.INCHES, "Inches decimal point"),
        DualKeySpec("⌫frac", Key.ClearFraction, DualKeyTone.ACTION_FRAC, "Clear fraction", isCompactText = true),
    ),
)

private fun feetDigit(c: Char) = DualKeySpec(c.toString(), Key.FeetDigit(c), DualKeyTone.FEET, "$c feet")
private fun inchDigit(c: Char) = DualKeySpec(c.toString(), Key.InchDigit(c), DualKeyTone.INCHES, "$c inches")

private fun operatorColumn(equalsLabel: String): List<DualKeySpec> = listOf(
    DualKeySpec(Operator.DIVIDE.symbol, Key.Op(Operator.DIVIDE), DualKeyTone.OPERATOR, "Divide"),
    DualKeySpec(Operator.MULTIPLY.symbol, Key.Op(Operator.MULTIPLY), DualKeyTone.OPERATOR, "Multiply"),
    DualKeySpec(Operator.SUBTRACT.symbol, Key.Op(Operator.SUBTRACT), DualKeyTone.OPERATOR, "Minus"),
    DualKeySpec(Operator.ADD.symbol, Key.Op(Operator.ADD), DualKeyTone.OPERATOR, "Plus"),
    DualKeySpec(equalsLabel, Key.Equals, DualKeyTone.EQUALS, if (equalsLabel == "=") "Equals" else equalsLabel, isCompactText = equalsLabel.length > 1),
)

/**
 * Side-by-side Feet and Inches keypad for fast mixed imperial entry without tapping `'` or `"`.
 * Uses distinct container colors AND distinct key shapes (`10.dp` vs `22.dp` corners) so the two
 * pads remain unmistakably separate for color-blind users.
 */
@Composable
internal fun DualMeasureKeypad(
    onKey: (Key) -> Unit,
    modifier: Modifier = Modifier,
    equalsLabel: String = "=",
) {
    val feetShape = RoundedCornerShape(10.dp)
    val inchesShape = RoundedCornerShape(22.dp)
    val operatorShape = RoundedCornerShape(18.dp)
    val scheme = MaterialTheme.colorScheme

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DualPadColumn(
            header = "FEET (')",
            headerColor = scheme.primary,
            rows = FEET_ROWS,
            shape = feetShape,
            onKey = onKey,
            modifier = Modifier.weight(3f).fillMaxHeight(),
        )
        DualPadColumn(
            header = "INCHES (\")",
            headerColor = scheme.tertiary,
            rows = INCH_ROWS,
            shape = inchesShape,
            onKey = onKey,
            modifier = Modifier.weight(3f).fillMaxHeight(),
        )
        Column(
            Modifier.weight(1.1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            operatorColumn(equalsLabel).forEach { spec ->
                DualKeyButton(
                    spec = spec,
                    shape = operatorShape,
                    onClick = { onKey(spec.key) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun DualPadColumn(
    header: String,
    headerColor: Color,
    rows: List<List<DualKeySpec>>,
    shape: Shape,
    onKey: (Key) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gap = 5.dp
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        Text(
            text = header,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = headerColor,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .semantics { heading() },
        )
        rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                row.forEach { spec ->
                    DualKeyButton(
                        spec = spec,
                        shape = shape,
                        onClick = { onKey(spec.key) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun DualKeyButton(
    spec: DualKeySpec,
    shape: Shape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (spec.tone) {
        DualKeyTone.FEET -> scheme.primaryContainer to scheme.onPrimaryContainer
        DualKeyTone.INCHES -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        DualKeyTone.ACTION_CLEAR -> scheme.surfaceContainerHighest to scheme.error
        DualKeyTone.ACTION_FRAC -> scheme.surfaceContainerHighest to scheme.onSurface
        DualKeyTone.OPERATOR -> scheme.secondaryContainer to scheme.onSecondaryContainer
        DualKeyTone.EQUALS -> scheme.primary to scheme.onPrimary
    }
    Surface(
        onClick = onClick,
        shape = shape,
        color = container,
        contentColor = content,
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = spec.description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = spec.label,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                autoSize = if (spec.isCompactText) {
                    TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 16.sp)
                } else {
                    TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 26.sp)
                },
                modifier = Modifier.padding(horizontal = 3.dp),
            )
        }
    }
}

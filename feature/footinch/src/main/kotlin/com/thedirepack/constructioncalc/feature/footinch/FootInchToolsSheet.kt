package com.thedirepack.constructioncalc.feature.footinch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Decimals
import com.thedirepack.constructioncalc.core.measure.Dimension
import com.thedirepack.constructioncalc.core.measure.Geometry
import com.thedirepack.constructioncalc.core.measure.MeasureFormat
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.Spacing
import com.thedirepack.constructioncalc.core.measure.StairCalculator
import com.thedirepack.constructioncalc.core.measure.Units
import com.thedirepack.constructioncalc.core.ui.components.FormSection
import com.thedirepack.constructioncalc.core.ui.components.MeasureField
import com.thedirepack.constructioncalc.core.ui.components.NumberField
import com.thedirepack.constructioncalc.core.ui.components.SegmentedChoice
import com.thedirepack.constructioncalc.core.ui.format
import com.thedirepack.constructioncalc.core.ui.fractionText
import com.thedirepack.constructioncalc.core.ui.isMetric

private enum class HelperTab(val label: String) {
    TRIANGLE("Right Triangle"),
    STAIRS("Stairs"),
    SPACING("Layout Marks"),
}

private enum class SpacingMode(val label: String) {
    ON_CENTER("On-center"),
    EQUAL_SPACES("Equal spaces"),
}

internal data class StairHelperInput(
    val totalRise: Quantity? = null,
    val targetRiser: Quantity? = StairCalculator.DEFAULT_TARGET_RISER,
    val treadRun: Quantity? = StairCalculator.DEFAULT_TREAD_RUN,
    val riserCountOverrideText: String = "",
)

internal sealed interface StairHelperEval {
    data object Empty : StairHelperEval
    data class Invalid(val message: String) : StairHelperEval
    data class Solved(val layout: StairCalculator.StairLayout) : StairHelperEval
}

internal object StairHelperState {
    fun evaluate(input: StairHelperInput): StairHelperEval {
        val rise = input.totalRise ?: return StairHelperEval.Empty
        if (rise.dimension != Dimension.LENGTH || rise.value.signum <= 0) {
            return StairHelperEval.Invalid("Total rise must be a positive length.")
        }
        val target = input.targetRiser ?: StairCalculator.DEFAULT_TARGET_RISER
        if (target.dimension != Dimension.LENGTH || target.value.signum <= 0) {
            return StairHelperEval.Invalid("Target riser height must be a positive length.")
        }
        val tread = input.treadRun ?: StairCalculator.DEFAULT_TREAD_RUN
        if (tread.dimension != Dimension.LENGTH || tread.value.signum <= 0) {
            return StairHelperEval.Invalid("Tread run must be a positive length.")
        }
        val overrideRaw = input.riserCountOverrideText.trim()
        val overrideCount = if (overrideRaw.isEmpty()) {
            null
        } else {
            val parsed = overrideRaw.toIntOrNull()
                ?: return StairHelperEval.Invalid("Riser count override must be a whole number.")
            if (parsed !in 1..StairCalculator.MAX_RISERS) {
                return StairHelperEval.Invalid("Riser count override must be between 1 and ${StairCalculator.MAX_RISERS}.")
            }
            parsed
        }
        val layout = StairCalculator.calculate(
            totalRise = rise,
            targetRiserHeight = target,
            treadRun = tread,
            riserCountOverride = overrideCount,
        ) ?: return StairHelperEval.Invalid("Could not calculate stair layout for these inputs.")
        return StairHelperEval.Solved(layout)
    }

    fun formatSummary(layout: StairCalculator.StairLayout, settings: AppSettings): String = buildString {
        appendLine("Stair Layout")
        appendLine("Total rise: ${settings.format(layout.totalRise)}")
        appendLine("Riser count: ${layout.riserCount}")
        appendLine("Exact riser height: ${settings.format(layout.riserHeight)}")
        appendLine("Treads: ${layout.treadCount} @ ${settings.format(layout.treadRun)}")
        appendLine("Total run: ${settings.format(layout.totalRun)}")
        appendLine("Stringer diagonal: ${settings.format(layout.stringerLength)}")
        appendLine("Step diagonal: ${settings.format(layout.stepDiagonal)}")
        appendLine("Angle: ${Decimals.trim(layout.angleDegrees, 2)}°")
        layout.ircNotes.forEach { appendLine(it) }
    }.trimEnd()
}

/**
 * Compact Helpers sheet for Foot-Inch: Right-Triangle solver, Stair stringer layout, and
 * Repeating Spacing / Layout Marks. Lets the user drop any result straight into the calculator
 * or copy summaries and mark lists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FootInchToolsSheet(
    settings: AppSettings,
    onUseQuantity: (Quantity) -> Unit,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableStateOf(HelperTab.TRIANGLE) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Helpers", style = MaterialTheme.typography.titleLarge)
            SegmentedChoice(
                options = HelperTab.entries,
                selected = tab,
                onSelect = { tab = it },
                optionLabel = { it.label },
            )
            when (tab) {
                HelperTab.TRIANGLE -> RightTriangleHelper(
                    settings = settings,
                    onUseQuantity = onUseQuantity,
                    onCopy = onCopy,
                )
                HelperTab.STAIRS -> StairsHelper(
                    settings = settings,
                    onUseQuantity = onUseQuantity,
                    onCopy = onCopy,
                )
                HelperTab.SPACING -> SpacingHelper(
                    settings = settings,
                    onUseQuantity = onUseQuantity,
                    onCopy = onCopy,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun RightTriangleHelper(
    settings: AppSettings,
    onUseQuantity: (Quantity) -> Unit,
    onCopy: (String) -> Unit,
) {
    var rise by remember { mutableStateOf<Quantity?>(null) }
    var run by remember { mutableStateOf<Quantity?>(null) }
    var diagonal by remember { mutableStateOf<Quantity?>(null) }
    var angleText by remember { mutableStateOf("") }

    val angle = remember(angleText) { Decimals.parse(angleText) }
    val filledCount = listOfNotNull(rise, run, diagonal, angle).size
    val solved = remember(rise, run, diagonal, angle) {
        Geometry.solveRightTriangle(rise = rise, run = run, diagonal = diagonal, angleDegrees = angle)
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Enter any 2 values to solve.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (filledCount > 0 || angleText.isNotEmpty()) {
            TextButton(onClick = {
                rise = null
                run = null
                diagonal = null
                angleText = ""
            }) { Text("Clear") }
        }
    }

    FormSection("Inputs") {
        MeasureField("Rise", rise, { rise = it })
        MeasureField("Run", run, { run = it })
        MeasureField("Diagonal", diagonal, { diagonal = it })
        NumberField("Angle", angleText, { angleText = it }, unit = "°")
    }

    val errorHint = when {
        filledCount > 2 -> "Clear one input — enter exactly 2 values."
        filledCount == 2 && solved == null -> when {
            angle != null && (angle <= 0.0 || angle >= 90.0) -> "Angle must be between 0° and 90°."
            diagonal != null && ((rise != null && diagonal!!.value <= rise!!.value) || (run != null && diagonal!!.value <= run!!.value)) ->
                "Diagonal must be longer than rise and run."
            else -> "Values must be greater than zero."
        }
        else -> null
    }

    if (errorHint != null) {
        Text(errorHint, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }

    if (solved != null) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Solution", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                LengthResultRow("Rise", solved.rise, settings, onUseQuantity, onCopy)
                LengthResultRow("Run", solved.run, settings, onUseQuantity, onCopy)
                LengthResultRow("Diagonal", solved.diagonal, settings, onUseQuantity, onCopy)
                HorizontalDivider()
                PlainResultRow("Angle", "${Decimals.trim(solved.angleDegrees, 2)}°", onCopy)
                PlainResultRow("Complementary", "${Decimals.trim(solved.complementaryDegrees, 2)}°", onCopy)
                PlainResultRow("Pitch", "${Decimals.trim(solved.risePerFoot, 2)} : 12", onCopy)
                PlainResultRow("Slope", "${Decimals.trim(solved.slopePercent, 1)}%", onCopy)
            }
        }
    }
}

@Composable
private fun StairsHelper(
    settings: AppSettings,
    onUseQuantity: (Quantity) -> Unit,
    onCopy: (String) -> Unit,
) {
    var input by remember { mutableStateOf(StairHelperInput()) }
    val eval = remember(input) { StairHelperState.evaluate(input) }
    val hasCustomValues = input != StairHelperInput()

    val defaultRiserText = settings.format(StairCalculator.DEFAULT_TARGET_RISER)
    val defaultTreadText = settings.format(StairCalculator.DEFAULT_TREAD_RUN)
    val maxRiserNote = if (settings.isMetric) {
        "Default $defaultRiserText (IRC max ${settings.format(StairCalculator.IRC_MAX_RISER)})"
    } else {
        "Default 7-1/2\" (IRC residential max 7-3/4\")"
    }
    val minTreadNote = if (settings.isMetric) {
        "Default $defaultTreadText (IRC min ${settings.format(StairCalculator.IRC_MIN_TREAD)})"
    } else {
        "Default 10\" (IRC residential min 10\")"
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            fractionText("Enter total rise; defaults to $defaultRiserText riser and $defaultTreadText tread."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (hasCustomValues) {
            TextButton(onClick = { input = StairHelperInput() }) { Text("Reset") }
        }
    }

    FormSection("Inputs") {
        MeasureField(
            label = "Total rise",
            value = input.totalRise,
            onValueChange = { input = input.copy(totalRise = it) },
        )
        MeasureField(
            label = "Target riser height",
            value = input.targetRiser,
            onValueChange = { input = input.copy(targetRiser = it) },
            supportingText = maxRiserNote,
        )
        MeasureField(
            label = "Tread run",
            value = input.treadRun,
            onValueChange = { input = input.copy(treadRun = it) },
            supportingText = minTreadNote,
        )
        NumberField(
            label = "Riser count override (optional)",
            value = input.riserCountOverrideText,
            onValueChange = { text -> input = input.copy(riserCountOverrideText = text.filter(Char::isDigit)) },
            unit = "risers",
            supportingText = "Leave blank to solve automatically from target riser height",
        )
    }

    when (eval) {
        StairHelperEval.Empty -> Unit
        is StairHelperEval.Invalid -> {
            Text(eval.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        is StairHelperEval.Solved -> {
            val layout = eval.layout
            val summaryText = remember(layout, settings) { StairHelperState.formatSummary(layout, settings) }
            val risePerFoot = remember(layout) {
                (layout.riserHeight.value / layout.treadRun.value).toDouble() * 12.0
            }
            val twoRPlusT = remember(layout) {
                layout.riserHeight.value * Rational.of(2) + layout.treadRun.value
            }
            val twoRPlusTValue = remember(twoRPlusT, settings) {
                if (settings.isMetric) {
                    "${Decimals.trim((twoRPlusT * Units.MM_PER_INCH).toDouble(), 0)} mm (ideal 610–635 mm)"
                } else {
                    "${Decimals.trim(twoRPlusT.toDouble(), 2)}\" (ideal 24\"–25\")"
                }
            }
            val displayMarkCount = minOf(layout.treadCount, Spacing.MAX_MARKS)
            val stringerMarks = remember(layout, displayMarkCount) {
                List(displayMarkCount) { idx ->
                    Quantity.inches(layout.stepDiagonal.value * Rational.of((idx + 1).toLong()))
                }
            }
            val formattedMarks = remember(stringerMarks, settings) {
                stringerMarks.mapIndexed { idx, q -> idx + 1 to settings.format(q) }
            }
            val copyMarksText = remember(formattedMarks, layout, settings) {
                buildString {
                    appendLine("Stringer marks (${settings.format(layout.stepDiagonal)} step)")
                    formattedMarks.forEach { (num, text) -> appendLine("$num. $text") }
                }.trimEnd()
            }

            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (layout.riserCount > 1) {
                    AssistChip(
                        onClick = { input = input.copy(riserCountOverrideText = (layout.riserCount - 1).toString()) },
                        label = { Text("${layout.riserCount - 1} risers (−1)") },
                    )
                }
                if (layout.riserCount < StairCalculator.MAX_RISERS) {
                    AssistChip(
                        onClick = { input = input.copy(riserCountOverrideText = (layout.riserCount + 1).toString()) },
                        label = { Text("${layout.riserCount + 1} risers (+1)") },
                    )
                }
                if (input.riserCountOverrideText.isNotBlank()) {
                    AssistChip(
                        onClick = { input = input.copy(riserCountOverrideText = "") },
                        label = { Text("Auto risers") },
                    )
                }
            }

            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Stair Layout",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onCopy(summaryText) }) { Text("Copy all") }
                    }
                    PlainResultRow("Riser count", "${layout.riserCount}", onCopy)
                    LengthResultRow("Exact riser height", layout.riserHeight, settings, onUseQuantity, onCopy)
                    if (!settings.isMetric && MeasureFormat.isRounded(layout.riserHeight, settings.precision, settings.fractionRounding)) {
                        Text(
                            "Exact riser: ${MeasureFormat.decimal(layout.riserHeightInches, 4)}\" (${layout.riserHeightInches})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    PlainResultRow("Treads", "${layout.treadCount}", onCopy)
                    LengthResultRow("Total run", layout.totalRun, settings, onUseQuantity, onCopy)
                    LengthResultRow("Stringer diagonal", layout.stringerLength, settings, onUseQuantity, onCopy)
                    LengthResultRow("Step diagonal", layout.stepDiagonal, settings, onUseQuantity, onCopy)
                    PlainResultRow("Angle", "${Decimals.trim(layout.angleDegrees, 2)}°", onCopy)
                    PlainResultRow("Pitch", "${Decimals.trim(risePerFoot, 2)} : 12", onCopy)
                    PlainResultRow("2R + T check", twoRPlusTValue, onCopy)
                    HorizontalDivider()
                    layout.ircNotes.forEach { note ->
                        val color = if (layout.isIrcCompliant) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        }
                        val prefix = if (layout.isIrcCompliant) "✓ " else "⚠ "
                        Text(
                            fractionText(prefix + note),
                            style = MaterialTheme.typography.bodySmall,
                            color = color,
                        )
                    }
                    if (formattedMarks.isNotEmpty()) {
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Stringer layout marks (${formattedMarks.size})",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { onCopy(copyMarksText) }) { Text("Copy marks") }
                        }
                        if (layout.treadCount > Spacing.MAX_MARKS) {
                            Text(
                                "Showing first ${Spacing.MAX_MARKS} marks.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        formattedMarks.forEachIndexed { idx, (num, text) ->
                            val markQty = stringerMarks[idx]
                            LayoutMarkRow(num, text, onUse = { onUseQuantity(markQty) })
                        }
                    }
                }
            }
        }
    }
}

private fun metricSpacing(mm: Long): Quantity =
    Quantity.inches(Rational.of(mm) / Units.MM_PER_INCH)

@Composable
private fun SpacingHelper(
    settings: AppSettings,
    onUseQuantity: (Quantity) -> Unit,
    onCopy: (String) -> Unit,
) {
    var mode by remember { mutableStateOf(SpacingMode.ON_CENTER) }
    var totalSpan by remember { mutableStateOf<Quantity?>(null) }
    var onCenter by remember {
        mutableStateOf<Quantity?>(
            if (settings.isMetric) metricSpacing(400) else Quantity.inches(Rational.of(16)),
        )
    }
    var spacesText by remember { mutableStateOf("4") }
    var startOffset by remember { mutableStateOf<Quantity?>(null) }
    var endOffset by remember { mutableStateOf<Quantity?>(null) }

    val zero = remember { Quantity.inches(Rational.ZERO) }
    val layout = remember(mode, totalSpan, onCenter, spacesText, startOffset, endOffset) {
        val span = totalSpan ?: return@remember null
        val start = startOffset ?: zero
        val end = endOffset ?: zero
        when (mode) {
            SpacingMode.ON_CENTER -> onCenter?.let { oc ->
                Spacing.bySpacing(totalSpan = span, onCenter = oc, startOffset = start, endOffset = end)
            }
            SpacingMode.EQUAL_SPACES -> {
                val count = Decimals.parse(spacesText)?.toInt() ?: return@remember null
                Spacing.equalSpaces(totalSpan = span, spaces = count, startOffset = start, endOffset = end)
            }
        }
    }

    SegmentedChoice(
        options = SpacingMode.entries,
        selected = mode,
        onSelect = { mode = it },
        optionLabel = { it.label },
    )

    FormSection("Layout") {
        MeasureField("Total span", totalSpan, { totalSpan = it })
        when (mode) {
            SpacingMode.ON_CENTER -> {
                MeasureField("On-center spacing", onCenter, { onCenter = it })
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val presets = if (settings.isMetric) {
                        listOf(
                            "300 mm" to metricSpacing(300),
                            "400 mm" to metricSpacing(400),
                            "600 mm" to metricSpacing(600),
                            "1.2 m" to metricSpacing(1200),
                            "2.4 m" to metricSpacing(2400),
                            "3 m" to metricSpacing(3000),
                        )
                    } else {
                        listOf(
                            "12\"" to Quantity.inches(Rational.of(12)),
                            "16\"" to Quantity.inches(Rational.of(16)),
                            "24\"" to Quantity.inches(Rational.of(24)),
                            "4'" to Quantity.feet(Rational.of(4)),
                            "8'" to Quantity.feet(Rational.of(8)),
                            "10'" to Quantity.feet(Rational.of(10)),
                        )
                    }
                    presets.forEach { (label, qty) ->
                        AssistChip(
                            onClick = { onCenter = qty },
                            label = { Text(label) },
                        )
                    }
                }
            }
            SpacingMode.EQUAL_SPACES -> {
                NumberField("Number of spaces", spacesText, { spacesText = it })
            }
        }
        MeasureField("Start offset (optional)", startOffset, { startOffset = it })
        MeasureField("End offset (optional)", endOffset, { endOffset = it })
    }

    if (layout != null) {
        val formattedMarks = remember(layout, settings) {
            layout.marks.mapIndexed { idx, q -> idx + 1 to settings.format(q) }
        }
        val copyText = remember(formattedMarks, layout, settings) {
            buildString {
                appendLine("Spacing: ${settings.format(layout.spacing)} (${layout.markCount} marks)")
                formattedMarks.forEach { (num, text) -> appendLine("$num. $text") }
            }.trimEnd()
        }

        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${layout.markCount} marks · ${settings.format(layout.spacing)} OC",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onUseQuantity(layout.spacing) }) { Text("Use OC") }
                    TextButton(onClick = { onCopy(copyText) }) { Text("Copy all") }
                }
                if (!layout.remainder.value.isZero) {
                    Text(
                        fractionText("Remainder: ${settings.format(layout.remainder)}"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (layout.truncated) {
                    Text(
                        "Showing first ${Spacing.MAX_MARKS} marks.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                HorizontalDivider()
                formattedMarks.forEachIndexed { idx, (num, text) ->
                    val markQty = layout.marks[idx]
                    LayoutMarkRow(num, text, onUse = { onUseQuantity(markQty) })
                }
            }
        }
    }
}

@Composable
private fun LayoutMarkRow(num: Int, text: String, onUse: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Use mark $num", onClick = onUse)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "#$num",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            fractionText(text),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            "Use",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun LengthResultRow(
    label: String,
    quantity: Quantity,
    settings: AppSettings,
    onUse: (Quantity) -> Unit,
    onCopy: (String) -> Unit,
) {
    val text = settings.format(quantity)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Copy $label") { onCopy(text) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            fractionText(text),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        TextButton(onClick = { onUse(quantity) }) { Text("Use") }
    }
}

@Composable
private fun PlainResultRow(label: String, value: String, onCopy: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Copy $label") { onCopy(value) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

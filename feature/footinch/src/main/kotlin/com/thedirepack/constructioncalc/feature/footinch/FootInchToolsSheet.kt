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
import com.thedirepack.constructioncalc.core.measure.Geometry
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.Spacing
import com.thedirepack.constructioncalc.core.measure.StairTopMount
import com.thedirepack.constructioncalc.core.measure.Stairs
import com.thedirepack.constructioncalc.core.ui.components.FormSection
import com.thedirepack.constructioncalc.core.ui.components.MeasureField
import com.thedirepack.constructioncalc.core.ui.components.NumberField
import com.thedirepack.constructioncalc.core.ui.components.SegmentedChoice
import com.thedirepack.constructioncalc.core.ui.format
import com.thedirepack.constructioncalc.core.ui.fractionText
import com.thedirepack.constructioncalc.core.ui.isMetric

private enum class HelperTab(val label: String) {
    TRIANGLE("Right Triangle"),
    SPACING("Layout Marks"),
    STAIRS("Stairs"),
}

private enum class SpacingMode(val label: String) {
    ON_CENTER("On-center"),
    EQUAL_SPACES("Equal spaces"),
}

/**
 * Compact Helpers sheet for Foot-Inch: Right-Triangle solver, Repeating Spacing / Layout Marks,
 * and Stair Stringer layout. Lets the user drop any result straight into the calculator or copy
 * the full mark list.
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
                HelperTab.SPACING -> SpacingHelper(
                    settings = settings,
                    onUseQuantity = onUseQuantity,
                    onCopy = onCopy,
                )
                HelperTab.STAIRS -> StairsHelper(
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
private fun SpacingHelper(
    settings: AppSettings,
    onUseQuantity: (Quantity) -> Unit,
    onCopy: (String) -> Unit,
) {
    var mode by remember { mutableStateOf(SpacingMode.ON_CENTER) }
    var totalSpan by remember { mutableStateOf<Quantity?>(null) }
    var onCenter by remember { mutableStateOf<Quantity?>(Quantity.inches(Rational.of(16))) }
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
                    val presets = listOf(
                        "12\"" to Quantity.inches(Rational.of(12)),
                        "16\"" to Quantity.inches(Rational.of(16)),
                        "24\"" to Quantity.inches(Rational.of(24)),
                        "4'" to Quantity.feet(Rational.of(4)),
                        "8'" to Quantity.feet(Rational.of(8)),
                        "10'" to Quantity.feet(Rational.of(10)),
                    )
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
private fun StairsHelper(
    settings: AppSettings,
    onUseQuantity: (Quantity) -> Unit,
    onCopy: (String) -> Unit,
) {
    var totalRise by remember { mutableStateOf<Quantity?>(null) }
    var targetRiser by remember { mutableStateOf<Quantity?>(Quantity.inches(Rational.of(15, 2))) }
    var treadDepth by remember { mutableStateOf<Quantity?>(Quantity.inches(Rational.of(10))) }
    var riserOverrideText by remember { mutableStateOf("") }
    var mount by remember { mutableStateOf(StairTopMount.FLUSH) }

    val overrideTrimmed = riserOverrideText.trim()
    val parsedOverride = overrideTrimmed.toIntOrNull()
    val overrideInvalid = overrideTrimmed.isNotEmpty() && (parsedOverride == null || parsedOverride !in 1..Stairs.MAX_RISERS)

    val result = remember(totalRise, targetRiser, treadDepth, parsedOverride, overrideInvalid, mount) {
        if (overrideInvalid) return@remember null
        val rise = totalRise ?: return@remember null
        val target = targetRiser ?: return@remember null
        val tread = treadDepth ?: return@remember null
        Stairs.calculate(
            totalRise = rise,
            targetRiser = target,
            treadDepth = tread,
            riserCountOverride = parsedOverride,
            mount = mount,
        )
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Enter total rise to lay out risers, treads, and stringer marks.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (totalRise != null || riserOverrideText.isNotEmpty()) {
            TextButton(onClick = {
                totalRise = null
                riserOverrideText = ""
            }) { Text("Clear") }
        }
    }

    FormSection("Stair inputs") {
        MeasureField("Total rise", totalRise, { totalRise = it })
        MeasureField("Target riser height", targetRiser, { targetRiser = it })
        MeasureField("Tread depth", treadDepth, { treadDepth = it })
        NumberField(
            label = "Riser count override (optional)",
            value = riserOverrideText,
            onValueChange = { riserOverrideText = it },
            isError = overrideInvalid,
            supportingText = if (overrideInvalid) {
                "Enter a whole number 1–${Stairs.MAX_RISERS}."
            } else {
                "Leave blank to compute from target riser."
            },
        )
        SegmentedChoice(
            options = StairTopMount.entries,
            selected = mount,
            onSelect = { mount = it },
            label = "Top step mount",
            optionLabel = { it.label },
        )
    }

    if (result == null && totalRise != null && !overrideInvalid) {
        Text(
            "Total rise, target riser, and tread depth must be greater than zero (1–${Stairs.MAX_RISERS} risers).",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    if (result != null) {
        val formattedMarks = remember(result, settings) {
            result.layoutMarks.mapIndexed { idx, q -> idx + 1 to settings.format(q) }
        }
        val twoRPlusTValue = if (settings.isMetric) {
            "${Decimals.trim(result.twoRPlusTInches * 25.4, 0)} mm (ideal 610–635 mm)"
        } else {
            "${Decimals.trim(result.twoRPlusTInches, 2)}\" (ideal 24\"–25\")"
        }
        val copyMarksText = remember(formattedMarks, result, settings) {
            buildString {
                appendLine("Stringer marks (${settings.format(result.stepHypotenuse)} step)")
                formattedMarks.forEach { (num, text) -> appendLine("$num. $text") }
            }.trimEnd()
        }
        val copyAllText = remember(result, formattedMarks, settings, twoRPlusTValue, mount) {
            buildString {
                appendLine("Stair Stringer (${mount.label})")
                appendLine("Total rise: ${settings.format(result.totalRise)}")
                appendLine("Risers: ${result.riserCount} @ ${settings.format(result.riserHeight)}")
                appendLine("Treads: ${result.treadCount} @ ${settings.format(result.treadDepth)}")
                appendLine("Total run: ${settings.format(result.totalRun)}")
                appendLine("Stringer length: ${settings.format(result.stringerLength)}")
                appendLine("Step hypotenuse: ${settings.format(result.stepHypotenuse)}")
                appendLine("Angle: ${Decimals.trim(result.angleDegrees, 2)}° (${Decimals.trim(result.risePerFoot, 2)} : 12)")
                appendLine("2R + T: $twoRPlusTValue")
                if (formattedMarks.isNotEmpty()) {
                    appendLine("Layout marks:")
                    formattedMarks.forEach { (num, text) -> appendLine("$num. $text") }
                }
            }.trimEnd()
        }

        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${result.riserCount} risers · ${result.treadCount} treads",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onCopy(copyAllText) }) { Text("Copy all") }
                }
                LengthResultRow("Riser height", result.riserHeight, settings, onUseQuantity, onCopy)
                LengthResultRow("Total run", result.totalRun, settings, onUseQuantity, onCopy)
                LengthResultRow("Stringer length", result.stringerLength, settings, onUseQuantity, onCopy)
                LengthResultRow("Step hypotenuse", result.stepHypotenuse, settings, onUseQuantity, onCopy)
                HorizontalDivider()
                PlainResultRow("Tread count", "${result.treadCount}", onCopy)
                PlainResultRow("Angle", "${Decimals.trim(result.angleDegrees, 2)}°", onCopy)
                PlainResultRow("Pitch", "${Decimals.trim(result.risePerFoot, 2)} : 12", onCopy)
                PlainResultRow("2R + T check", twoRPlusTValue, onCopy)
                if (result.notices.isNotEmpty()) {
                    HorizontalDivider()
                    result.notices.forEach { notice ->
                        Text(
                            fractionText("⚠ $notice"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
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
                    formattedMarks.forEachIndexed { idx, (num, text) ->
                        val markQty = result.layoutMarks[idx]
                        LayoutMarkRow(num, text, onUse = { onUseQuantity(markQty) })
                    }
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

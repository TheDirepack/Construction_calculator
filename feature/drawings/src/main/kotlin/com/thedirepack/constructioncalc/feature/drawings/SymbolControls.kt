package com.thedirepack.constructioncalc.feature.drawings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.model.PlanDrawing
import com.thedirepack.constructioncalc.core.model.PlanMark
import com.thedirepack.constructioncalc.core.model.PlanReviewState
import com.thedirepack.constructioncalc.core.model.PlanSymbolCounts
import com.thedirepack.constructioncalc.core.model.PlanSymbolPlacement
import kotlin.math.roundToInt

/** Symbol sizes offered by the − / + buttons, in page points. */
private val SYMBOL_SIZES = listOf(6.0, 8.0, 10.0, 12.0, 16.0, 20.0, 24.0, 32.0, 40.0, 48.0, 64.0, 96.0)

internal fun nextSymbolSize(size: Double, larger: Boolean): Double =
    if (larger) SYMBOL_SIZES.firstOrNull { it > size + 0.01 } ?: size
    else SYMBOL_SIZES.lastOrNull { it < size - 0.01 } ?: size

@Composable
internal fun SymbolSizeButtons(size: Double, onSize: (Double) -> Unit) {
    TextButton(onClick = { onSize(nextSymbolSize(size, larger = false)) }, enabled = size > SYMBOL_SIZES.first()) { Text("−") }
    Text("${size.roundToInt()} pt", style = MaterialTheme.typography.labelMedium)
    TextButton(onClick = { onSize(nextSymbolSize(size, larger = true)) }, enabled = size < SYMBOL_SIZES.last()) { Text("+") }
}

/**
 * Controls for a selected symbol: review (accept / reject a detection), its device (tap to change
 * class), turn by 90°, size, and its circuit and area tags. Drag the symbol to move it, its corner
 * handle to resize and the handle above it to turn it freely.
 */
@Composable
internal fun SelectedSymbolControls(
    drawing: PlanDrawing,
    mark: PlanMark,
    placement: PlanSymbolPlacement,
    onReplace: (PlanMark) -> Unit,
    onChangeClass: () -> Unit,
    onNext: () -> Unit,
    onCircuit: () -> Unit,
    onArea: () -> Unit,
) {
    fun update(change: (PlanSymbolPlacement) -> PlanSymbolPlacement) = onReplace(mark.copy(symbol = change(placement)))
    when (placement.reviewState) {
        PlanReviewState.PENDING -> {
            placement.confidence?.let { Text("${(it * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium) }
            FilledTonalButton(onClick = { update { it.copy(reviewState = PlanReviewState.ACCEPTED) }; onNext() }) { Text("Accept") }
            TextButton(onClick = { update { it.copy(reviewState = PlanReviewState.REJECTED) }; onNext() }) {
                Text("Reject", color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = onNext) { Text("Next") }
        }
        PlanReviewState.REJECTED -> TextButton(onClick = { update { it.copy(reviewState = PlanReviewState.ACCEPTED) } }) { Text("Accept") }
        PlanReviewState.ACCEPTED -> Unit
    }
    TextButton(onClick = onChangeClass) {
        DevicePreview(placement.deviceId, drawing.customSymbols, 20.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            PlanSymbolCounts.name(drawing, placement.deviceId) + "  ▾",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.sizeIn(maxWidth = 180.dp),
        )
    }
    TextButton(onClick = { update { it.rotatedBy(90.0) } }) { Text("${placement.rotationDegrees.roundToInt()}° ↻") }
    SymbolSizeButtons(placement.size) { size -> update { it.copy(size = size) } }
    TextButton(onClick = onCircuit) { Text(mark.circuitTag.ifBlank { "Circuit" }) }
    TextButton(onClick = onArea) { Text(mark.areaTag.ifBlank { "Area" }) }
}

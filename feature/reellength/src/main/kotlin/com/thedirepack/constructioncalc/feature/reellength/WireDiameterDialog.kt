package com.thedirepack.constructioncalc.feature.reellength

import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.InsulatedConductorArea
import com.thedirepack.constructioncalc.core.electrical.InsulationType
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.ui.components.ChoiceField

/**
 * Picks a single insulated conductor and returns its approximate outside diameter from NEC
 * Chapter 9 Table 5 (the same table Conduit Fill uses), so the reel estimate doesn't need a caliper.
 */
@Composable
internal fun WireDiameterDialog(onPick: (Quantity) -> Unit, onDismiss: () -> Unit) {
    var type by remember { mutableStateOf(InsulationType.THHN) }
    var size by remember { mutableStateOf(ConductorSize.AWG_4_0) }
    val sizes = InsulatedConductorArea.sizes(type)
    val chosen = InsulatedConductorArea.nearestSize(type, size)
    val diameter = InsulatedConductorArea.diameter(type, chosen)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cable diameter from a wire size") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceField("Insulation", InsulationType.entries, type, { type = it }, optionLabel = { it.label })
                ChoiceField("Size", sizes, chosen, { size = it }, optionLabel = { it.label })
                Text(
                    diameter?.let { "Approximate outside diameter ${it} in (NEC ${CodeRule.CONDUCTOR_DIMENSIONS.cite}). For a multiconductor cable, enter its own OD instead." }
                        ?: "${CodeRule.CONDUCTOR_DIMENSIONS.cite} has no diameter for this wire.",
                )
            }
        },
        confirmButton = {
            TextButton(enabled = diameter != null, onClick = { diameter?.let { onPick(Quantity.inches(Rational.of(it))) } }) { Text("Use") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

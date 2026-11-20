package com.thedirepack.constructioncalc.feature.panels

import com.thedirepack.constructioncalc.core.ui.components.ToggleRow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.ui.components.FormSection
import com.thedirepack.constructioncalc.core.ui.components.NumberField
import com.thedirepack.constructioncalc.core.ui.formatDecimal
import com.thedirepack.constructioncalc.core.ui.toDecimalOrNull

/** The project's design assumptions (they live in the project file, not in app settings). */
@Composable
internal fun DesignTab(ctx: PanelsContext, modifier: Modifier = Modifier) {
    val design = ctx.project.design
    var ambientText by remember(ctx.project.id) { mutableStateOf(formatDecimal(design.ambientC, 1)) }
    var targetText by remember(ctx.project.id) { mutableStateOf(formatDecimal(design.voltageDropTargetPercent, 1)) }
    fun update(change: (com.thedirepack.constructioncalc.core.model.DesignDefaults) -> com.thedirepack.constructioncalc.core.model.DesignDefaults) =
        ctx.edit { p, now -> p.copy(design = change(p.design), updatedAt = now) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Every circuit starts from these assumptions unless it has its own conductor override. They are saved with the project.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FormSection("Default conductor") {
            if (ctx.readOnly) {
                Text(
                    "${design.conductor.material.label}, ${design.conductor.insulation.label}, " +
                        "insulation ${design.conductor.insulationRating.label}, terminals ${design.conductor.terminationRating.label}",
                )
            } else {
                ConductorFields(design.conductor) { spec -> update { it.copy(conductor = spec) } }
            }
        }
        FormSection("Conditions") {
            if (ctx.readOnly) {
                Text("Ambient ${formatDecimal(design.ambientC, 1)} °C · voltage-drop target ${formatDecimal(design.voltageDropTargetPercent, 1)}% · " +
                    "upsize for voltage drop: ${if (design.upsizeForVoltageDrop) "on" else "off"}")
            } else {
                NumberField(
                    label = "Default ambient temperature",
                    value = ambientText,
                    onValueChange = { text ->
                        ambientText = text
                        text.toDecimalOrNull()?.takeIf { it in -50.0..100.0 }?.let { v -> update { it.copy(ambientC = v) } }
                    },
                    unit = "°C",
                    allowNegative = true,
                    supportingText = "Runs can set their own (hot attic, rooftop).",
                )
                NumberField(
                    label = "Voltage-drop target",
                    value = targetText,
                    onValueChange = { text ->
                        targetText = text
                        text.toDecimalOrNull()?.takeIf { it > 0.0 && it <= 20.0 }?.let { v -> update { it.copy(voltageDropTargetPercent = v) } }
                    },
                    unit = "%",
                    supportingText = "NEC recommends 3% on a branch circuit, 5% overall (informational notes).",
                )
                ToggleRow(
                    "Upsize wire for voltage drop",
                    design.upsizeForVoltageDrop,
                    { v -> update { it.copy(upsizeForVoltageDrop = v) } },
                    "Steps the wire (and the ground with it) up until the target is met.",
                )
            }
        }
        Text(
            "Sizing follows NEC 2023: Table 310.16 ampacity, 310.15 ambient and conductor-count adjustment, 110.14(C) terminals, " +
                "240.4 protection, Chapter 9 Tables 1, 4, 5 and 9. It guides fieldwork and doesn't replace a code check.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

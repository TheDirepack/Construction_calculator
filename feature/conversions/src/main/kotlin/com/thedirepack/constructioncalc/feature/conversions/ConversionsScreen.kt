package com.thedirepack.constructioncalc.feature.conversions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.ui.tool.toolViewModel
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.core.model.ToolTarget
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.core.ui.components.ChoiceField
import com.thedirepack.constructioncalc.core.ui.components.FormSection
import com.thedirepack.constructioncalc.core.ui.components.MeasureField
import com.thedirepack.constructioncalc.core.ui.components.NumberField
import com.thedirepack.constructioncalc.core.ui.components.ResultCard
import com.thedirepack.constructioncalc.core.ui.components.SegmentedChoice
import com.thedirepack.constructioncalc.core.ui.components.ToolScaffold
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.toFieldText
import com.thedirepack.constructioncalc.core.ui.tool.ItemDraft
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.core.ui.tool.ToolIds
import com.thedirepack.constructioncalc.feature.conversions.domain.ConversionCategory
import com.thedirepack.constructioncalc.feature.conversions.domain.UnitTable

private val SQ_IN_PER_MM2 = Rational.of(64516, 100)

@Composable
fun ConversionsScreen(host: ToolHost) {
    val vm: ConversionsViewModel = toolViewModel(ConversionsTool.ID, host)
    val inputs = vm.inputs
    val settings = rememberAppSettings()
    val output = remember(inputs, settings) { conversionsOutput(inputs, settings) }
    val results = output.results

    LaunchedEffect(results) {
        if (results.isNotEmpty()) host.publish(results, context = output.summary)
    }

    ToolScaffold(
        title = ConversionsTool.title,
        host = host,
        onSave = if (results.isEmpty()) null else ({
            host.saveToProject(
                ItemDraft(
                    ConversionsTool.ID,
                    "${inputs.category.label} conversion",
                    ToolInputs.encode(inputs),
                    results,
                    output.summary,
                ),
            )
        }),
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ChoiceField(
                label = "Convert",
                options = ConversionCategory.entries,
                selected = inputs.category,
                onSelect = { c ->
                    vm.update { it.copy(category = c, fromUnit = UnitTable.defaultUnitId(c)) }
                },
                optionLabel = { it.label },
            )
            TextButton(onClick = { host.openTool(ToolIds.FORMULAS, target = ToolTarget.OfTopic("power-three-phase")) }) {
                Text("Amps ↔ kW ↔ kVA: open Electrical Formulas")
            }
            when (inputs.category) {
                ConversionCategory.WIRE_SIZE -> WireSizeForm(inputs, vm)
                else -> TableForm(inputs, vm)
            }
            ResultCard(results, notices = output.notices)
        }
    }
}

@Composable
private fun TableForm(inputs: ConversionsInputs, vm: ConversionsViewModel) {
    val category = inputs.category
    val units = UnitTable.unitsFor(category)
    val fromOptions = buildList {
        if (category == ConversionCategory.LENGTH) add(UnitTable.CALCULATOR_ID)
        addAll(units.map { it.id })
    }
    val from = UnitTable.unit(category, inputs.fromUnit)
    FormSection("Value") {
        ChoiceField(
            label = "From unit",
            options = fromOptions,
            selected = if (inputs.fromUnit in fromOptions) inputs.fromUnit else UnitTable.defaultUnitId(category),
            onSelect = { id -> vm.update { it.copy(fromUnit = id) } },
            optionLabel = { id ->
                if (id == UnitTable.CALCULATOR_ID) "Feet & inches (calculator)" else UnitTable.unit(category, id).let { "${it.name} (${it.symbol})" }
            },
        )
        if (inputs.usesCalculator) {
            MeasureField(
                "Length",
                inputs.length?.toQuantity(),
                { q -> vm.update { it.copy(length = q?.stored()) } },
            )
        } else {
            NumberField(
                label = "Value",
                value = inputs.value,
                onValueChange = { t -> vm.update { it.copy(value = t) } },
                unit = from.symbol,
                allowNegative = category == ConversionCategory.TEMPERATURE,
                pickKinds = if (category.kind == ValueKind.NUMBER) emptySet() else setOf(category.kind),
                onPick = { picked ->
                    picked.rational?.let { base -> vm.update { it.copy(value = from.fromBase(base).toFieldText(6)) } }
                },
            )
        }
    }
}

@Composable
private fun WireSizeForm(inputs: ConversionsInputs, vm: ConversionsViewModel) {
    FormSection("Wire") {
        SegmentedChoice(
            options = WireFrom.entries,
            selected = inputs.wireFrom,
            onSelect = { w -> vm.update { it.copy(wireFrom = w) } },
            label = "Enter",
            optionLabel = { it.label },
        )
        when (inputs.wireFrom) {
            WireFrom.SIZE -> ChoiceField(
                label = "Size",
                options = ConductorSize.entries,
                selected = ConductorSize.fromLabel(inputs.wireSize) ?: ConductorSize.AWG_12,
                onSelect = { s -> vm.update { it.copy(wireSize = s.label) } },
                optionLabel = { it.label },
            )
            WireFrom.CMIL -> NumberField(
                label = "Area",
                value = inputs.value,
                onValueChange = { t -> vm.update { it.copy(value = t) } },
                unit = "cmil",
            )
            WireFrom.MM2 -> NumberField(
                label = "Area",
                value = inputs.value,
                onValueChange = { t -> vm.update { it.copy(value = t) } },
                unit = "mm²",
                pickKinds = setOf(ValueKind.AREA),
                onPick = { picked: ResultValue ->
                    picked.rational?.let { sqIn -> vm.update { it.copy(value = (sqIn * SQ_IN_PER_MM2).toFieldText(4)) } }
                },
            )
        }
    }
}

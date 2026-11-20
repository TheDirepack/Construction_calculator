package com.thedirepack.constructioncalc.feature.conversions

import com.thedirepack.constructioncalc.core.electrical.AcPhases
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.model.ToolInputs
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
import com.thedirepack.constructioncalc.feature.conversions.domain.ConversionCategory
import com.thedirepack.constructioncalc.feature.conversions.domain.PowerTerm
import com.thedirepack.constructioncalc.feature.conversions.domain.UnitTable

private val SQ_IN_PER_MM2 = Rational.of(64516, 100)

@Composable
fun ConversionsScreen(host: ToolHost) {
    val opened = host.openedItem
    val vm: ConversionsViewModel = viewModel(key = "conversions:${opened?.id}")
    LaunchedEffect(opened) {
        opened?.takeIf { it.toolId == ConversionsTool.ID }?.let(vm::restore)
    }
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
            when (inputs.category) {
                ConversionCategory.WIRE_SIZE -> WireSizeForm(inputs, vm)
                ConversionCategory.CURRENT_POWER -> CurrentPowerForm(inputs, vm)
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

@Composable
private fun CurrentPowerForm(inputs: ConversionsInputs, vm: ConversionsViewModel) {
    FormSection("Load") {
        SegmentedChoice(
            options = AcPhases.entries,
            selected = inputs.phase,
            onSelect = { p -> vm.update { it.copy(phase = p) } },
            label = "System",
            optionLabel = { it.label },
        )
        SegmentedChoice(
            options = PowerTerm.entries,
            selected = inputs.powerFrom,
            onSelect = { t -> vm.update { it.copy(powerFrom = t, value = "") } },
            label = "Convert from",
            optionLabel = { it.label },
        )
        val kinds = when (inputs.powerFrom) {
            PowerTerm.AMPS -> setOf(ValueKind.CURRENT)
            PowerTerm.KW -> setOf(ValueKind.POWER)
            PowerTerm.KVA -> setOf(ValueKind.APPARENT_POWER)
        }
        NumberField(
            label = inputs.powerFrom.label,
            value = inputs.value,
            onValueChange = { t -> vm.update { it.copy(value = t) } },
            unit = inputs.powerFrom.unit,
            pickKinds = kinds,
            onPick = { picked ->
                picked.rational?.let { base ->
                    // Power kinds are published in W and VA; show kW and kVA.
                    val shown = if (inputs.powerFrom == PowerTerm.AMPS) base else base / Rational.of(1000)
                    vm.update { it.copy(value = shown.toFieldText(4)) }
                }
            },
        )
        NumberField(
            label = "Voltage",
            value = inputs.voltage,
            onValueChange = { t -> vm.update { it.copy(voltage = t) } },
            unit = "V",
            supportingText = if (inputs.phase == AcPhases.THREE) "Line-to-line" else null,
            pickKinds = setOf(ValueKind.VOLTAGE),
        )
        NumberField(
            label = "Power factor",
            value = inputs.powerFactor,
            onValueChange = { t -> vm.update { it.copy(powerFactor = t) } },
            supportingText = "0 to 1. Use 1 for resistive loads",
        )
    }
}

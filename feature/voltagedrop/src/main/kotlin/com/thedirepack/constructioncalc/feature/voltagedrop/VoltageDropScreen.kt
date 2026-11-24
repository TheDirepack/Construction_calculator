package com.thedirepack.constructioncalc.feature.voltagedrop

import com.thedirepack.constructioncalc.core.electrical.code.codePercent
import com.thedirepack.constructioncalc.core.electrical.code.codePercentValue
import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.model.ToolTarget
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices

import com.thedirepack.constructioncalc.core.electrical.Table9Conduit
import com.thedirepack.constructioncalc.core.electrical.AcPhases
import com.thedirepack.constructioncalc.core.electrical.ConductorResistance
import com.thedirepack.constructioncalc.core.electrical.Stranding
import com.thedirepack.constructioncalc.core.electrical.TemperatureRating
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
import com.thedirepack.constructioncalc.core.ui.tool.toolViewModel
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.measure.Measure
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.core.ui.components.ChoiceField
import com.thedirepack.constructioncalc.core.ui.components.FormSection
import com.thedirepack.constructioncalc.core.ui.components.MeasureField
import com.thedirepack.constructioncalc.core.ui.components.NumberField
import com.thedirepack.constructioncalc.core.ui.components.ResultCard
import com.thedirepack.constructioncalc.core.ui.components.SegmentedChoice
import com.thedirepack.constructioncalc.core.ui.components.ToggleRow
import com.thedirepack.constructioncalc.core.ui.components.ToolScaffold
import com.thedirepack.constructioncalc.core.ui.components.UnitField
import com.thedirepack.constructioncalc.core.ui.isMetric
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.show
import com.thedirepack.constructioncalc.core.ui.tool.ItemDraft
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.feature.voltagedrop.domain.Method
import com.thedirepack.constructioncalc.feature.voltagedrop.domain.VoltageDrop

private const val CUSTOM_VOLTAGE = 0

@Composable
fun VoltageDropScreen(host: ToolHost) {
    val vm: VoltageDropViewModel = toolViewModel(VoltageDropTool.ID, host)
    val projectsRepo = LocalAppServices.current.projects
    LaunchedEffect(host.target) {
        (host.target as? ToolTarget.OfCircuit)?.let { t ->
            vm.prefill(t) { projectsRepo.get(t.projectId)?.let { voltageDropInputsForCircuit(it, t.circuitId) } }
        }
    }
    val inputs = vm.inputs
    val settings = rememberAppSettings()
    val output = remember(inputs, settings) { voltageDropOutput(inputs, settings) }
    val results = output.results

    LaunchedEffect(results) {
        if (results.isNotEmpty()) host.publish(results, context = output.summary)
    }

    ToolScaffold(
        title = VoltageDropTool.title,
        host = host,
        onSave = if (results.isEmpty()) null else ({
            host.saveToProject(
                ItemDraft(VoltageDropTool.ID, "Voltage drop", ToolInputs.encode(inputs), results, output.summary),
            )
        }),
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FormSection("Circuit") {
                SegmentedChoice(
                    options = AcPhases.entries,
                    selected = inputs.system,
                    onSelect = { s -> vm.update { it.copy(system = s) } },
                    label = "System",
                    optionLabel = { it.label },
                )
                ChoiceField(
                    label = "Voltage",
                    options = VoltageDropInputs.VOLTAGE_PRESETS + CUSTOM_VOLTAGE,
                    selected = inputs.voltagePreset.takeIf { it in VoltageDropInputs.VOLTAGE_PRESETS } ?: CUSTOM_VOLTAGE,
                    onSelect = { v -> vm.update { it.copy(voltagePreset = v) } },
                    optionLabel = { if (it == CUSTOM_VOLTAGE) "Custom…" else "$it V" },
                )
                if (inputs.voltagePreset !in VoltageDropInputs.VOLTAGE_PRESETS) {
                    NumberField(
                        "Custom voltage",
                        inputs.customVoltage,
                        { t -> vm.update { it.copy(customVoltage = t) } },
                        unit = "V",
                        pickKinds = setOf(ValueKind.VOLTAGE),
                    )
                }
                NumberField(
                    "Load current",
                    inputs.amps,
                    { t -> vm.update { it.copy(amps = t) } },
                    unit = "A",
                    pickKinds = setOf(ValueKind.CURRENT),
                )
                MeasureField(
                    "One-way length",
                    inputs.length?.toQuantity(),
                    { q -> vm.update { it.copy(length = q?.stored()) } },
                    supportingText = "Source to load, one direction",
                )
            }

            FormSection("Conductor") {
                ChoiceField(
                    options = ConductorMaterial.entries,
                    selected = inputs.conductorMaterial,
                    onSelect = { m -> vm.update { it.copy(material = m.name) } },
                    label = "Material",
                    optionLabel = { it.label },
                )
                ChoiceField(
                    label = "Conductor size",
                    options = VoltageDrop.supportedSizes(inputs.conductorMaterial, inputs.method),
                    selected = inputs.conductorSize,
                    onSelect = { s -> vm.update { it.copy(size = s.label) } },
                    optionLabel = { it.label },
                )
                ChoiceField(
                    label = "Parallel sets per phase",
                    options = (1..VoltageDrop.MAX_SETS).toList(),
                    selected = inputs.parallelSets.coerceIn(1, VoltageDrop.MAX_SETS),
                    onSelect = { n -> vm.update { it.copy(parallelSets = n) } },
                    optionLabel = { if (it == 1) "1 (no parallel)" else "$it" },
                )
            }

            FormSection("Method") {
                ChoiceField(
                    label = "Calculation",
                    options = Method.entries,
                    selected = inputs.method,
                    onSelect = { m -> vm.update { it.copy(method = m) } },
                    optionLabel = { it.label },
                )
                if (inputs.method == Method.AC_TABLE_9) {
                    SegmentedChoice(
                        options = Table9Conduit.entries,
                        selected = inputs.conduit,
                        onSelect = { c -> vm.update { it.copy(conduit = c) } },
                        label = "Conduit",
                        optionLabel = { it.label },
                    )
                    NumberField(
                        "Power factor",
                        inputs.powerFactor,
                        { t -> vm.update { it.copy(powerFactor = t) } },
                    )
                }
                if (inputs.method == Method.DC_TABLE_8 && ConductorResistance.hasSolidOption(inputs.conductorSize)) {
                    SegmentedChoice(
                        options = Stranding.entries,
                        selected = inputs.stranding,
                        onSelect = { st -> vm.update { it.copy(stranding = st) } },
                        label = "Conductor",
                        optionLabel = { it.label },
                    )
                }
                NumberField(
                    "Target maximum drop",
                    inputs.targetPercent,
                    { t -> vm.update { it.copy(targetPercent = t) } },
                    unit = "%",
                    supportingText = "${codePercentValue(Code.book.values.voltageDropBranchPercent, spaced = false)} is the usual branch-circuit recommendation",
                )
            }

            FormSection("Ampacity check") {
                ToggleRow(
                    title = "Check NEC ampacity",
                    checked = inputs.checkAmpacity,
                    onCheckedChange = { checked -> vm.update { it.copy(checkAmpacity = checked) } },
                    detail = "Compare voltage drop against ampacity, derating, and OCPD",
                )
                if (inputs.checkAmpacity) {
                    SegmentedChoice(
                        options = TemperatureRating.entries,
                        selected = inputs.insulation,
                        onSelect = { rating -> vm.update { it.copy(insulation = rating) } },
                        label = "Insulation (${CodeRule.AMPACITY_TABLE.cite})",
                        optionLabel = { it.label },
                    )
                    SegmentedChoice(
                        options = listOf(TemperatureRating.C60, TemperatureRating.C75),
                        selected = inputs.termination,
                        onSelect = { rating -> vm.update { it.copy(termination = rating) } },
                        label = "Termination (${CodeRule.TERMINATION_TEMPERATURE.cite})",
                        optionLabel = { it.label },
                    )
                    ToggleRow(
                        title = "Continuous load (3+ hours)",
                        checked = inputs.continuousLoad,
                        onCheckedChange = { cont -> vm.update { it.copy(continuousLoad = cont) } },
                        detail = "Terminals sized at ${codePercent(Code.book.values.continuousLoadFactor, spaced = false)} (${CodeRule.BRANCH_CONDUCTOR_CONTINUOUS.cite})",
                    )
                    val basis = Code.book.values.ambientBasisC
                    UnitField(
                        label = "Ambient temperature",
                        measure = Measure.TEMPERATURE,
                        value = inputs.ambientC,
                        onValueChange = { t -> vm.update { it.copy(ambientC = t) } },
                        supportingText = "NEC tables are based on ${settings.show(Measure.TEMPERATURE, basis, 0)} (${Measure.TEMPERATURE.format(basis, if (settings.isMetric) UnitSystem.IMPERIAL else UnitSystem.METRIC, 0)})",
                        pickKinds = setOf(ValueKind.TEMPERATURE),
                    )
                    NumberField(
                        label = "Current-carrying conductors",
                        value = inputs.currentCarrying,
                        onValueChange = { t -> vm.update { it.copy(currentCarrying = t) } },
                        supportingText = "Conductors in raceway or cable (${CodeRule.CONDUCTOR_COUNT_ADJUSTMENT.cite})",
                    )
                    NumberField(
                        label = "Breaker / fuse (optional)",
                        value = inputs.ocpdAmps,
                        onValueChange = { t -> vm.update { it.copy(ocpdAmps = t) } },
                        unit = "A",
                        supportingText = "Leave blank to skip ${CodeRule.CONDUCTOR_PROTECTION.cite} / ${CodeRule.SMALL_CONDUCTORS.cite} check",
                    )
                }
            }

            ResultCard(results, notices = output.notices)
        }
    }
}

package com.thedirepack.constructioncalc.feature.voltagedrop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.electrical.AcPhases
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConductorResistance
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.Stranding
import com.thedirepack.constructioncalc.core.electrical.Table9Conduit
import com.thedirepack.constructioncalc.core.electrical.TemperatureRating
import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.electrical.code.codeNumber
import com.thedirepack.constructioncalc.core.electrical.code.codePercent
import com.thedirepack.constructioncalc.core.electrical.code.codePercentValue
import com.thedirepack.constructioncalc.core.measure.Decimals
import com.thedirepack.constructioncalc.core.measure.Measure
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.core.model.ToolTarget
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.core.ui.components.AdvancedSection
import com.thedirepack.constructioncalc.core.ui.components.ChoiceField
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
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.core.ui.tool.toolViewModel
import com.thedirepack.constructioncalc.feature.voltagedrop.domain.Method
import com.thedirepack.constructioncalc.feature.voltagedrop.domain.VoltageDrop
import kotlin.math.abs

private const val CUSTOM_VOLTAGE = 0
private val QUICK_MATERIALS = listOf(ConductorMaterial.COPPER, ConductorMaterial.ALUMINUM)

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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PrimaryInputsCard(inputs = inputs, onUpdate = vm::update)

            ResultCard(results, notices = output.notices)

            // NEC 310.10(G): parallel sets are only offered in the quick flow when sized/selected wire >= 1/0 AWG.
            AnimatedVisibility(visible = output.canParallel) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        SegmentedChoice(
                            options = (1..VoltageDrop.MAX_SETS).toList(),
                            selected = inputs.parallelSets.coerceIn(1, VoltageDrop.MAX_SETS),
                            onSelect = { n -> vm.update { it.copy(parallelSets = n) } },
                            label = "Parallel sets per phase (${CodeRule.PARALLEL_CONDUCTORS.cite})",
                            optionLabel = { if (it == 1) "1" else "$it×" },
                        )
                    }
                }
            }

            AdvancedOptionsSection(
                inputs = inputs,
                output = output,
                settings = settings,
                hasPrefillSource = host.target is ToolTarget.OfCircuit || host.openedItem != null,
                onUpdate = vm::update,
            )
        }
    }
}

@Composable
private fun PrimaryInputsCard(
    inputs: VoltageDropInputs,
    onUpdate: ((VoltageDropInputs) -> VoltageDropInputs) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SegmentedChoice(
                    options = QUICK_MATERIALS,
                    selected = inputs.conductorMaterial,
                    onSelect = { m -> onUpdate { it.copy(material = m.name) } },
                    modifier = Modifier.weight(1f),
                    label = "Material",
                    optionLabel = { it.shortLabel },
                )
                SegmentedChoice(
                    options = AcPhases.entries,
                    selected = inputs.system,
                    onSelect = { s -> onUpdate { it.copy(system = s) } },
                    modifier = Modifier.weight(1f),
                    label = "System",
                    optionLabel = { if (it == AcPhases.SINGLE) "1φ" else "3φ" },
                )
            }
            ChoiceField(
                label = "Voltage",
                options = VoltageDropInputs.VOLTAGE_PRESETS + CUSTOM_VOLTAGE,
                selected = inputs.voltagePreset.takeIf { it in VoltageDropInputs.VOLTAGE_PRESETS } ?: CUSTOM_VOLTAGE,
                onSelect = { v -> onUpdate { it.copy(voltagePreset = v) } },
                optionLabel = { if (it == CUSTOM_VOLTAGE) "Custom…" else "$it V" },
            )
            if (inputs.voltagePreset !in VoltageDropInputs.VOLTAGE_PRESETS) {
                NumberField(
                    label = "Custom voltage",
                    value = inputs.customVoltage,
                    onValueChange = { t -> onUpdate { it.copy(customVoltage = t) } },
                    unit = "V",
                    pickKinds = setOf(ValueKind.VOLTAGE),
                )
            }
            NumberField(
                label = "Load current",
                value = inputs.amps,
                onValueChange = { t -> onUpdate { it.copy(amps = t) } },
                unit = "A",
                pickKinds = setOf(ValueKind.CURRENT),
            )
            MeasureField(
                label = "One-way length",
                value = inputs.length?.toQuantity(),
                onValueChange = { q -> onUpdate { it.copy(length = q?.stored()) } },
            )
        }
    }
}

@Composable
private fun AdvancedOptionsSection(
    inputs: VoltageDropInputs,
    output: VoltageDropOutput,
    settings: AppSettings,
    hasPrefillSource: Boolean,
    onUpdate: ((VoltageDropInputs) -> VoltageDropInputs) -> Unit,
) {
    val supportedSizes = remember(inputs.conductorMaterial, inputs.method) {
        VoltageDrop.supportedSizes(inputs.conductorMaterial, inputs.method)
    }
    val codeTarget = Code.book.values.voltageDropBranchPercent
    val codeTargetText = codeNumber(codeTarget)
    val isCodeTarget = Decimals.parse(inputs.targetPercent)?.let { abs(it - codeTarget) < 1e-6 } == true

    // Auto-expand when prefilled from a project circuit or restored item with ampacity active;
    // keep collapsed by default in quick drop-only entry.
    var autoExpanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(inputs.checkAmpacity, hasPrefillSource) {
        if (inputs.checkAmpacity && hasPrefillSource) {
            autoExpanded = true
        }
    }

    key(autoExpanded) {
        AdvancedSection(
            title = "Advanced options",
            summary = advancedSummary(inputs),
            initiallyExpanded = autoExpanded || inputs.checkAmpacity,
        ) {
            ChoiceField<ConductorSize?>(
                label = "Manual wire size",
                options = listOf<ConductorSize?>(null) + supportedSizes,
                selected = inputs.manualConductorSize,
                onSelect = { s -> onUpdate { it.copy(manualSize = s?.label, size = s?.label ?: it.size) } },
                optionLabel = { it?.label ?: "Auto-size (minimum compliant)" },
            )
            if (inputs.manualSize != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onUpdate { it.copy(manualSize = null) } }) {
                        Text("Auto-size")
                    }
                }
            }

            ChoiceField(
                label = "Parallel sets per phase",
                options = (1..VoltageDrop.MAX_SETS).toList(),
                selected = inputs.parallelSets.coerceIn(1, VoltageDrop.MAX_SETS),
                onSelect = { n -> onUpdate { it.copy(parallelSets = n) } },
                optionLabel = { if (it == 1) "1 (no parallel)" else "$it sets" },
                supportingText = if (!output.canParallel && !output.hasParallelViolation) {
                    "NEC ${CodeRule.PARALLEL_CONDUCTORS.cite} requires ${VoltageDrop.minParallelSize.label} or larger"
                } else {
                    null
                },
            )
            if (output.hasParallelViolation) {
                Text(
                    "⚠ NEC ${CodeRule.PARALLEL_CONDUCTORS.cite}: parallel conductors smaller than ${VoltageDrop.minParallelSize.label} are not permitted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            SegmentedChoice(
                options = Method.entries,
                selected = inputs.method,
                onSelect = { m -> onUpdate { it.copy(method = m) } },
                label = "Calculation method",
                optionLabel = { it.shortLabel },
            )

            if (inputs.method == Method.AC_TABLE_9) {
                SegmentedChoice(
                    options = Table9Conduit.entries,
                    selected = inputs.conduit,
                    onSelect = { c -> onUpdate { it.copy(conduit = c) } },
                    label = "Conduit material",
                    optionLabel = { it.label },
                )
                NumberField(
                    label = "Power factor",
                    value = inputs.powerFactor,
                    onValueChange = { t -> onUpdate { it.copy(powerFactor = t) } },
                    supportingText = "Default ${codeNumber(Code.book.values.acImpedanceTablePowerFactor)} lagging (NEC ${CodeRule.AC_IMPEDANCE.cite})",
                )
            } else if (inputs.isAutoSizing || ConductorResistance.hasSolidOption(inputs.conductorSize)) {
                SegmentedChoice(
                    options = Stranding.entries,
                    selected = inputs.stranding,
                    onSelect = { st -> onUpdate { it.copy(stranding = st) } },
                    label = "Conductor stranding",
                    optionLabel = { it.label },
                )
            }

            NumberField(
                label = "Target maximum drop",
                value = inputs.targetPercent,
                onValueChange = { t -> onUpdate { it.copy(targetPercent = t) } },
                unit = "%",
                supportingText = "${codePercentValue(codeTarget, spaced = false)} branch / ${codePercentValue(Code.book.values.voltageDropTotalPercent, spaced = false)} total NEC recommendation",
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = { onUpdate { it.copy(targetPercent = codeTargetText) } },
                    enabled = !isCodeTarget,
                ) {
                    Text("Reset to Code (${codePercentValue(codeTarget, spaced = false)})")
                }
            }

            ChoiceField(
                label = "Conductor alloy",
                options = ConductorMaterial.entries,
                selected = inputs.conductorMaterial,
                onSelect = { m -> onUpdate { it.copy(material = m.name) } },
                optionLabel = { it.label },
            )

            AmpacityCheckControls(inputs = inputs, settings = settings, onUpdate = onUpdate)
        }
    }
}

@Composable
private fun AmpacityCheckControls(
    inputs: VoltageDropInputs,
    settings: AppSettings,
    onUpdate: ((VoltageDropInputs) -> VoltageDropInputs) -> Unit,
) {
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    ToggleRow(
        title = "Check NEC ampacity & OCPD",
        checked = inputs.checkAmpacity,
        onCheckedChange = { checked -> onUpdate { it.copy(checkAmpacity = checked) } },
        detail = "Compare voltage drop against ampacity, derating, and OCPD",
    )
    if (inputs.checkAmpacity) {
        SegmentedChoice(
            options = TemperatureRating.entries,
            selected = inputs.insulation,
            onSelect = { rating -> onUpdate { it.copy(insulation = rating) } },
            label = "Insulation (${CodeRule.AMPACITY_TABLE.cite})",
            optionLabel = { it.label },
        )
        SegmentedChoice(
            options = listOf(TemperatureRating.C60, TemperatureRating.C75),
            selected = inputs.termination,
            onSelect = { rating -> onUpdate { it.copy(termination = rating) } },
            label = "Termination (${CodeRule.TERMINATION_TEMPERATURE.cite})",
            optionLabel = { it.label },
        )
        ToggleRow(
            title = "Continuous load (3+ hours)",
            checked = inputs.continuousLoad,
            onCheckedChange = { cont -> onUpdate { it.copy(continuousLoad = cont) } },
            detail = "Terminals sized at ${codePercent(Code.book.values.continuousLoadFactor, spaced = false)} (${CodeRule.BRANCH_CONDUCTOR_CONTINUOUS.cite})",
        )
        val basis = Code.book.values.ambientBasisC
        UnitField(
            label = "Ambient temperature",
            measure = Measure.TEMPERATURE,
            value = inputs.ambientC,
            onValueChange = { t -> onUpdate { it.copy(ambientC = t) } },
            supportingText = "NEC tables are based on ${settings.show(Measure.TEMPERATURE, basis, 0)} (${Measure.TEMPERATURE.format(basis, if (settings.isMetric) UnitSystem.IMPERIAL else UnitSystem.METRIC, 0)})",
            pickKinds = setOf(ValueKind.TEMPERATURE),
        )
        NumberField(
            label = "Current-carrying conductors",
            value = inputs.currentCarrying,
            onValueChange = { t -> onUpdate { it.copy(currentCarrying = t) } },
            supportingText = "Conductors in raceway or cable (${CodeRule.CONDUCTOR_COUNT_ADJUSTMENT.cite})",
        )
        NumberField(
            label = "Breaker / fuse (optional)",
            value = inputs.ocpdAmps,
            onValueChange = { t -> onUpdate { it.copy(ocpdAmps = t) } },
            unit = "A",
            supportingText = "Leave blank to skip ${CodeRule.CONDUCTOR_PROTECTION.cite} / ${CodeRule.SMALL_CONDUCTORS.cite} check",
        )
    }
}

package com.thedirepack.constructioncalc.feature.conduitfill

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.electrical.AmbientCorrection
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.ConduitFill
import com.thedirepack.constructioncalc.core.electrical.ConduitSize
import com.thedirepack.constructioncalc.core.electrical.ConduitTable
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.DeratingResult
import com.thedirepack.constructioncalc.core.electrical.InsulationType
import com.thedirepack.constructioncalc.core.electrical.TemperatureRating
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.electrical.code.codeLength
import com.thedirepack.constructioncalc.core.electrical.code.codePercentValue
import com.thedirepack.constructioncalc.core.measure.Decimals
import com.thedirepack.constructioncalc.core.measure.Measure
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.core.model.ToolTarget
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.components.AdvancedSection
import com.thedirepack.constructioncalc.core.ui.components.ChoiceField
import com.thedirepack.constructioncalc.core.ui.components.FormSection
import com.thedirepack.constructioncalc.core.ui.components.NamedCableEditorDialog
import com.thedirepack.constructioncalc.core.ui.components.NamedCablePickerDialog
import com.thedirepack.constructioncalc.core.ui.components.Notice
import com.thedirepack.constructioncalc.core.ui.components.NoticeLevel
import com.thedirepack.constructioncalc.core.ui.components.ResultCard
import com.thedirepack.constructioncalc.core.ui.components.SegmentedChoice
import com.thedirepack.constructioncalc.core.ui.components.ToggleRow
import com.thedirepack.constructioncalc.core.ui.components.ToolScaffold
import com.thedirepack.constructioncalc.core.ui.components.UnitField
import com.thedirepack.constructioncalc.core.ui.components.rememberNamedCables
import com.thedirepack.constructioncalc.core.ui.conductorSizeLabel
import com.thedirepack.constructioncalc.core.ui.fractionText
import com.thedirepack.constructioncalc.core.ui.isMetric
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.show
import com.thedirepack.constructioncalc.core.ui.tool.ItemDraft
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.core.ui.tool.toolViewModel
import com.thedirepack.constructioncalc.core.ui.tradeSizeLabel
import com.thedirepack.constructioncalc.feature.conduitfill.domain.CircuitPreset
import com.thedirepack.constructioncalc.feature.conduitfill.domain.ConductorRole
import com.thedirepack.constructioncalc.feature.conduitfill.domain.ConduitFillReport
import com.thedirepack.constructioncalc.feature.conduitfill.domain.FillRefs
import com.thedirepack.constructioncalc.feature.conduitfill.domain.NeutralGuidanceResult
import com.thedirepack.constructioncalc.feature.conduitfill.domain.ReportSeverity
import com.thedirepack.constructioncalc.feature.conduitfill.domain.ThermalAdjustmentKind
import com.thedirepack.constructioncalc.feature.conduitfill.domain.ThermalStatus
import com.thedirepack.constructioncalc.feature.conduitfill.domain.VoltageSystem
import com.thedirepack.constructioncalc.feature.conduitfill.domain.hasNonDefaultThermals
import com.thedirepack.constructioncalc.feature.conduitfill.domain.noticeTexts
import com.thedirepack.constructioncalc.feature.conduitfill.domain.thermalStatus
import com.thedirepack.constructioncalc.feature.conduitfill.domain.toResults

@Composable
fun ConduitFillScreen(host: ToolHost) {
    val vm: ConduitFillViewModel = toolViewModel(ConduitFillTool.ID, host)
    val projectsRepo = LocalAppServices.current.projects
    LaunchedEffect(host.target) {
        (host.target as? ToolTarget.OfRun)?.let { t ->
            vm.prefill(t) { projectsRepo.get(t.projectId)?.let { conduitFillInputsForRun(it, t.runId) } }
        }
    }

    val settings = rememberAppSettings()
    val inputs = vm.inputs
    val parsed = remember(inputs) { inputs.toRequest() }
    val report = remember(parsed, settings) { parsed.request?.let { ConduitFillReport.build(it, settings) } }
    val derating = remember(inputs, settings.unitSystem) { inputs.derating(settings.unitSystem) }
    val thermalStatus = remember(derating, settings.unitSystem) { derating?.thermalStatus(settings.unitSystem) }
    val neutralGuidance = remember(
        inputs.voltageSystem,
        inputs.circuitCount,
        inputs.sharedMwbc,
        inputs.nonlinearHarmonic,
        inputs.threePhaseCircuits,
    ) { inputs.neutralGuidance() }
    val deratingResults = remember(derating) { derating?.toResults().orEmpty() }
    val results = report?.results.orEmpty() + deratingResults

    var showFitSheet by remember { mutableStateOf(false) }

    LaunchedEffect(results) {
        if (report != null) host.publish(results, context = report.summary)
    }

    ToolScaffold(
        title = ConduitFillTool.title,
        host = host,
        onSave = report?.let { r ->
            {
                val conduit = parsed.request!!.conduit
                host.saveToProject(
                    ItemDraft(
                        toolId = ConduitFillTool.ID,
                        title = "Conduit fill: ${ConduitFillReport.conduitName(conduit, settings)}",
                        inputs = ToolInputs.encode(inputs),
                        results = results,
                        summary = r.summary,
                    ),
                )
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TopSummaryCard(
                inputs = inputs,
                parsed = parsed,
                report = report,
                neutralGuidance = neutralGuidance,
                thermalStatus = thermalStatus,
                settings = settings,
                onApplyMinimum = { min -> vm.setTradeSize(min.tradeSize) },
            )

            ConduitSection(
                inputs = inputs,
                report = report,
                vm = vm,
                settings = settings,
                onOpenFitSheet = { showFitSheet = true },
            )

            VoltageAndNeutralSection(
                inputs = inputs,
                guidance = neutralGuidance,
                vm = vm,
            )

            ConductorsSection(
                inputs = inputs,
                vm = vm,
                settings = settings,
            )

            ThermalsAdvancedSection(
                inputs = inputs,
                derating = derating,
                thermalStatus = thermalStatus,
                deratingResults = deratingResults,
                vm = vm,
                settings = settings,
                onOpenReference = { host.openReference(it) },
            )

            val notices = if (parsed.problems.isNotEmpty()) {
                parsed.problems.map { Notice(it, NoticeLevel.ERROR) }
            } else {
                report?.notices.orEmpty().map { Notice(it.text, it.severity.toLevel(), it.refId) }
            }
            ResultCard(
                results = report?.results.orEmpty(),
                notices = notices,
                onOpenReference = { host.openReference(it) },
                emptyText = if (parsed.problems.isEmpty()) "Enter a count above 0 for at least one conductor to see the fill." else "Fix the inputs above to see the fill.",
            )
        }
    }

    if (showFitSheet) {
        HowManyFitBottomSheet(
            inputs = inputs,
            report = report,
            vm = vm,
            settings = settings,
            onDismiss = { showFitSheet = false },
        )
    }
}

@Composable
private fun TopSummaryCard(
    inputs: ConduitFillInputs,
    parsed: ParsedFill,
    report: ConduitFillReport?,
    neutralGuidance: NeutralGuidanceResult,
    thermalStatus: ThermalStatus?,
    settings: AppSettings,
    onApplyMinimum: (ConduitSize) -> Unit,
) {
    val overLimit = report != null && !report.analysis.withinLimit
    val hasProblems = parsed.problems.isNotEmpty()
    val thermalFlagged = inputs.hasNonDefaultThermals(null) ||
        (thermalStatus != null && thermalStatus.kind != ThermalAdjustmentKind.NOMINAL)

    val containerColor = when {
        overLimit || hasProblems -> MaterialTheme.colorScheme.errorContainer
        thermalFlagged && thermalStatus?.kind == ThermalAdjustmentKind.RATING_INCREASE ->
            MaterialTheme.colorScheme.primaryContainer
        thermalFlagged || neutralGuidance.handleTieNote != null ->
            MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }

    val type = ConduitType.fromCode(inputs.conduitType) ?: ConduitType.EMT
    val selectedConduit = TradeSize.fromLabel(inputs.tradeSize)?.let { ConduitTable.find(type, it) }
    val selectedName = selectedConduit?.let { ConduitFillReport.conduitName(it, settings) } ?: "${inputs.tradeSize}\" ${type.displayName}"
    val minConduit = report?.minimumConduit
    val minText = when {
        report == null -> "Add conductors"
        minConduit != null -> ConduitFillReport.conduitName(minConduit, settings)
        else -> "None fits (${type.displayName})"
    }
    val fillText = when {
        hasProblems -> "Input error"
        report == null -> "0% / ${ConduitFill.limitPercent(0, inputs.nipple)}%"
        else -> buildString {
            append("${Decimals.fixed(report.analysis.percentFill, 1)}% / ${report.analysis.limitPercent}%")
            append(if (report.analysis.withinLimit) " · PASS" else " · OVER")
            if (inputs.nipple) append(" (nipple)")
        }
    }
    val thermalText = when {
        thermalStatus == null -> "Invalid ambient temperature"
        else -> thermalStatus.compactLabel
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryStat(
                    label = "Auto min conduit",
                    value = minText,
                    modifier = Modifier.weight(1f),
                    highlight = minConduit == null && report != null,
                )
                SummaryStat(
                    label = "Fill ($selectedName)",
                    value = fillText,
                    modifier = Modifier.weight(1f),
                    highlight = overLimit || hasProblems,
                )
            }
            if (minConduit != null && selectedConduit != null && minConduit.tradeSize != selectedConduit.tradeSize) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (overLimit) "Selected conduit is over Table 1 limit." else "Smaller trade size fits: $minText.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (overLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { onApplyMinimum(minConduit) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text(fractionText("Use $minText"))
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            SummaryLine(label = "Neutrals", value = neutralGuidance.headline)
            SummaryLine(
                label = "CCC & thermals",
                value = thermalText,
                isAlert = thermalStatus == null,
            )
        }
    }
}

@Composable
private fun SummaryStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            fractionText(value),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (highlight) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SummaryLine(label: String, value: String, isAlert: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Text(
            "$label:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 92.dp),
        )
        Text(
            fractionText(value),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = if (isAlert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ConduitSection(
    inputs: ConduitFillInputs,
    report: ConduitFillReport?,
    vm: ConduitFillViewModel,
    settings: AppSettings,
    onOpenFitSheet: () -> Unit,
) {
    val type = ConduitType.fromCode(inputs.conduitType) ?: ConduitType.EMT
    val sizes = ConduitTable.sizes(type)
    val selected = TradeSize.fromLabel(inputs.tradeSize)?.let { ConduitTable.find(type, it) } ?: ConduitTable.nearest(type, TradeSize.S_3_4)

    FormSection("Conduit") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceField(
                label = "Type",
                options = ConduitType.entries,
                selected = type,
                onSelect = vm::setConduitType,
                optionLabel = { it.displayName },
                modifier = Modifier.weight(0.46f),
            )
            ChoiceField(
                label = "Trade size",
                options = sizes,
                selected = selected,
                onSelect = { vm.setTradeSize(it.tradeSize) },
                optionLabel = { settings.tradeSizeLabel(it.tradeSize) },
                modifier = Modifier.weight(0.54f),
            )
        }
        Text(
            "${type.fullName} (${type.article}) · ID ${settings.show(Measure.SMALL_LENGTH, selected.internalDiameterIn)} · " +
                "100% area ${settings.show(Measure.SMALL_AREA, selected.area100SqIn, if (settings.isMetric) 0 else 3)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ToggleRow(
            title = "Nipple, ${Code.book.values.let { codeLength(it.nippleMaxLengthIn, it.nippleMaxLengthMm, settings.unitSystem) }} or shorter",
            detail = "Between boxes: ${codePercentValue(Code.book.values.fillNipplePercent, spaced = false)} fill allowed; count derating waived (${CodeRule.FILL_NIPPLES.cite})",
            checked = inputs.nipple,
            onCheckedChange = vm::setNipple,
        )
        val fitSize = ConductorSize.fromLabel(inputs.fitSize) ?: ConductorSize.AWG_12
        val fitInsulLabel = insulationLabel(inputs.fitInsulation)
        val fitPreview = report?.maxCount?.let { "$it × ${sizeLabel(fitSize, settings)} $fitInsulLabel" }
        FilledTonalButton(
            onClick = onOpenFitSheet,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(
                fractionText(
                    if (fitPreview != null) "How many fit (Annex C) · $fitPreview" else "How many fit (Annex C)",
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoltageAndNeutralSection(
    inputs: ConduitFillInputs,
    guidance: NeutralGuidanceResult,
    vm: ConduitFillViewModel,
) {
    val selectedSystem = inputs.voltagePreset
    FormSection("Voltage system & neutral guidance") {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VoltageSystem.entries.forEach { preset ->
                FilterChip(
                    selected = preset == selectedSystem,
                    onClick = { vm.setVoltageSystem(preset) },
                    label = { Text(preset.label) },
                )
            }
        }
        if (selectedSystem == VoltageSystem.CUSTOM) {
            OutlinedTextField(
                value = inputs.customVoltageLabel,
                onValueChange = vm::setCustomVoltageLabel,
                label = { Text("Custom voltage / system note") },
                placeholder = { Text("e.g. 600V 3φ or 4160V") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Circuits in conduit", style = MaterialTheme.typography.labelLarge)
                    CompactIntStepper(
                        value = inputs.circuitCount,
                        onDecrement = vm::decrementCircuitCount,
                        onIncrement = vm::incrementCircuitCount,
                        decrementDescription = "Decrease circuits",
                        incrementDescription = "Increase circuits",
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (selectedSystem.supportsSharedMwbc) {
                        FilterChip(
                            selected = inputs.sharedMwbc,
                            onClick = { vm.setSharedMwbc(!inputs.sharedMwbc) },
                            label = { Text(if (inputs.sharedMwbc) "Shared MWBC (NEC 210.4)" else "Dedicated neutrals") },
                        )
                    }
                    FilterChip(
                        selected = inputs.nonlinearHarmonic,
                        onClick = { vm.setNonlinearHarmonic(!inputs.nonlinearHarmonic) },
                        label = { Text("Nonlinear / harmonics (310.15(E)(3))") },
                    )
                    if (selectedSystem.isThreePhaseWye && !inputs.sharedMwbc) {
                        FilterChip(
                            selected = inputs.threePhaseCircuits,
                            onClick = { vm.setThreePhaseCircuits(!inputs.threePhaseCircuits) },
                            label = { Text(if (inputs.threePhaseCircuits) "3φ 4W circuits" else "1φ L-N branches") },
                        )
                    }
                }
                Text(
                    guidance.explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                guidance.handleTieNote?.let { tieNote ->
                    Text(
                        "⚠ $tieNote",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                guidance.notes.forEach { note ->
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConductorsSection(
    inputs: ConduitFillInputs,
    vm: ConduitFillViewModel,
    settings: AppSettings,
) {
    val (store, cables) = rememberNamedCables()
    var pickingCable by remember { mutableStateOf(false) }
    var creatingCable by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Conductors",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = vm::clearGroups,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text("Clear")
            }
        }

        // 1-tap preset strip
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CircuitPreset.entries.forEach { preset ->
                val enabled = preset == CircuitPreset.NAMED_CABLE || inputs.groups.size < ConduitFillViewModel.MAX_GROUPS ||
                    preset == CircuitPreset.SINGLE_PHASE_BRANCH_2W_G ||
                    preset == CircuitPreset.THREE_PHASE_CIRCUIT_4W_G ||
                    preset == CircuitPreset.GROUND
                AssistChip(
                    onClick = {
                        if (preset == CircuitPreset.NAMED_CABLE) {
                            pickingCable = true
                        } else {
                            vm.applyPreset(preset)
                        }
                    },
                    enabled = enabled,
                    label = { Text(preset.label) },
                )
            }
        }

        inputs.groups.forEachIndexed { index, group ->
            CompactConductorRow(
                index = index,
                group = group,
                canRemove = inputs.groups.size > 1,
                vm = vm,
                settings = settings,
            )
        }
    }

    if (pickingCable) {
        NamedCablePickerDialog(
            cables = cables,
            onPick = { pickingCable = false; vm.addCable(it) },
            onNew = { pickingCable = false; creatingCable = true },
            onDelete = { store.delete(it.id) },
            onDismiss = { pickingCable = false },
        )
    }
    if (creatingCable) {
        NamedCableEditorDialog(
            initial = null,
            onSave = { cable ->
                store.save(cable)
                vm.addCable(cable)
                creatingCable = false
            },
            onDismiss = { creatingCable = false },
        )
    }
}

@Composable
private fun CompactConductorRow(
    index: Int,
    group: ConductorGroupInput,
    canRemove: Boolean,
    vm: ConduitFillViewModel,
    settings: AppSettings,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WireCountStepper(
                    countText = group.count,
                    onCountChange = { vm.setCount(index, it) },
                    onDecrement = { vm.decrementCount(index) },
                    onIncrement = { vm.incrementCount(index) },
                    labelPrefix = "Group ${index + 1}",
                )

                Column(Modifier.weight(1f)) {
                    if (group.isCable) {
                        Text(
                            group.cableName,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "OD ${group.cableOdIn?.let { settings.show(Measure.SMALL_LENGTH, it) } ?: "—"} · " +
                                "${group.cableAreaSqIn?.let { settings.show(Measure.SMALL_AREA, it, if (settings.isMetric) 1 else 4) } ?: "—"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        val size = ConductorSize.fromLabel(group.size) ?: ConductorSize.AWG_12
                        val roleTag = when {
                            group.insulation == BARE_CODE -> "Bare Ground"
                            group.role != ConductorRole.GENERAL -> group.role.label
                            else -> "Wire ${index + 1}"
                        }
                        Text(
                            fractionText("$roleTag · ${sizeLabel(size, settings)} ${insulationShortLabel(group.insulation)}"),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (!group.isCable) {
                    if (group.insulation == BARE_CODE) {
                        Text(
                            "Non-CCC",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        FilterChip(
                            selected = group.currentCarrying,
                            onClick = { vm.setCurrentCarrying(index, !group.currentCarrying) },
                            label = {
                                Text(
                                    if (group.currentCarrying) "CCC" else "Non-CCC",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }

                if (canRemove) {
                    IconButton(onClick = { vm.removeGroup(index) }) {
                        Icon(
                            painterResource(R.drawable.ic_delete),
                            contentDescription = "Remove conductor group ${index + 1}",
                        )
                    }
                }
            }

            if (!group.isCable) {
                val size = ConductorSize.fromLabel(group.size) ?: ConductorSize.AWG_12
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceField(
                        label = "Size",
                        options = sizesFor(group.insulation),
                        selected = size,
                        onSelect = { vm.setSize(index, it) },
                        optionLabel = { sizeLabel(it, settings) },
                        modifier = Modifier.weight(0.45f),
                    )
                    ChoiceField(
                        label = "Insulation",
                        options = insulationCodes,
                        selected = group.insulation,
                        onSelect = { vm.setInsulation(index, it) },
                        optionLabel = ::insulationLabel,
                        modifier = Modifier.weight(0.55f),
                    )
                }
            }
        }
    }
}

@Composable
private fun WireCountStepper(
    countText: String,
    onCountChange: (String) -> Unit,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    labelPrefix: String,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clickable(onClick = onDecrement)
                    .padding(horizontal = 10.dp, vertical = 10.dp)
                    .semantics { contentDescription = "Decrease $labelPrefix count" },
                contentAlignment = Alignment.Center,
            ) {
                Text("−", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            BasicTextField(
                value = countText,
                onValueChange = onCountChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .width(38.dp)
                    .padding(vertical = 8.dp)
                    .semantics { contentDescription = "$labelPrefix wire count" },
            )
            Box(
                modifier = Modifier
                    .clickable(onClick = onIncrement)
                    .padding(horizontal = 10.dp, vertical = 10.dp)
                    .semantics { contentDescription = "Increase $labelPrefix count" },
                contentAlignment = Alignment.Center,
            ) {
                Text("+", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CompactIntStepper(
    value: Int,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    decrementDescription: String,
    incrementDescription: String,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clickable(onClick = onDecrement)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .semantics { contentDescription = decrementDescription },
                contentAlignment = Alignment.Center,
            ) {
                Text("−", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            Text(
                value.toString(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 28.dp),
            )
            Box(
                modifier = Modifier
                    .clickable(onClick = onIncrement)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .semantics { contentDescription = incrementDescription },
                contentAlignment = Alignment.Center,
            ) {
                Text("+", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ThermalsAdvancedSection(
    inputs: ConduitFillInputs,
    derating: DeratingResult?,
    thermalStatus: ThermalStatus?,
    deratingResults: List<com.thedirepack.constructioncalc.core.model.ResultValue>,
    vm: ConduitFillViewModel,
    settings: AppSettings,
    onOpenReference: (String) -> Unit,
) {
    val flagged = inputs.hasNonDefaultThermals(derating)
    val basisText = settings.show(Measure.TEMPERATURE, Code.book.values.ambientBasisC, 0)
    val summary = when {
        derating == null -> "⚠ Enter valid ambient temperature · ${inputs.conductorMaterial.label} · ${inputs.terminationRating.label}"
        flagged && thermalStatus != null -> {
            val prefix = if (thermalStatus.kind == ThermalAdjustmentKind.RATING_INCREASE) "↑ " else "⚠ "
            "$prefix${thermalStatus.summary} · ${inputs.conductorMaterial.label} · ${inputs.terminationRating.label}"
        }
        else -> "$basisText · ≤3 CCC · ${inputs.conductorMaterial.label} · ${inputs.terminationRating.label} terminals"
    }

    val highlightColor = when {
        derating == null -> MaterialTheme.colorScheme.error
        thermalStatus?.kind == ThermalAdjustmentKind.RATING_INCREASE -> MaterialTheme.colorScheme.primary
        flagged -> MaterialTheme.colorScheme.tertiary
        else -> null
    }

    val sectionModifier = if (highlightColor != null) {
        Modifier.border(1.5.dp, highlightColor, MaterialTheme.shapes.medium)
    } else {
        Modifier
    }

    AdvancedSection(
        title = "Thermals & derating",
        summary = summary,
        initiallyExpanded = flagged,
        modifier = sectionModifier,
    ) {
        if (thermalStatus?.hasAmbientIncrease == true) {
            Text(
                "Cold ambient (< $basisText): ${CodeRule.AMBIENT_CORRECTION.cite} provides a rating increase factor > 1.00 before ${CodeRule.TERMINATION_TEMPERATURE.cite} terminal limits.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }
        UnitField(
            label = "Ambient temperature",
            measure = Measure.TEMPERATURE,
            value = inputs.ambientC,
            onValueChange = vm::setAmbient,
            pickKinds = setOf(ValueKind.TEMPERATURE),
            supportingText = "NEC tables are based on $basisText (${Measure.TEMPERATURE.format(Code.book.values.ambientBasisC, if (settings.isMetric) UnitSystem.IMPERIAL else UnitSystem.METRIC, 0)}): < increases, > derates",
        )
        ToggleRow(
            title = "Rooftop, in sunlight",
            detail = AmbientCorrection.rooftopConditionText(settings.unitSystem),
            checked = inputs.rooftop,
            onCheckedChange = vm::setRooftop,
        )
        ChoiceField(
            label = "Conductor material",
            options = ConductorMaterial.entries,
            selected = inputs.conductorMaterial,
            onSelect = vm::setMaterial,
            optionLabel = { it.label },
        )
        SegmentedChoice(
            label = "Terminal temperature rating (${CodeRule.TERMINATION_TEMPERATURE.cite})",
            options = TemperatureRating.entries,
            selected = inputs.terminationRating,
            onSelect = vm::setTermination,
            optionLabel = { it.label },
        )
        ResultCard(
            results = deratingResults,
            notices = derating?.let { d -> d.noticeTexts(settings.unitSystem).map { Notice(it, NoticeLevel.INFO, deratingRef(it)) } }
                ?: listOf(Notice("Enter the ambient temperature to calculate thermal correction.", NoticeLevel.ERROR, FillRefs.AMBIENT)),
            onOpenReference = onOpenReference,
            emptyText = "Mark at least one insulated group as current-carrying (CCC) to see ampacity correction.",
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HowManyFitBottomSheet(
    inputs: ConduitFillInputs,
    report: ConduitFillReport?,
    vm: ConduitFillViewModel,
    settings: AppSettings,
    onDismiss: () -> Unit,
) {
    val type = ConduitType.fromCode(inputs.conduitType) ?: ConduitType.EMT
    val sizes = ConduitTable.sizes(type)
    val selected = TradeSize.fromLabel(inputs.tradeSize)?.let { ConduitTable.find(type, it) } ?: ConduitTable.nearest(type, TradeSize.S_3_4)
    val fitSize = ConductorSize.fromLabel(inputs.fitSize) ?: ConductorSize.AWG_12
    val fitSpec = conductorSpec(inputs.fitInsulation, inputs.fitSize)
    val fitArea = fitSpec?.dimensions?.areaSqIn
    val maxInSelected = fitArea?.let { ConduitFill.maxCount(selected, it, inputs.nipple) } ?: report?.maxCount

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("How many fit (${CodeRule.CONDUCTORS_IN_CONDUIT.cite})", style = MaterialTheme.typography.titleLarge)
            Text(
                "Maximum of one wire size alone in ${ConduitFillReport.conduitName(selected, settings)}" +
                    if (inputs.nipple) " (60% nipple)" else " (Chapter 9 Table 1 + Note 7).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceField(
                    label = "Insulation",
                    options = insulationCodes,
                    selected = inputs.fitInsulation,
                    onSelect = vm::setFitInsulation,
                    optionLabel = ::insulationLabel,
                    modifier = Modifier.weight(0.58f),
                )
                ChoiceField(
                    label = "Size",
                    options = sizesFor(inputs.fitInsulation),
                    selected = fitSize,
                    onSelect = vm::setFitSize,
                    optionLabel = { sizeLabel(it, settings) },
                    modifier = Modifier.weight(0.42f),
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "In ${ConduitFillReport.conduitName(selected, settings)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        when (maxInSelected) {
                            null -> "Not listed in NEC tables"
                            0 -> "0 (none fits)"
                            1 -> "1 conductor"
                            else -> "$maxInSelected conductors"
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (maxInSelected == 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (fitArea != null) {
                Text(
                    "All ${type.displayName} trade sizes (${sizeLabel(fitSize, settings)} ${insulationShortLabel(inputs.fitInsulation)})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    sizes.forEach { conduit ->
                        val count = ConduitFill.maxCount(conduit, fitArea, inputs.nipple)
                        val isSelected = conduit.tradeSize == selected.tradeSize
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { vm.setTradeSize(conduit.tradeSize) }
                                .padding(vertical = 4.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                fractionText(ConduitFillReport.conduitName(conduit, settings)),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                if (count == 0) "—" else count.toString(),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Done")
            }
            Spacer(Modifier.heightIn(min = 8.dp))
        }
    }
}

/** The Reference page a derating/thermal note is about: ambient correction, or the more-than-three adjustment. */
private fun deratingRef(text: String): String =
    if (text.contains("ambient", ignoreCase = true) || text.contains("rooftop", ignoreCase = true)) FillRefs.AMBIENT else FillRefs.ADJUSTMENT

private fun ReportSeverity.toLevel() = when (this) {
    ReportSeverity.INFO -> NoticeLevel.INFO
    ReportSeverity.WARNING -> NoticeLevel.WARNING
    ReportSeverity.ERROR -> NoticeLevel.ERROR
}

private val insulationCodes: List<String> = InsulationType.entries.map { it.code } + BARE_CODE

private fun insulationLabel(code: String) =
    if (code == BARE_CODE) "Bare / equipment ground" else InsulationType.fromCode(code)?.label ?: code

private fun insulationShortLabel(code: String) =
    if (code == BARE_CODE) "Bare" else InsulationType.fromCode(code)?.code ?: code

// "1/0 AWG" would be typeset as the fraction 1⁄0 by the shared fraction formatter; a division slash avoids that.
private fun sizeLabel(size: ConductorSize, settings: AppSettings) = settings.conductorSizeLabel(size).replace("/0", "∕0")

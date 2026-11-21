package com.thedirepack.constructioncalc.feature.conduitfill

import com.thedirepack.constructioncalc.core.electrical.AmbientCorrection
import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.codeLength
import com.thedirepack.constructioncalc.core.electrical.code.codePercentValue
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import com.thedirepack.constructioncalc.core.model.ToolTarget
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices

import com.thedirepack.constructioncalc.core.ui.components.ToggleRow

import com.thedirepack.constructioncalc.feature.conduitfill.domain.toResults
import com.thedirepack.constructioncalc.feature.conduitfill.domain.noticeTexts
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.tradeSizeLabel
import com.thedirepack.constructioncalc.core.ui.conductorSizeLabel
import com.thedirepack.constructioncalc.core.ui.show
import com.thedirepack.constructioncalc.core.ui.isMetric
import com.thedirepack.constructioncalc.core.ui.components.UnitField
import com.thedirepack.constructioncalc.core.measure.Measure
import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.electrical.explanations
import com.thedirepack.constructioncalc.core.ui.components.SegmentedChoice
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.electrical.TemperatureRating
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.ui.tool.toolViewModel
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.ConduitSize
import com.thedirepack.constructioncalc.core.electrical.ConduitTable
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.InsulationType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.components.ChoiceField
import com.thedirepack.constructioncalc.core.ui.components.FormSection
import com.thedirepack.constructioncalc.core.ui.components.NumberField
import com.thedirepack.constructioncalc.core.ui.components.Notice
import com.thedirepack.constructioncalc.core.ui.components.NoticeLevel
import com.thedirepack.constructioncalc.core.ui.components.ResultCard
import com.thedirepack.constructioncalc.core.ui.components.ToolScaffold
import com.thedirepack.constructioncalc.core.ui.tool.ItemDraft
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.feature.conduitfill.domain.ConduitFillReport
import com.thedirepack.constructioncalc.feature.conduitfill.domain.ReportSeverity
import java.util.Locale

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
    val deratingResults = remember(derating) { derating?.toResults().orEmpty() }
    val results = report?.results.orEmpty() + deratingResults

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
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ConduitSection(inputs, vm, settings)
            ConductorsSection(inputs, vm, settings)
            FitSection(inputs, vm, settings)
            DeratingSection(inputs, vm, settings)

            val notices = if (parsed.problems.isNotEmpty()) {
                parsed.problems.map { Notice(it, NoticeLevel.ERROR) }
            } else {
                report?.notices.orEmpty().map { Notice(it.text, it.severity.toLevel()) }
            }
            ResultCard(
                results = report?.results.orEmpty(),
                notices = notices,
                emptyText = if (parsed.problems.isEmpty()) "Enter a count above 0 for at least one conductor to see the fill." else "Fix the inputs above to see the fill.",
            )
            Text("Ampacity derating", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            ResultCard(
                results = deratingResults,
                notices = derating?.let { d -> d.noticeTexts(settings.unitSystem).map { Notice(it, NoticeLevel.INFO) } }
                    ?: listOf(Notice("Enter the ambient temperature to see derating.", NoticeLevel.ERROR)),
                emptyText = "Mark at least one insulated group as current-carrying to see derating.",
            )
        }
    }
}

private fun ReportSeverity.toLevel() = when (this) {
    ReportSeverity.INFO -> NoticeLevel.INFO
    ReportSeverity.WARNING -> NoticeLevel.WARNING
    ReportSeverity.ERROR -> NoticeLevel.ERROR
}

@Composable
private fun ConduitSection(inputs: ConduitFillInputs, vm: ConduitFillViewModel, settings: AppSettings) {
    val type = ConduitType.fromCode(inputs.conduitType) ?: ConduitType.EMT
    val sizes = ConduitTable.sizes(type)
    val selected = TradeSize.fromLabel(inputs.tradeSize)?.let { ConduitTable.find(type, it) } ?: ConduitTable.nearest(type, TradeSize.S_3_4)

    FormSection("Conduit") {
        ChoiceField(
            label = "Conduit type",
            options = ConduitType.entries,
            selected = type,
            onSelect = vm::setConduitType,
            optionLabel = { it.displayName },
            supportingText = "${type.fullName} (${type.article})",
        )
        ChoiceField(
            label = "Trade size",
            options = sizes,
            selected = selected,
            onSelect = { vm.setTradeSize(it.tradeSize) },
            optionLabel = { settings.tradeSizeLabel(it.tradeSize) },
            supportingText = "Inside diameter ${settings.show(Measure.SMALL_LENGTH, selected.internalDiameterIn)}, area ${settings.show(Measure.SMALL_AREA, selected.area100SqIn, if (settings.isMetric) 0 else 3)} (${CodeRule.CONDUIT_DIMENSIONS.cite})",
        )
        ToggleRow(
            title = "Nipple, ${Code.book.values.let { codeLength(it.nippleMaxLengthIn, it.nippleMaxLengthMm, settings.unitSystem) }} or shorter",
            detail = "Between boxes or cabinets: ${codePercentValue(Code.book.values.fillNipplePercent, spaced = false)} fill allowed (${CodeRule.FILL_NIPPLES.cite})",
            checked = inputs.nipple,
            onCheckedChange = vm::setNipple,
        )
    }
}

@Composable
private fun ConductorsSection(inputs: ConduitFillInputs, vm: ConduitFillViewModel, settings: AppSettings) {
    FormSection("Conductors") {
        inputs.groups.forEachIndexed { index, group ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Group ${index + 1}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (inputs.groups.size > 1) {
                            IconButton(onClick = { vm.removeGroup(index) }) {
                                Icon(painterResource(R.drawable.ic_delete), contentDescription = "Remove conductor group ${index + 1}")
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField(
                            label = "Count",
                            value = group.count,
                            onValueChange = { vm.setCount(index, it) },
                            modifier = Modifier.weight(0.38f),
                        )
                        val size = ConductorSize.fromLabel(group.size) ?: ConductorSize.AWG_12
                        ChoiceField(
                            label = "Size",
                            options = sizesFor(group.insulation),
                            selected = size,
                            onSelect = { vm.setSize(index, it) },
                            optionLabel = { sizeLabel(it, settings) },
                            modifier = Modifier.weight(0.62f),
                        )
                    }
                    ChoiceField(
                        label = "Insulation",
                        options = insulationCodes,
                        selected = group.insulation,
                        onSelect = { vm.setInsulation(index, it) },
                        optionLabel = ::insulationLabel,
                        supportingText = if (group.insulation == BARE_CODE) "Bare equipment grounding conductor (${CodeRule.CONDUCTOR_PROPERTIES.cite}, stranded)" else null,
                    )
                    if (group.insulation != BARE_CODE) {
                        ToggleRow(
                            title = "Carries current",
                            detail = "Counts for derating. Off for grounds and neutrals that carry only unbalanced current (${CodeRule.NEUTRAL_COUNTING.cite}, ${CodeRule.GROUNDING_COUNTING.cite}).",
                            checked = group.currentCarrying,
                            onCheckedChange = { vm.setCurrentCarrying(index, it) },
                        )
                    }
                }
            }
        }
        if (inputs.groups.size < ConduitFillViewModel.MAX_GROUPS) {
            OutlinedButton(onClick = vm::addGroup, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add conductor group")
            }
        }
    }
}

@Composable
private fun FitSection(inputs: ConduitFillInputs, vm: ConduitFillViewModel, settings: AppSettings) {
    val size = ConductorSize.fromLabel(inputs.fitSize) ?: ConductorSize.AWG_12
    FormSection("How many fit") {
        Text(
            "The most of one wire that fit alone in the conduit above, as in NEC ${CodeRule.CONDUCTORS_IN_CONDUIT.cite}.",
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
                modifier = Modifier.weight(0.6f),
            )
            ChoiceField(
                label = "Size",
                options = sizesFor(inputs.fitInsulation),
                selected = size,
                onSelect = vm::setFitSize,
                optionLabel = { sizeLabel(it, settings) },
                modifier = Modifier.weight(0.4f),
            )
        }
    }
}


private val insulationCodes: List<String> = InsulationType.entries.map { it.code } + BARE_CODE

private fun insulationLabel(code: String) =
    if (code == BARE_CODE) "Bare / equipment ground" else InsulationType.fromCode(code)?.label ?: code


// "1/0 AWG" would be typeset as the fraction 1⁄0 by the shared fraction formatter; a division slash avoids that.
private fun sizeLabel(size: ConductorSize, settings: AppSettings) = settings.conductorSizeLabel(size).replace("/0", "∕0")

private fun fmt(value: Double, decimals: Int) = com.thedirepack.constructioncalc.core.measure.Decimals.fixed(value, decimals)

@Composable
private fun DeratingSection(inputs: ConduitFillInputs, vm: ConduitFillViewModel, settings: AppSettings) {
    FormSection("Derating conditions") {
        UnitField(
            label = "Ambient temperature",
            measure = Measure.TEMPERATURE,
            value = inputs.ambientC,
            onValueChange = vm::setAmbient,
            pickKinds = setOf(ValueKind.TEMPERATURE),
            supportingText = "NEC tables are based on ${settings.show(Measure.TEMPERATURE, Code.book.values.ambientBasisC, 0)} (${Measure.TEMPERATURE.format(Code.book.values.ambientBasisC, if (settings.isMetric) UnitSystem.IMPERIAL else UnitSystem.METRIC, 0)})",
        )
        ToggleRow(
            title = "Rooftop, in sunlight",
            detail = "Less than 3/4 in. above the roof: ${AmbientCorrection.rooftopAdderText(settings.unitSystem)} (${CodeRule.ROOFTOP_ADDER.cite}); XHHW-2 is exempt",
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
    }
}

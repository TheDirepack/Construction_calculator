package com.thedirepack.constructioncalc.feature.conduitfill

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
import androidx.lifecycle.viewmodel.compose.viewModel
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
    val opened = host.openedItem?.takeIf { it.toolId == ConduitFillTool.ID }
    val vm: ConduitFillViewModel = viewModel(key = "${ConduitFillTool.ID}:${opened?.id}")
    LaunchedEffect(opened) { opened?.let(vm::restore) }

    val inputs = vm.inputs
    val parsed = remember(inputs) { inputs.toRequest() }
    val report = remember(parsed) { parsed.request?.let { ConduitFillReport.build(it) } }
    val results = report?.results.orEmpty()

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
                        title = "Conduit fill: ${conduit.displayName}",
                        inputs = ToolInputs.encode(inputs),
                        results = r.results,
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
            ConduitSection(inputs, vm)
            ConductorsSection(inputs, vm)
            FitSection(inputs, vm)

            val notices = if (parsed.problems.isNotEmpty()) {
                parsed.problems.map { Notice(it, NoticeLevel.ERROR) }
            } else {
                report?.notices.orEmpty().map { Notice(it.text, it.severity.toLevel()) }
            }
            ResultCard(
                results = results,
                notices = notices,
                emptyText = if (parsed.problems.isEmpty()) "Enter a count above 0 for at least one conductor to see the fill." else "Fix the inputs above to see the fill.",
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
private fun ConduitSection(inputs: ConduitFillInputs, vm: ConduitFillViewModel) {
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
            optionLabel = ::tradeSizeLabel,
            supportingText = "Inside diameter ${fmt(selected.internalDiameterIn, 3)} in, area ${fmt(selected.area100SqIn, 3)} in² (Table 4)",
        )
        ToggleRow(
            title = "Nipple, 24 in. or shorter",
            subtitle = "Between boxes or cabinets: 60% fill allowed (Table 1 Note 4)",
            checked = inputs.nipple,
            onCheckedChange = vm::setNipple,
        )
    }
}

@Composable
private fun ConductorsSection(inputs: ConduitFillInputs, vm: ConduitFillViewModel) {
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
                            optionLabel = ::sizeLabel,
                            modifier = Modifier.weight(0.62f),
                        )
                    }
                    ChoiceField(
                        label = "Insulation",
                        options = insulationCodes,
                        selected = group.insulation,
                        onSelect = { vm.setInsulation(index, it) },
                        optionLabel = ::insulationLabel,
                        supportingText = if (group.insulation == BARE_CODE) "Bare equipment grounding conductor (Table 8, stranded)" else null,
                    )
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
private fun FitSection(inputs: ConduitFillInputs, vm: ConduitFillViewModel) {
    val size = ConductorSize.fromLabel(inputs.fitSize) ?: ConductorSize.AWG_12
    FormSection("How many fit") {
        Text(
            "The most of one wire that fit alone in the conduit above, as in NEC Annex C.",
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
                optionLabel = ::sizeLabel,
                modifier = Modifier.weight(0.4f),
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

private val insulationCodes: List<String> = InsulationType.entries.map { it.code } + BARE_CODE

private fun insulationLabel(code: String) =
    if (code == BARE_CODE) "Bare / equipment ground" else InsulationType.fromCode(code)?.label ?: code

private fun tradeSizeLabel(size: ConduitSize) = "${size.tradeSize.display} (metric ${size.tradeSize.metricDesignator})"

// "1/0 AWG" would be typeset as the fraction 1⁄0 by the shared fraction formatter; a division slash avoids that.
private fun sizeLabel(size: ConductorSize) = size.label.replace("/0", "∕0")

private fun fmt(value: Double, decimals: Int) = String.format(Locale.US, "%.${decimals}f", value)

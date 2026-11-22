package com.thedirepack.constructioncalc.feature.formulas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConductorResistance
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.ResistanceUnit
import com.thedirepack.constructioncalc.core.electrical.Stranding
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.electrical.formula.FormulaCatalog
import com.thedirepack.constructioncalc.core.electrical.formula.Variable
import com.thedirepack.constructioncalc.core.measure.Measure
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.core.model.ToolTarget
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.ui.components.ChoiceField
import com.thedirepack.constructioncalc.core.ui.components.FormSection
import com.thedirepack.constructioncalc.core.ui.components.NumberField
import com.thedirepack.constructioncalc.core.ui.components.ResultCard
import com.thedirepack.constructioncalc.core.ui.components.SegmentedChoice
import com.thedirepack.constructioncalc.core.ui.components.ToolScaffold
import com.thedirepack.constructioncalc.core.ui.components.UnitField
import com.thedirepack.constructioncalc.core.ui.conductorSizeLabel
import com.thedirepack.constructioncalc.core.ui.tool.ItemDraft
import com.thedirepack.constructioncalc.core.ui.tool.LocalTools
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.core.ui.tool.titleOf
import com.thedirepack.constructioncalc.core.ui.tool.toolViewModel

/** Reference topic id for a formula's entry on the formula sheet. */
fun formulaTopic(formulaId: String) = "formula:$formulaId"

@Composable
fun FormulasScreen(host: ToolHost) {
    val vm: FormulasViewModel = toolViewModel(FormulasTool.ID, host)
    LaunchedEffect(host.target) {
        (host.target as? ToolTarget.OfTopic)?.let { t ->
            vm.prefill(t) {
                if (t.topicId == FormulaInputs.RESISTANCE_LENGTH_TOPIC) {
                    FormulaInputs.startResistanceLength()
                } else {
                    FormulaCatalog.find(t.topicId.removePrefix("formula:"))?.let { f -> FormulaInputs.start(f) }
                }
            }
        }
    }
    val settings = com.thedirepack.constructioncalc.core.ui.rememberAppSettings()
    val inputs = vm.inputs
    val isWireLength = inputs.mode == FormulasMode.RESISTANCE_LENGTH
    val formula = inputs.formula
    val output = remember(inputs, settings) { formulasOutput(inputs, settings) }
    val wireOutput = remember(inputs, settings) { resistanceLengthOutput(inputs, settings) }
    val summary = if (isWireLength) {
        wireOutput.summary
    } else {
        output.results.take(3).joinToString(" · ") { "${it.label.substringBefore(" (")} ${it.text}" }
    }
    val activeResults = if (isWireLength) wireOutput.results else output.results
    val canSave = if (isWireLength) wireOutput.calculation != null else output.solution.steps.isNotEmpty()

    LaunchedEffect(isWireLength, activeResults) {
        if (canSave && activeResults.isNotEmpty()) {
            host.publish(activeResults, context = if (isWireLength) "Wire length from resistance" else formula.title)
        }
    }

    ToolScaffold(
        title = FormulasTool.title,
        host = host,
        onSave = if (!canSave) null else ({
            val title = if (isWireLength) "Wire length from Ω" else formula.title
            host.saveToProject(ItemDraft(FormulasTool.ID, title, ToolInputs.encode(inputs), activeResults, summary))
        }),
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SegmentedChoice(
                options = FormulasMode.entries,
                selected = inputs.mode,
                onSelect = { m -> vm.update { it.copy(mode = m) } },
                label = "Calculator",
                optionLabel = { it.label },
            )

            if (isWireLength) {
                ResistanceLengthContent(inputs, vm, settings, wireOutput, host)
            } else {
                ChoiceField(
                    label = "Formula",
                    options = FormulaCatalog.all.sortedBy { it.group.ordinal },
                    selected = formula,
                    onSelect = { f -> vm.update { it.withFormula(f) } },
                    optionLabel = { it.title },
                    supportingText = formula.summary,
                )
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        formula.relations.forEach { r ->
                            Text(r.expression, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif)
                        }
                    }
                }

                FormSection("Values (leave blank to solve)") {
                    formula.variables.forEach { v ->
                        VariableRow(
                            variable = v,
                            text = inputs.values[v.id].orEmpty(),
                            unitIndex = inputs.unitIndex(v.id, settings.unitSystem),
                            computed = output.computedText[v.id]?.takeIf { inputs.values[v.id].isNullOrBlank() },
                            onText = { t -> vm.update { it.withValue(v.id, t, settings.unitSystem) } },
                            onUnit = { u -> vm.update { it.withUnit(v.id, u, settings.unitSystem) } },
                        )
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.update { it.cleared() } }) { Text("Clear") }
                    formula.toolId?.let { toolId ->
                        val tools = LocalTools.current
                        TextButton(onClick = { host.openTool(toolId) }) { Text("Open ${tools.titleOf(toolId)}") }
                    }
                    TextButton(onClick = { host.openReference(formulaTopic(formula.id)) }) { Text("Formula sheet") }
                }

                ResultCard(
                    output.results,
                    notices = output.notices,
                    emptyText = "Enter what you know.",
                    onOpenReference = host::openReference,
                )

                if (output.steps.isNotEmpty()) {
                    FormSection("How it was solved") {
                        output.steps.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResistanceLengthContent(
    inputs: FormulaInputs,
    vm: FormulasViewModel,
    settings: com.thedirepack.constructioncalc.core.data.AppSettings,
    output: ResistanceLengthOutput,
    host: ToolHost,
) {
    val size = inputs.conductorSize
    FormSection("Measured resistance") {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(
                label = "Resistance",
                value = inputs.measuredResistance,
                onValueChange = { t -> vm.update { it.copy(measuredResistance = t) } },
                modifier = Modifier.weight(1f),
                pickKinds = setOf(ValueKind.RESISTANCE),
                onPick = { picked ->
                    picked.rational?.toDouble()?.let { ohms ->
                        val inUnit = ohms / inputs.resistanceUnit.ohmsPerUnit
                        vm.update { it.copy(measuredResistance = FormulasText.number(inUnit)) }
                    }
                },
            )
            ChoiceField(
                label = "Unit",
                options = ResistanceUnit.entries,
                selected = inputs.resistanceUnit,
                onSelect = { u -> vm.update { it.copy(resistanceUnit = u) } },
                optionLabel = { it.label },
                modifier = Modifier.width(104.dp).padding(top = 8.dp),
            )
        }
        SegmentedChoice(
            options = listOf(false, true),
            selected = inputs.wireRoundTrip,
            onSelect = { loop -> vm.update { it.copy(wireRoundTrip = loop) } },
            label = "Measurement path",
            optionLabel = { if (it) "Round-trip loop (÷2)" else "One-way" },
        )
    }

    FormSection("Conductor") {
        ChoiceField(
            label = "Material",
            options = ConductorMaterial.entries,
            selected = inputs.wireMaterial,
            onSelect = { m -> vm.update { it.copy(wireMaterial = m) } },
            optionLabel = { it.label },
        )
        ChoiceField(
            label = "Wire size",
            options = ConductorSize.entries,
            selected = size,
            onSelect = { s -> vm.update { it.copy(wireSize = s.label) } },
            optionLabel = { settings.conductorSizeLabel(it).replace("/0", "∕0") },
        )
        if (ConductorResistance.hasSolidOption(size)) {
            SegmentedChoice(
                options = Stranding.entries,
                selected = inputs.wireStranding,
                onSelect = { s -> vm.update { it.copy(wireStranding = s) } },
                label = "Stranding",
                optionLabel = { it.label },
            )
        }
        UnitField(
            label = "Conductor temperature",
            measure = Measure.TEMPERATURE,
            value = inputs.wireAmbientC,
            onValueChange = { t -> vm.update { it.copy(wireAmbientC = t) } },
            pickKinds = setOf(ValueKind.TEMPERATURE),
            supportingText = "Corrects from the ${Measure.TEMPERATURE.format(Code.book.values.resistanceReferenceC, UnitSystem.METRIC, 0)} basis of NEC ${CodeRule.CONDUCTOR_PROPERTIES.cite}.",
        )
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.update { it.cleared() } }) { Text("Clear") }
        TextButton(onClick = { host.openReference("nec-conductor-properties") }) { Text("${CodeRule.CONDUCTOR_PROPERTIES.cite} reference") }
    }

    ResultCard(
        output.results,
        notices = output.notices,
        emptyText = "Enter a measured resistance to find wire length.",
        onOpenReference = host::openReference,
    )
}

@Composable
private fun VariableRow(
    variable: Variable,
    text: String,
    unitIndex: Int,
    computed: String?,
    onText: (String) -> Unit,
    onUnit: (Int) -> Unit,
) {
    val units = variable.shownUnits
    val unit = units[unitIndex]
    val kind = valueKind(variable.quantity)
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(
            label = "${variable.name} (${variable.symbol})",
            value = text,
            onValueChange = onText,
            modifier = Modifier.weight(1f),
            unit = if (units.size == 1) unit.label else "",
            supportingText = computed?.let { "Solved: $it" } ?: variable.hint,
            allowNegative = variable.min == null || variable.min!! < 0,
            pickKinds = if (kind == ValueKind.NUMBER) emptySet() else setOf(kind),
            onPick = { picked ->
                picked.rational?.toDouble()?.let { exact ->
                    onText(FormulasText.number(fromPublished(variable.quantity, exact) / unit.factor))
                }
            },
        )
        if (units.size > 1) {
            ChoiceField(
                label = "Unit",
                options = units.indices.toList(),
                selected = unitIndex,
                onSelect = onUnit,
                optionLabel = { units[it].label },
                modifier = Modifier.width(104.dp).padding(top = 8.dp),
            )
        }
    }
}

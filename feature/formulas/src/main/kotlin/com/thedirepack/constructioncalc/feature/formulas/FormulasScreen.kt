package com.thedirepack.constructioncalc.feature.formulas

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConductorResistance
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.ResistanceUnit
import com.thedirepack.constructioncalc.core.electrical.Stranding
import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeEdition
import com.thedirepack.constructioncalc.core.electrical.code.CodeProfile
import com.thedirepack.constructioncalc.core.electrical.code.CodeRegion
import com.thedirepack.constructioncalc.core.electrical.code.CodeRegions
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.electrical.formula.FormulaCatalog
import com.thedirepack.constructioncalc.core.electrical.formula.Variable
import com.thedirepack.constructioncalc.core.measure.Measure
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.core.model.ToolTarget
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.components.ChoiceField
import com.thedirepack.constructioncalc.core.ui.components.FormSection
import com.thedirepack.constructioncalc.core.ui.components.NumberField
import com.thedirepack.constructioncalc.core.ui.components.ResultCard
import com.thedirepack.constructioncalc.core.ui.components.SegmentedChoice
import com.thedirepack.constructioncalc.core.ui.components.ToolScaffold
import com.thedirepack.constructioncalc.core.ui.components.UnitField
import com.thedirepack.constructioncalc.core.ui.conductorSizeLabel
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.tool.ItemDraft
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices
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

    val settings = rememberAppSettings()
    val moduleSettings = LocalAppServices.current.settings.module(FormulasTool.ID)
    val store = remember(moduleSettings) { FormulasStore(moduleSettings) }
    val storeVersion by moduleSettings.changes.collectAsStateWithLifecycle()

    val favoriteIds = remember(storeVersion) { store.favorites().toSet() }
    val favoriteFormulas = remember(storeVersion) { store.favorites().mapNotNull(FormulaCatalog::find) }
    val codeOverride = remember(storeVersion) { store.codeOverride() }
    val appProfile = remember(settings.codeEdition, settings.codeRegion) {
        val global = Code.globalProfile
        val edition = CodeEdition.parse(settings.codeEdition)
            ?: CodeEdition.NEC_2023
        val regionId = CodeRegions.getOrDefault(settings.codeRegion).id
        if (global.edition == edition && global.regionId == regionId) global else CodeProfile(edition, regionId)
    }
    val overrideProfile = remember(codeOverride, appProfile) {
        FormulasCodeProfiles.resolveOverrideOrNull(codeOverride, appProfile)
    }
    val activeProfile = overrideProfile ?: appProfile
    val chipLabel = remember(activeProfile, codeOverride) {
        FormulasCodeProfiles.chipLabel(activeProfile, isOverride = !codeOverride.isInherited)
    }

    var query by rememberSaveable { mutableStateOf("") }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var showCodeDialog by rememberSaveable { mutableStateOf(false) }

    val inputs = vm.inputs
    val isWireLength = inputs.mode == FormulasMode.RESISTANCE_LENGTH
    val formula = inputs.formula
    val output = remember(inputs, settings, overrideProfile, activeProfile) { formulasOutput(inputs, settings, overrideProfile) }
    val wireOutput = remember(inputs, settings, overrideProfile, activeProfile) { resistanceLengthOutput(inputs, settings, overrideProfile) }
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

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = !codeOverride.isInherited,
                    onClick = { showCodeDialog = true },
                    label = { Text(chipLabel) },
                )
                Text(
                    if (codeOverride.isInherited) "App code setting · Tap to override" else "Per-tool override · Tap to change",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (isWireLength) {
                ResistanceLengthContent(inputs, vm, settings, overrideProfile, wireOutput, host)
            } else {
                val filteredFormulas = remember(query, favoritesOnly, favoriteIds) {
                    FormulasCatalogSearch.filter(query, favoritesOnly, favoriteIds)
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("Search formulas (Ohm, Motor, 3-phase…)") },
                            singleLine = true,
                            trailingIcon = if (query.isEmpty()) null else ({
                                IconButton(onClick = { query = "" }) {
                                    Icon(painterResource(R.drawable.ic_close), contentDescription = "Clear search")
                                }
                            }),
                            modifier = Modifier.weight(1f),
                        )
                        FilterChip(
                            selected = favoritesOnly,
                            onClick = { favoritesOnly = !favoritesOnly },
                            leadingIcon = {
                                Icon(
                                    painterResource(if (favoritesOnly) R.drawable.ic_star else R.drawable.ic_star_border),
                                    contentDescription = null,
                                )
                            },
                            label = {
                                Text(if (favoriteIds.isEmpty()) "Favorites" else "Favorites (${favoriteIds.size})")
                            },
                        )
                    }

                    if (query.isNotBlank() || favoritesOnly) {
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            if (filteredFormulas.isEmpty()) {
                                val emptyMessage = if (favoritesOnly && query.isBlank()) {
                                    "No favorite formulas yet. Tap the star next to any formula to save it."
                                } else {
                                    "No formulas match \"$query\"."
                                }
                                Text(
                                    emptyMessage,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(12.dp),
                                )
                            } else {
                                Column(
                                    Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
                                ) {
                                    filteredFormulas.forEachIndexed { idx, candidate ->
                                        val isSelected = candidate.id == formula.id
                                        val isFav = candidate.id in favoriteIds
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clickable { vm.update { it.withFormula(candidate) } }
                                                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    candidate.title,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    candidate.summary,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            IconButton(onClick = { store.toggleFavorite(candidate.id) }) {
                                                Icon(
                                                    painterResource(if (isFav) R.drawable.ic_star else R.drawable.ic_star_border),
                                                    contentDescription = if (isFav) "Remove ${candidate.title} from favorites" else "Add ${candidate.title} to favorites",
                                                    tint = if (isFav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                        if (idx < filteredFormulas.lastIndex) {
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                        }
                                    }
                                }
                            }
                        }
                    } else if (favoriteFormulas.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            favoriteFormulas.forEach { fav ->
                                FilterChip(
                                    selected = fav.id == formula.id,
                                    onClick = { vm.update { it.withFormula(fav) } },
                                    leadingIcon = {
                                        Icon(painterResource(R.drawable.ic_star), contentDescription = null)
                                    },
                                    label = { Text(fav.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                )
                            }
                        }
                    }

                    val dropdownOptions = remember(filteredFormulas, formula) {
                        when {
                            filteredFormulas.isEmpty() -> FormulasCatalogSearch.ordered
                            formula in filteredFormulas -> filteredFormulas
                            else -> listOf(formula) + filteredFormulas
                        }
                    }
                    val activeIsFavorite = formula.id in favoriteIds
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ChoiceField(
                            label = "Formula",
                            options = dropdownOptions,
                            selected = formula,
                            onSelect = { f -> vm.update { it.withFormula(f) } },
                            optionLabel = { it.title },
                            supportingText = formula.summary,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { store.toggleFavorite(formula.id) },
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Icon(
                                painterResource(if (activeIsFavorite) R.drawable.ic_star else R.drawable.ic_star_border),
                                contentDescription = if (activeIsFavorite) "Remove ${formula.title} from favorites" else "Add ${formula.title} to favorites",
                                tint = if (activeIsFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

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

    if (showCodeDialog) {
        FormulasCodeOverrideDialog(
            appProfile = appProfile,
            currentOverride = codeOverride,
            onInheritApp = {
                store.clearCodeOverride()
                showCodeDialog = false
            },
            onApplyOverride = { edition, region ->
                store.setCodeOverride(edition.name, region.id)
                showCodeDialog = false
            },
            onDismiss = { showCodeDialog = false },
        )
    }
}

@Composable
private fun ResistanceLengthContent(
    inputs: FormulaInputs,
    vm: FormulasViewModel,
    settings: com.thedirepack.constructioncalc.core.data.AppSettings,
    overrideProfile: CodeProfile?,
    output: ResistanceLengthOutput,
    host: ToolHost,
) {
    val size = inputs.conductorSize
    val hasSolid = remember(size, overrideProfile) {
        withOptionalCodeProfile(overrideProfile) { ConductorResistance.hasSolidOption(size) }
    }
    val (tableBasisTempText, tableCite) = remember(settings.unitSystem, overrideProfile) {
        withOptionalCodeProfile(overrideProfile) {
            Measure.TEMPERATURE.format(Code.book.values.resistanceReferenceC, UnitSystem.METRIC, 0) to
                CodeRule.CONDUCTOR_PROPERTIES.cite
        }
    }

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
        if (hasSolid) {
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
            supportingText = "Corrects from the $tableBasisTempText basis of NEC $tableCite.",
        )
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.update { it.cleared() } }) { Text("Clear") }
        TextButton(onClick = { host.openReference("nec-conductor-properties") }) { Text("$tableCite reference") }
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

@Composable
private fun FormulasCodeOverrideDialog(
    appProfile: CodeProfile,
    currentOverride: FormulasCodeOverride,
    onInheritApp: () -> Unit,
    onApplyOverride: (CodeEdition, CodeRegion) -> Unit,
    onDismiss: () -> Unit,
) {
    val resolvedCurrent = remember(currentOverride, appProfile) {
        FormulasCodeProfiles.resolve(currentOverride, appProfile)
    }
    var inheritApp by remember(currentOverride) { mutableStateOf(currentOverride.isInherited) }
    var selectedEdition by remember(resolvedCurrent) { mutableStateOf(resolvedCurrent.edition) }
    var selectedRegionId by remember(resolvedCurrent) { mutableStateOf(resolvedCurrent.regionId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("NEC edition & region") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = inheritApp, role = Role.RadioButton, onClick = { inheritApp = true })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(selected = inheritApp, onClick = null)
                    Column(Modifier.weight(1f)) {
                        Text(FormulasCodeProfiles.INHERIT_LABEL, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Follows app setting (${FormulasCodeProfiles.chipLabel(appProfile)})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                HorizontalDivider()

                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Code edition", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    CodeEdition.entries.forEach { edition ->
                        val enabled = true
                        val selected = !inheritApp && enabled && selectedEdition == edition
                        val label = edition.label
                        val note = if (enabled) {
                            edition.statusNote
                        } else {
                            edition.statusNote
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected,
                                    enabled = enabled,
                                    role = Role.RadioButton,
                                    onClick = {
                                        if (enabled) {
                                            inheritApp = false
                                            selectedEdition = edition
                                        }
                                    },
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RadioButton(selected = selected, onClick = null, enabled = enabled)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                )
                                Text(
                                    note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                )
                            }
                        }
                    }
                }

                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Jurisdiction", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    CodeRegions.entries.forEach { region ->
                        val selected = !inheritApp && selectedRegionId == region.id
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = {
                                        inheritApp = false
                                        selectedRegionId = region.id
                                    },
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Column(Modifier.weight(1f)) {
                                Text(region.name, style = MaterialTheme.typography.bodyMedium)
                                val note = buildString {
                                    append(region.description)
                                    if (!region.hasOverrides && region.id != CodeRegions.STANDARD_ID) {
                                        append(" No local amendments entered; uses ${selectedEdition.label} as published.")
                                    }
                                }
                                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (inheritApp) onInheritApp()
                    else onApplyOverride(selectedEdition, CodeRegions.getOrDefault(selectedRegionId))
                },
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
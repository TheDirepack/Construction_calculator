package com.thedirepack.constructioncalc.feature.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.InsulationType
import com.thedirepack.constructioncalc.core.electrical.StandardOcpd
import com.thedirepack.constructioncalc.core.electrical.TemperatureRating
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.Circuit
import com.thedirepack.constructioncalc.core.model.CircuitKind
import com.thedirepack.constructioncalc.core.model.ConductorSpec
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.model.Raceway
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.model.Run
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.model.design.CircuitDesign
import com.thedirepack.constructioncalc.core.model.design.PanelLayout
import com.thedirepack.constructioncalc.core.model.design.ProjectAnalysis
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.core.ui.components.ChoiceField
import com.thedirepack.constructioncalc.core.ui.components.ConfirmDialog
import com.thedirepack.constructioncalc.core.ui.components.FormSection
import com.thedirepack.constructioncalc.core.ui.components.MeasureField
import com.thedirepack.constructioncalc.core.ui.components.Notice
import com.thedirepack.constructioncalc.core.ui.components.NoticeLevel
import com.thedirepack.constructioncalc.core.ui.components.NumberField
import com.thedirepack.constructioncalc.core.ui.components.ResultCard
import com.thedirepack.constructioncalc.core.ui.components.SegmentedChoice
import com.thedirepack.constructioncalc.core.ui.components.TextInputDialog
import com.thedirepack.constructioncalc.core.ui.components.racewaySummary
import com.thedirepack.constructioncalc.core.ui.formatDecimal
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.toDecimalOrNull
import com.thedirepack.constructioncalc.feature.panels.domain.LoadMath
import com.thedirepack.constructioncalc.feature.panels.domain.PublishedValues
import com.thedirepack.constructioncalc.feature.panels.domain.Wire
import com.thedirepack.constructioncalc.feature.panels.domain.designRuns
import com.thedirepack.constructioncalc.feature.panels.domain.deleteCircuit
import com.thedirepack.constructioncalc.feature.panels.domain.freeSlots
import com.thedirepack.constructioncalc.feature.panels.domain.addGroup
import com.thedirepack.constructioncalc.feature.panels.domain.newGroup
import com.thedirepack.constructioncalc.feature.panels.domain.supportedPoles
import com.thedirepack.constructioncalc.feature.panels.domain.upsertCircuit

private enum class LoadUnit(val label: String) { VA("VA"), AMPS("Amps") }

private fun slotLabel(panel: Panel, slot: Int, poles: Int): String {
    val slots = (0 until poles).map { slot + 2 * it }
    val phases = slots.map { PanelLayout.phaseOf(it, panel.system.phases).name }
    return "${slots.joinToString(", ")} · phase ${phases.joinToString("/")}"
}

/** The nearest position at or after [slot] where [poles] fit, else the first that fits, else null. */
private fun fitSlot(panel: Panel, poles: Int, slot: Int, ignore: String): Int? {
    val free = freeSlots(panel, poles, ignore)
    return if (slot in free) slot else free.firstOrNull { it >= slot } ?: free.firstOrNull()
}

/**
 * Edits one circuit (or adds a new one) and shows what the design engine makes of it, live.
 * Position and panel choices only offer places the breaker fits, so moving it is just choosing
 * another. Nothing is saved until Save; Delete removes it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CircuitSheet(
    ctx: PanelsContext,
    initialPanelId: String,
    initial: Circuit,
    isNew: Boolean,
    onDismiss: () -> Unit,
) {
    var panelId by remember { mutableStateOf(initialPanelId) }
    val panel = ctx.project.panel(panelId)
    if (panel == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    var draft by remember {
        val start = ctx.project.panel(initialPanelId)
        mutableStateOf(initial.copy(slot = start?.let { fitSlot(it, initial.poles, initial.slot, initial.id) } ?: initial.slot))
    }
    var loadUnit by remember { mutableStateOf(LoadUnit.VA) }
    var loadText by remember { mutableStateOf(initial.loadVa?.let { formatDecimal(it, 1) }.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var newGroupPrompt by remember { mutableStateOf(false) }
    var newRunPrompt by remember { mutableStateOf(false) }
    val settings = rememberAppSettings()
    val length = rememberLengthFormatter()

    val vaValue = loadText.toDecimalOrNull()?.let { if (loadUnit == LoadUnit.VA) it else LoadMath.va(panel.system, draft.poles, it) }
    val effective = draft.copy(loadVa = if (draft.kind == CircuitKind.LOAD) vaValue else null)
    val design: CircuitDesign? = remember(ctx.project, panelId, effective) {
        ProjectAnalysis.analyze(ctx.project.upsertCircuit(panelId, effective, 0L)).circuits[effective.id]
    }
    LaunchedEffect(design) {
        if (design != null) {
            val values = PublishedValues.circuit(design, length)
            if (values.isNotEmpty()) ctx.host.publish(values, context = "${panel.name} circuit ${effective.slots.joinToString(",")}")
        }
    }

    fun retarget(newPanelId: String = panelId, poles: Int = draft.poles) {
        val target = ctx.project.panel(newPanelId) ?: return
        val maxPoles = supportedPoles(target, draft.id).maxOrNull() ?: 1
        val p = poles.coerceAtMost(maxPoles)
        val slot = fitSlot(target, p, draft.slot, draft.id) ?: draft.slot
        panelId = newPanelId
        draft = draft.copy(
            poles = p,
            slot = slot,
            neutral = if (p != draft.poles) p == 1 && target.system.lineToNeutral != null else draft.neutral && target.system.lineToNeutral != null,
        )
    }

    val free = freeSlots(panel, draft.poles, draft.id)
    val canSave = draft.slot in free
    val title = if (isNew) "New circuit" else "Circuit ${draft.slots.joinToString(",")}"

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                "${panel.name} · ${panel.system.label}" + if (ctx.readOnly) " · archived project, read-only" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (ctx.readOnly) {
                ReadOnlySummary(effective, panel)
            } else {
                FormSection("Breaker") {
                    SegmentedChoice(
                        options = CircuitKind.entries,
                        selected = draft.kind,
                        onSelect = { kind ->
                            draft = draft.copy(
                                kind = kind,
                                breakerAmps = if (kind == CircuitKind.SPACE) null else draft.breakerAmps ?: 20,
                            )
                        },
                        label = "What's in this position",
                        optionLabel = { it.label },
                    )
                    if (ctx.project.panels.size > 1) {
                        ChoiceField(
                            label = "Panel",
                            options = ctx.project.panels,
                            selected = panel,
                            onSelect = { retarget(newPanelId = it.id) },
                            optionLabel = { it.name },
                        )
                    }
                    val poleOptions = (supportedPoles(panel, draft.id) + draft.poles).distinct().sorted()
                    SegmentedChoice(
                        options = poleOptions,
                        selected = draft.poles,
                        onSelect = { retarget(poles = it) },
                        label = "Poles",
                        optionLabel = { "$it-pole" },
                    )
                    ChoiceField(
                        label = "Position",
                        options = if (draft.slot in free) free else listOf(draft.slot) + free,
                        selected = draft.slot,
                        onSelect = { draft = draft.copy(slot = it) },
                        optionLabel = { slotLabel(panel, it, draft.poles) },
                        supportingText = if (free.isEmpty()) "No room for a ${draft.poles}-pole breaker in ${panel.name}." else null,
                    )
                    if (draft.kind != CircuitKind.SPACE) {
                        ChoiceField(
                            label = "Breaker",
                            options = StandardOcpd.ratings,
                            selected = draft.breakerAmps ?: 20,
                            onSelect = { draft = draft.copy(breakerAmps = it) },
                            optionLabel = { "$it A" },
                        )
                    }
                    OutlinedTextField(
                        value = draft.description,
                        onValueChange = { draft = draft.copy(description = it) },
                        label = { Text("Description") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (draft.kind == CircuitKind.LOAD) {
                    FormSection("Load") {
                        SegmentedChoice(
                            options = LoadUnit.entries,
                            selected = loadUnit,
                            onSelect = { unit ->
                                if (unit != loadUnit) {
                                    loadText = when {
                                        vaValue == null -> loadText
                                        unit == LoadUnit.VA -> formatDecimal(vaValue, 1)
                                        else -> formatDecimal(LoadMath.amps(panel.system, draft.poles, vaValue), 2)
                                    }
                                    loadUnit = unit
                                }
                            },
                            label = "Enter the load in",
                            optionLabel = { it.label },
                        )
                        NumberField(
                            label = if (loadUnit == LoadUnit.VA) "Connected load" else "Load current",
                            value = loadText,
                            onValueChange = { loadText = it },
                            unit = if (loadUnit == LoadUnit.VA) "VA" else "A",
                            pickKinds = if (loadUnit == LoadUnit.VA) setOf(ValueKind.APPARENT_POWER, ValueKind.POWER) else setOf(ValueKind.CURRENT),
                            supportingText = vaValue?.let {
                                val amps = LoadMath.amps(panel.system, draft.poles, it)
                                if (loadUnit == LoadUnit.VA) "= ${Wire.amps(amps)} at ${voltsText(panel, draft.poles)}" else "= ${Wire.va(it)} at ${voltsText(panel, draft.poles)}"
                            } ?: "Needed to check voltage drop and phase balance.",
                        )
                        ToggleRow("Continuous load", draft.continuous, { draft = draft.copy(continuous = it) }, "On for 3 hours or more: wire and breaker sized at 125%.")
                        if (panel.system.lineToNeutral != null) {
                            ToggleRow("Neutral conductor", draft.neutral, { draft = draft.copy(neutral = it) }, "Pulled with the circuit and counted when it carries current.")
                        }
                        ToggleRow("Nonlinear load", draft.nonlinear, { draft = draft.copy(nonlinear = it) }, "Electronic loads: the neutral counts as current-carrying.")
                        ToggleRow(
                            "Multiple cord-and-plug receptacles",
                            draft.multipleCordReceptacles,
                            { draft = draft.copy(multipleCordReceptacles = it) },
                            "Affects the minimum wire size (NEC 210.19).",
                        )
                    }
                }

                if (draft.kind != CircuitKind.SPACE) {
                    FormSection("Groups") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ctx.project.groups.forEach { g ->
                                val on = g.id in draft.groupIds
                                FilterChip(
                                    selected = on,
                                    onClick = { draft = draft.copy(groupIds = if (on) draft.groupIds - g.id else draft.groupIds + g.id) },
                                    label = { Text(g.name) },
                                    leadingIcon = { GroupTag(g) },
                                )
                            }
                            AssistChip(onClick = { newGroupPrompt = true }, label = { Text("New group…") })
                        }
                    }

                    FormSection("Conduit runs") {
                        val runs = remember(ctx.project) { ctx.project.designRuns() }
                        val shown = runs + ctx.project.runs.filter { it.id in draft.runIds && it !in runs }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            shown.forEach { r ->
                                val on = r.id in draft.runIds
                                FilterChip(
                                    selected = on,
                                    onClick = { draft = draft.copy(runIds = if (on) draft.runIds - r.id else draft.runIds + r.id) },
                                    label = { Text(r.name) },
                                )
                            }
                            AssistChip(onClick = { newRunPrompt = true }, label = { Text("New conduit run…") })
                        }
                        draft.runIds.mapNotNull(ctx.project::run).forEach { r ->
                            val rd = ctx.design.runs[r.id]
                            Text(
                                "${r.name}: " + (r.raceway?.let { racewaySummary(it, settings, autoSize = rd?.conduit?.tradeSize) } ?: "no conduit set, edit it in Runs"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        MeasureField(
                            label = "Length override (one way)",
                            value = draft.length?.toQuantity(),
                            onValueChange = { draft = draft.copy(length = it?.stored()) },
                            supportingText = if (draft.length == null) {
                                design?.lengthInches?.let { "Using ${length(Quantity.inches(com.thedirepack.constructioncalc.core.math.Rational.of(it)))} from its runs" }
                                    ?: "Otherwise the sum of its runs' lengths."
                            } else {
                                null
                            },
                        )
                    }

                    FormSection("Conductor") {
                        ToggleRow(
                            "Override project default",
                            draft.conductor != null,
                            { draft = draft.copy(conductor = if (it) ctx.project.design.conductor else null) },
                            if (draft.conductor == null) "Using ${ctx.project.design.conductor.material.label}, ${ctx.project.design.conductor.insulation.code}" else null,
                        )
                        draft.conductor?.let { spec -> ConductorFields(spec) { draft = draft.copy(conductor = it) } }
                    }
                }

                OutlinedTextField(
                    value = draft.note,
                    onValueChange = { draft = draft.copy(note = it) },
                    label = { Text("Note") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (draft.kind == CircuitKind.LOAD) {
                FormSection("Design result") { DesignResult(design) }
            }

            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isNew && !ctx.readOnly) {
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(if (ctx.readOnly) "Close" else "Cancel") }
                    if (!ctx.readOnly) {
                        Button(
                            enabled = canSave,
                            onClick = {
                                val saved = when (effective.kind) {
                                    CircuitKind.SPACE -> effective.copy(breakerAmps = null, loadVa = null, neutral = false, continuous = false, runIds = emptyList())
                                    CircuitKind.SPARE -> effective.copy(loadVa = null, continuous = false)
                                    CircuitKind.LOAD -> effective
                                }
                                ctx.edit { p, now -> p.upsertCircuit(panelId, saved, now) }
                                onDismiss()
                            },
                        ) { Text("Save") }
                    }
                }
            }
        }
    }

    if (newGroupPrompt) {
        TextInputDialog("New group", "", "Create", { name ->
            val group = ctx.project.newGroup(name)
            ctx.edit { p, now -> p.addGroup(group, now) }
            draft = draft.copy(groupIds = draft.groupIds + group.id)
            newGroupPrompt = false
        }, { newGroupPrompt = false }, label = "e.g. Kitchen, RTU-3")
    }
    if (newRunPrompt) {
        TextInputDialog("New conduit run", "", "Create", { name ->
            val run = Run(name = name.ifBlank { "Conduit run" }, raceway = Raceway())
            ctx.edit { p, now -> p.addRun(run, now) }
            draft = draft.copy(runIds = draft.runIds + run.id)
            newRunPrompt = false
        }, { newRunPrompt = false }, label = "e.g. Home run to RTU-3")
    }
    if (confirmDelete) {
        ConfirmDialog("Delete this circuit?", "Removes ${draft.description.ifBlank { "the circuit" }} at ${draft.slots.joinToString(",")} from ${panel.name}.", "Delete", onConfirm = {
            ctx.edit { p, now -> p.deleteCircuit(draft.id, now) }
            confirmDelete = false
            onDismiss()
        }, onDismiss = { confirmDelete = false })
    }
}

private fun voltsText(panel: Panel, poles: Int): String {
    val v = when {
        poles <= 1 -> panel.system.lineToNeutral ?: panel.system.lineToLine
        else -> panel.system.lineToLine
    }
    return "$v V" + if (poles >= 3 && panel.system.phases == 3) " three-phase" else ""
}

@Composable
private fun ReadOnlySummary(c: Circuit, panel: Panel) {
    val lines = listOfNotNull(
        "Position: ${slotLabel(panel, c.slot, c.poles)}",
        "Kind: ${c.kind.label}",
        c.breakerAmps?.let { "Breaker: $it A, ${c.poles}-pole" },
        c.description.takeIf { it.isNotBlank() }?.let { "Description: $it" },
        c.loadVa?.let { "Load: ${Wire.va(it)}" },
        "Continuous: ${if (c.continuous) "yes" else "no"} · Neutral: ${if (c.neutral) "yes" else "no"} · Nonlinear: ${if (c.nonlinear) "yes" else "no"}",
        c.note.takeIf { it.isNotBlank() }?.let { "Note: $it" },
    )
    lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
internal fun ConductorFields(spec: ConductorSpec, onChange: (ConductorSpec) -> Unit) {
    ChoiceField("Material", ConductorMaterial.entries, spec.material, { onChange(spec.copy(material = it)) }, optionLabel = { it.label })
    ChoiceField("Insulation", InsulationType.entries, spec.insulation, { onChange(spec.copy(insulation = it)) }, optionLabel = { it.label })
    ChoiceField(
        "Insulation temperature column",
        TemperatureRating.entries,
        spec.insulationRating,
        { onChange(spec.copy(insulationRating = it)) },
        optionLabel = { it.label },
        supportingText = "THHN, THWN-2 and XHHW-2 are 90 °C.",
    )
    ChoiceField(
        "Termination temperature column",
        TemperatureRating.entries,
        spec.terminationRating,
        { onChange(spec.copy(terminationRating = it)) },
        optionLabel = { it.label },
        supportingText = "Equipment terminals are usually 75 °C (NEC 110.14(C)).",
    )
}

/** The engine's answer for a circuit: the wire, every figure behind it, the governing rule and notes. */
@Composable
internal fun DesignResult(d: CircuitDesign?) {
    if (d == null) {
        Text("Nothing to size yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val sizing = d.sizing
    val rows = buildList {
        add(ResultValue.text("wire", "Wire", Wire.text(d) ?: "—"))
        d.amps?.let { add(ResultValue.text("amps", "Load current", Wire.amps(it))) }
        d.minimumSize?.let { add(ResultValue.text("min", "Minimum size (ampacity, protection)", it.label)) }
        d.size?.let { add(ResultValue.text("size", "Final size (after voltage drop)", it.label)) }
        d.groundSize?.let { add(ResultValue.text("ground", "Equipment ground", it.label)) }
        add(ResultValue.text("ccc", "Current-carrying conductors", d.currentCarrying.toString()))
        sizing?.let {
            add(ResultValue.text("ambient", "Ambient used", "${Wire.number(it.effectiveAmbientC)} °C"))
            it.ambientFactor?.let { f -> add(ResultValue.text("af", "Ambient correction", Wire.number(f, 2))) }
            add(ResultValue.text("adj", "Conductor-count adjustment", Wire.number(it.adjustmentFactor, 2)))
            it.adjustedAmpacity?.let { a -> add(ResultValue.text("adjamp", "Adjusted ampacity", Wire.amps(a))) }
            it.terminationAmpacity?.let { a -> add(ResultValue.text("term", "Termination ampacity", "$a A")) }
        }
        if (d.voltageDropPercent != null && d.voltageDropVolts != null) {
            add(ResultValue.text("vd", "Voltage drop", "${Wire.percent(d.voltageDropPercent!!)} (${Wire.number(d.voltageDropVolts!!, 1)} V)"))
        }
    }
    val notices = buildList {
        sizing?.governing?.takeIf { it.isNotBlank() }?.let { add(Notice("Governing rule: $it", NoticeLevel.INFO)) }
        d.notes.forEach { add(Notice(it, if (isProblem(it)) NoticeLevel.WARNING else NoticeLevel.INFO)) }
    }
    ResultCard(rows, notices = notices)
}

private fun isProblem(note: String): Boolean {
    val n = note.lowercase()
    return listOf("exceed", "over ", "too many", "no table", "can't", "cannot", "not ").any { it in n }
}

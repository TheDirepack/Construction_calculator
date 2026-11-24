package com.thedirepack.constructioncalc.feature.panels

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConduitTable
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.InsulationType
import com.thedirepack.constructioncalc.core.electrical.StandardOcpd
import com.thedirepack.constructioncalc.core.electrical.TemperatureRating
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.electrical.code.codePercent
import com.thedirepack.constructioncalc.core.measure.Measure
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.Circuit
import com.thedirepack.constructioncalc.core.model.CircuitKind
import com.thedirepack.constructioncalc.core.model.ConductorSpec
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.model.Raceway
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.model.Run
import com.thedirepack.constructioncalc.core.model.ToolTarget
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.model.design.CircuitDesign
import com.thedirepack.constructioncalc.core.model.design.PanelLayout
import com.thedirepack.constructioncalc.core.model.design.ProjectAnalysis
import com.thedirepack.constructioncalc.core.model.design.PublishedValues
import com.thedirepack.constructioncalc.core.model.design.Wire
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.core.ui.components.AdvancedSection
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
import com.thedirepack.constructioncalc.core.ui.components.ToggleRow
import com.thedirepack.constructioncalc.core.ui.components.racewaySummary
import com.thedirepack.constructioncalc.core.ui.formatDecimal
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.show
import com.thedirepack.constructioncalc.core.ui.toDecimalOrNull
import com.thedirepack.constructioncalc.core.ui.tool.ToolIds
import com.thedirepack.constructioncalc.feature.panels.domain.LoadMath
import com.thedirepack.constructioncalc.feature.panels.domain.addGroup
import com.thedirepack.constructioncalc.feature.panels.domain.circuitWireSummaryPill
import com.thedirepack.constructioncalc.feature.panels.domain.deleteCircuit
import com.thedirepack.constructioncalc.feature.panels.domain.designRuns
import com.thedirepack.constructioncalc.feature.panels.domain.freeSlots
import com.thedirepack.constructioncalc.feature.panels.domain.newGroup
import com.thedirepack.constructioncalc.feature.panels.domain.supportedPoles
import com.thedirepack.constructioncalc.feature.panels.domain.upsertCircuit
import com.thedirepack.constructioncalc.feature.panels.domain.withUpsizeForVoltageDrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class LoadUnit(val label: String) { VA("VA"), AMPS("Amps") }

private val QUICK_BREAKER_AMPS = listOf(15, 20, 30, 40, 50, 60)

private data class CircuitAnalysisState(
    val design: CircuitDesign? = null,
    val upsizePreview: CircuitDesign? = null,
)

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
    var showInlineConduit by remember {
        mutableStateOf(initial.conduitTag.isNotBlank() || initial.conduitType != null || initial.conduitSize != null)
    }
    var moreAmpsMenu by remember { mutableStateOf(false) }
    val settings = rememberAppSettings()
    val length = rememberLengthFormatter()

    val vaValue = loadText.toDecimalOrNull()?.let { if (loadUnit == LoadUnit.VA) it else LoadMath.va(panel.system, draft.poles, it) }
    val effective = draft.copy(loadVa = if (draft.kind == CircuitKind.LOAD) vaValue else null)
    // The whole-project analysis (shared runs, feeders) is heavy: run it off the main thread, a beat
    // after typing stops, and cancel a draft's analysis as soon as the next keystroke supersedes it.
    // The last result stays on screen meanwhile.
    val analysisState: CircuitAnalysisState by produceState(CircuitAnalysisState(), ctx.project, panelId, effective, settings.unitSystem) {
        if (value.design != null) delay(150)
        value = withContext(Dispatchers.Default) {
            val currentDesign = ProjectAnalysis.analyze(ctx.project.upsertCircuit(panelId, effective, 0L), settings.unitSystem).circuits[effective.id]
            val target = ctx.project.design.voltageDropTargetPercent
            val vd = currentDesign?.voltageDropPercent
            val preview = if (
                effective.kind == CircuitKind.LOAD &&
                vd != null &&
                vd > target + 1e-9 &&
                !ctx.project.design.upsizeForVoltageDrop
            ) {
                ProjectAnalysis.analyze(
                    ctx.project.withUpsizeForVoltageDrop(true, 0L).upsertCircuit(panelId, effective, 0L),
                    settings.unitSystem,
                ).circuits[effective.id]
            } else {
                null
            }
            CircuitAnalysisState(design = currentDesign, upsizePreview = preview)
        }
    }
    val design = analysisState.design
    val upsizePreview = analysisState.upsizePreview
    LaunchedEffect(design) {
        design?.let { d ->
            val values = PublishedValues.circuit(d, length)
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

    fun normalizedForSave(): Circuit = when (effective.kind) {
        CircuitKind.SPACE -> effective.copy(
            breakerAmps = null,
            loadVa = null,
            neutral = false,
            continuous = false,
            runIds = emptyList(),
            conduitTag = "",
            conduitType = null,
            conduitSize = null,
        )
        CircuitKind.SPARE -> effective.copy(loadVa = null, continuous = false)
        CircuitKind.LOAD -> effective
    }

    val free = freeSlots(panel, draft.poles, draft.id)
    val canSave = draft.slot in free
    val title = if (isNew) "New circuit" else "Circuit ${draft.slots.joinToString(",")}"
    val targetVd = ctx.project.design.voltageDropTargetPercent
    val vdWarn = if (draft.kind == CircuitKind.LOAD) design?.voltageDropPercent?.takeIf { it > targetVd + 1e-9 } else null
    val poleOptions = (supportedPoles(panel, draft.id) + draft.poles).distinct().sorted()
    val summaryPill = circuitWireSummaryPill(panel, effective, design)
    val advancedSummary = buildList {
        if (draft.kind != CircuitKind.LOAD) add(draft.kind.label)
        add("Pos ${draft.slots.joinToString(",")}")
        if (draft.kind == CircuitKind.LOAD && draft.continuous) add("Continuous")
        if (draft.kind == CircuitKind.LOAD && draft.nonlinear) add("Nonlinear")
        val runNames = draft.runIds.mapNotNull { ctx.project.run(it)?.name }
        if (runNames.isNotEmpty()) {
            add(runNames.joinToString(", "))
        } else if (draft.conduitTag.isNotBlank()) {
            add(draft.conduitTag)
        }
        draft.length?.let { add(length(it.toQuantity())) }
        draft.conductor?.let { add("${it.material.shortLabel} ${it.insulation.code}") }
        if (draft.groupIds.isNotEmpty()) add(plural(draft.groupIds.size, "group"))
        if (draft.note.isNotBlank()) add("Note")
    }.joinToString(" · ")

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                "${panel.name} · Pos ${draft.slots.joinToString(",")} · ${panel.system.label}" + if (ctx.readOnly) " · archived project, read-only" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (vdWarn != null) {
                VoltageDropWarningBanner(
                    vdPercent = vdWarn,
                    targetPercent = targetVd,
                    currentSizeLabel = design?.size?.label,
                    upsizeEnabled = ctx.project.design.upsizeForVoltageDrop,
                    upsizePreview = upsizePreview,
                    readOnly = ctx.readOnly,
                    onUpsize = {
                        ctx.edit { p, now -> p.withUpsizeForVoltageDrop(true, now) }
                    },
                    onOpenVoltageDrop = {
                        if (!ctx.readOnly && canSave) {
                            ctx.edit { p, now -> p.upsertCircuit(panelId, normalizedForSave(), now) }
                        }
                        onDismiss()
                        ctx.host.openTool(ToolIds.VOLTAGE_DROP, target = ToolTarget.OfCircuit(ctx.project.id, draft.id))
                    },
                )
            }

            if (ctx.readOnly) {
                ReadOnlySummary(effective, panel)
                if (draft.kind == CircuitKind.LOAD) {
                    AdvancedSection(
                        title = "Advanced circuit details",
                        summary = "Design result",
                    ) {
                        FormSection("Design result") { DesignResult(design) }
                    }
                }
            } else {
                OutlinedTextField(
                    value = draft.description,
                    onValueChange = { draft = draft.copy(description = it) },
                    label = { Text("Description") },
                    placeholder = { Text("e.g. Kitchen receptacles, RTU-1") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (draft.kind != CircuitKind.SPACE) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Breaker amps", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            QUICK_BREAKER_AMPS.forEach { amps ->
                                FilterChip(
                                    selected = draft.breakerAmps == amps,
                                    onClick = { draft = draft.copy(breakerAmps = amps) },
                                    label = { Text("$amps A") },
                                )
                            }
                            val selectedCustomAmp = draft.breakerAmps?.takeIf { it !in QUICK_BREAKER_AMPS }
                            Box {
                                FilterChip(
                                    selected = selectedCustomAmp != null,
                                    onClick = { moreAmpsMenu = true },
                                    label = { Text(selectedCustomAmp?.let { "$it A ▾" } ?: "More…") },
                                )
                                DropdownMenu(expanded = moreAmpsMenu, onDismissRequest = { moreAmpsMenu = false }) {
                                    StandardOcpd.ratings.forEach { amps ->
                                        DropdownMenuItem(
                                            text = { Text("$amps A") },
                                            onClick = {
                                                draft = draft.copy(breakerAmps = amps)
                                                moreAmpsMenu = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (poleOptions.size > 1) {
                    SegmentedChoice(
                        options = poleOptions,
                        selected = draft.poles,
                        onSelect = { retarget(poles = it) },
                        label = "Poles",
                        optionLabel = { "$it-pole" },
                    )
                }

                if (draft.kind == CircuitKind.LOAD) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        NumberField(
                            label = if (loadUnit == LoadUnit.VA) "Load (optional)" else "Load current (optional)",
                            value = loadText,
                            onValueChange = { loadText = it },
                            unit = if (loadUnit == LoadUnit.VA) "VA" else "A",
                            pickKinds = if (loadUnit == LoadUnit.VA) setOf(ValueKind.APPARENT_POWER, ValueKind.POWER) else setOf(ValueKind.CURRENT),
                            supportingText = vaValue?.let {
                                val amps = LoadMath.amps(panel.system, draft.poles, it)
                                if (loadUnit == LoadUnit.VA) "= ${Wire.amps(amps)} at ${voltsText(panel, draft.poles)}" else "= ${Wire.va(it)} at ${voltsText(panel, draft.poles)}"
                            } ?: design?.takeIf { it.loadFromFedPanels }?.loadVa?.let {
                                "Sized for fed panels (${Wire.va(it)})."
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Row(
                            Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            LoadUnit.entries.forEach { unit ->
                                FilterChip(
                                    selected = loadUnit == unit,
                                    onClick = {
                                        if (unit != loadUnit) {
                                            loadText = when {
                                                vaValue == null -> loadText
                                                unit == LoadUnit.VA -> formatDecimal(vaValue, 1)
                                                else -> formatDecimal(LoadMath.amps(panel.system, draft.poles, vaValue), 2)
                                            }
                                            loadUnit = unit
                                        }
                                    },
                                    label = { Text(unit.label) },
                                )
                            }
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = if (vdWarn != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                    border = BorderStroke(
                        1.dp,
                        if (vdWarn != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) {
                    Text(
                        summaryPill,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = if (vdWarn != null) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }

                AdvancedSection(
                    title = "Advanced circuit details",
                    summary = advancedSummary,
                    initiallyExpanded = false,
                ) {
                    FormSection("Position & type") {
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
                        ChoiceField(
                            label = "Position",
                            options = if (draft.slot in free) free else listOf(draft.slot) + free,
                            selected = draft.slot,
                            onSelect = { draft = draft.copy(slot = it) },
                            optionLabel = { slotLabel(panel, it, draft.poles) },
                            supportingText = if (free.isEmpty()) "No room for a ${draft.poles}-pole breaker in ${panel.name}." else null,
                        )
                    }

                    if (draft.kind == CircuitKind.LOAD) {
                        FormSection("Load characteristics") {
                            ToggleRow("Continuous load", draft.continuous, { draft = draft.copy(continuous = it) }, "On for 3 hours or more: wire and breaker sized at ${codePercent(Code.book.values.continuousLoadFactor, spaced = false)}.")
                            if (panel.system.lineToNeutral != null) {
                                ToggleRow("Neutral conductor", draft.neutral, { draft = draft.copy(neutral = it) }, "Pulled with the circuit and counted when it carries current.")
                            }
                            ToggleRow("Nonlinear load", draft.nonlinear, { draft = draft.copy(nonlinear = it) }, "Electronic loads: the neutral counts as current-carrying.")
                            ToggleRow(
                                "Multiple cord-and-plug receptacles",
                                draft.multipleCordReceptacles,
                                { draft = draft.copy(multipleCordReceptacles = it) },
                                "Affects the minimum wire size (NEC ${CodeRule.BRANCH_CONDUCTOR_SIZE.cite}).",
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
                            if (draft.runIds.isEmpty()) {
                                ToggleRow(
                                    "Inline conduit details (optional)",
                                    showInlineConduit,
                                    { on ->
                                        showInlineConduit = on
                                        if (!on) draft = draft.copy(conduitTag = "", conduitType = null, conduitSize = null)
                                    },
                                    "Conduit tag, type & size without creating a full Run.",
                                )
                                if (showInlineConduit) {
                                    OutlinedTextField(
                                        value = draft.conduitTag,
                                        onValueChange = { draft = draft.copy(conduitTag = it) },
                                        label = { Text("Conduit tag / number") },
                                        placeholder = { Text("e.g. C-1, HR-12") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    ChoiceField<ConduitType?>(
                                        label = "Conduit type",
                                        options = listOf<ConduitType?>(null) + ConduitType.entries,
                                        selected = draft.conduitType,
                                        onSelect = { type ->
                                            val validSize = if (type != null && draft.conduitSize != null && ConduitTable.find(type, draft.conduitSize!!) == null) {
                                                null
                                            } else {
                                                draft.conduitSize
                                            }
                                            draft = draft.copy(conduitType = type, conduitSize = validSize)
                                        },
                                        optionLabel = { it?.displayName ?: "Not set (PVC for voltage drop)" },
                                    )
                                    ChoiceField<TradeSize?>(
                                        label = "Conduit size",
                                        options = listOf<TradeSize?>(null) + (draft.conduitType?.let(ConduitTable::tradeSizes) ?: TradeSize.entries),
                                        selected = draft.conduitSize,
                                        onSelect = { draft = draft.copy(conduitSize = it) },
                                        optionLabel = { it?.display ?: "Not set" },
                                    )
                                }
                            }
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

                    if (draft.kind == CircuitKind.LOAD) {
                        FormSection("Design result") { DesignResult(design) }
                    }
                }
            }

            if (!isNew && draft.kind == CircuitKind.LOAD) {
                OutlinedButton(onClick = {
                    onDismiss()
                    ctx.host.openTool(ToolIds.VOLTAGE_DROP, target = ToolTarget.OfCircuit(ctx.project.id, draft.id))
                }) { Text("Voltage drop for this circuit…") }
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
                                ctx.edit { p, now -> p.upsertCircuit(panelId, normalizedForSave(), now) }
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
private fun VoltageDropWarningBanner(
    vdPercent: Double,
    targetPercent: Double,
    currentSizeLabel: String?,
    upsizeEnabled: Boolean,
    upsizePreview: CircuitDesign?,
    readOnly: Boolean,
    onUpsize: () -> Unit,
    onOpenVoltageDrop: () -> Unit,
) {
    val previewResolves = upsizePreview?.voltageDropPercent?.let { it <= targetPercent + 1e-9 } == true
    val previewDetail = when {
        !upsizeEnabled && upsizePreview?.size != null -> {
            val dropText = upsizePreview.voltageDropPercent?.let { " (${Wire.percent(it)} drop)" }.orEmpty()
            "Current wire: ${currentSizeLabel ?: "—"}. Tap Upsize wire to step up to ${upsizePreview.size!!.label}$dropText."
        }
        !upsizeEnabled -> "Enable voltage-drop upsizing to step up conductor size automatically."
        else -> "Auto-upsize reached limit at ${currentSizeLabel ?: "—"}. Open Voltage Drop for parallel sets or larger cable."
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "⚠ Voltage drop ${Wire.percent(vdPercent)} exceeds ${Wire.percent(targetPercent)} target",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                previewDetail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (!readOnly) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!upsizeEnabled) {
                        Button(onClick = onUpsize) { Text("Upsize wire") }
                        if (!previewResolves && upsizePreview != null) {
                            OutlinedButton(onClick = onOpenVoltageDrop) { Text("Open Voltage Drop") }
                        }
                    } else {
                        Button(onClick = onOpenVoltageDrop) { Text("Open Voltage Drop") }
                    }
                }
            }
        }
    }
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
        if (c.runIds.isEmpty()) {
            listOfNotNull(
                c.conduitTag.trim().takeIf { it.isNotEmpty() },
                listOfNotNull(c.conduitSize?.display, c.conduitType?.displayName).takeIf { it.isNotEmpty() }?.joinToString(" "),
            ).takeIf { it.isNotEmpty() }?.let { "Inline conduit: ${it.joinToString(" ")}" }
        } else {
            null
        },
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
        supportingText = "Equipment terminals are usually 75 °C (NEC ${CodeRule.TERMINATION_TEMPERATURE.cite}).",
    )
}

/** The engine's answer for a circuit: the wire, every figure behind it, the governing rule and notes. */
@Composable
internal fun DesignResult(d: CircuitDesign?) {
    val settings = rememberAppSettings()
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
            add(ResultValue.text("ambient", "Ambient used", settings.show(Measure.TEMPERATURE, it.effectiveAmbientC, 1)))
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
        d.notes.forEach { add(Notice(it.text, if (it.isWarning) NoticeLevel.WARNING else NoticeLevel.INFO)) }
    }
    ResultCard(rows, notices = notices)
}


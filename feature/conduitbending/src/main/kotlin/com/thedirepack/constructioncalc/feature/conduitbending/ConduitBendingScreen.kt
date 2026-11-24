package com.thedirepack.constructioncalc.feature.conduitbending

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Dimension
import com.thedirepack.constructioncalc.core.measure.MeasureFormat
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.Raceway
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.core.model.ToolTarget
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.model.design.ProjectAnalysis
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.components.AdvancedSection
import com.thedirepack.constructioncalc.core.ui.components.ChoiceField
import com.thedirepack.constructioncalc.core.ui.components.MeasureField
import com.thedirepack.constructioncalc.core.ui.components.MeasureInputSheet
import com.thedirepack.constructioncalc.core.ui.components.Notice
import com.thedirepack.constructioncalc.core.ui.components.NoticeLevel
import com.thedirepack.constructioncalc.core.ui.components.NumberField
import com.thedirepack.constructioncalc.core.ui.components.ResultCard
import com.thedirepack.constructioncalc.core.ui.components.SegmentedChoice
import com.thedirepack.constructioncalc.core.ui.components.ToolScaffold
import com.thedirepack.constructioncalc.core.ui.format
import com.thedirepack.constructioncalc.core.ui.formatDecimal
import com.thedirepack.constructioncalc.core.ui.formatInches
import com.thedirepack.constructioncalc.core.ui.fractionText
import com.thedirepack.constructioncalc.core.ui.lengthFormat
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.spokenText
import com.thedirepack.constructioncalc.core.ui.toDecimalOrNull
import com.thedirepack.constructioncalc.core.ui.tool.ItemDraft
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.core.ui.tool.toolViewModel
import com.thedirepack.constructioncalc.core.ui.tradeSizeLabel
import com.thedirepack.constructioncalc.feature.conduitbending.domain.BendType
import com.thedirepack.constructioncalc.feature.conduitbending.domain.Bendable
import com.thedirepack.constructioncalc.feature.conduitbending.domain.DistanceAnchor
import com.thedirepack.constructioncalc.feature.conduitbending.domain.Figure
import com.thedirepack.constructioncalc.feature.conduitbending.domain.FigureKind
import com.thedirepack.constructioncalc.feature.conduitbending.domain.HookDirection
import com.thedirepack.constructioncalc.feature.conduitbending.domain.PathBends
import com.thedirepack.constructioncalc.feature.conduitbending.domain.Pipe3DGeometry
import com.thedirepack.constructioncalc.feature.conduitbending.domain.Severity
import com.thedirepack.constructioncalc.feature.conduitbending.domain.StickBendItem
import com.thedirepack.constructioncalc.feature.conduitbending.domain.StickBendResult
import com.thedirepack.constructioncalc.feature.conduitbending.domain.StickMark
import com.thedirepack.constructioncalc.feature.conduitbending.domain.StickPlan
import com.thedirepack.constructioncalc.feature.conduitbending.domain.StickPlanner
import com.thedirepack.constructioncalc.feature.conduitbending.domain.fmt
import com.thedirepack.constructioncalc.feature.conduitbending.domain.normalizeRotationDeg
import java.util.UUID
import kotlin.math.abs

@Composable
fun ConduitBendingScreen(host: ToolHost) {
    val vm: ConduitBendingViewModel = toolViewModel(ConduitBendingTool.ID, host)
    val projectsRepo = LocalAppServices.current.projects
    LaunchedEffect(host.target) {
        (host.target as? ToolTarget.OfRun)?.let { t ->
            vm.prefill(t) { projectsRepo.get(t.projectId)?.let { bendingInputsForRun(it, t.runId, vm.inputs) } }
        }
    }

    val settings = rememberAppSettings()
    val store = LocalAppServices.current.settings.module(ConduitBendingTool.ID)
    val version by store.changes.collectAsStateWithLifecycle()
    val table = remember(version) { BenderStore.load(store) }

    val inputs = vm.inputs
    val bends = remember(inputs) { inputs.effectiveBends() }
    val stickPlan = remember(inputs, table, settings) {
        StickPlanner.plan(inputs.toStick(table, settings.lengthFormat()))
    }
    val path3d = remember(stickPlan) { Pipe3DGeometry.build(stickPlan) }
    val results = remember(stickPlan, settings) { stickPlan.toResults(settings) }
    val summary = remember(stickPlan, settings) { stickPlan.summary(settings) }

    var expandedBendId by rememberSaveable { mutableStateOf<String?>(bends.firstOrNull()?.id) }
    LaunchedEffect(bends) {
        if (expandedBendId != null && bends.none { it.id == expandedBendId }) {
            expandedBendId = bends.firstOrNull()?.id
        }
    }

    LaunchedEffect(results) {
        if (results.isNotEmpty()) host.publish(results, context = summary)
    }

    ToolScaffold(
        title = "Conduit Bending",
        host = host,
        onSave = if (!stickPlan.hasResults) null else ({
            val title = if (bends.size == 1) {
                bends.first().type.label
            } else {
                "${bends.size}-bend stick (${stickPlan.totalDegrees.fmt()}°)"
            }
            host.saveToProject(
                ItemDraft(
                    toolId = ConduitBendingTool.ID,
                    title = title,
                    inputs = ToolInputs.encode(inputs),
                    results = results,
                    summary = summary,
                    conduit = Raceway(conduitType = inputs.conduit, tradeSize = inputs.tradeSize),
                    pathBends = PathBends.of(stickPlan),
                ),
            )
        }),
        actions = {
            IconButton(onClick = host::openSettings) {
                Icon(painterResource(R.drawable.ic_tune), contentDescription = "Bender settings")
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            PinnedStickHeaderCard(
                settings = settings,
                inputs = inputs,
                plan = stickPlan,
                path3d = path3d,
                onUpdate = vm::update,
            )

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (bends.isEmpty()) {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Text(
                            "No bends on this stick yet. Tap \"+ Add bend\" below to start laying out your conduit.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                } else {
                    bends.forEachIndexed { index, item ->
                        val result = stickPlan.bendResults.getOrNull(index)
                        StickBendCard(
                            item = item,
                            index = index,
                            totalCount = bends.size,
                            result = result,
                            expanded = expandedBendId == item.id,
                            settings = settings,
                            onToggleExpand = {
                                expandedBendId = if (expandedBendId == item.id) null else item.id
                            },
                            onChange = { updated ->
                                vm.update { cur ->
                                    val list = cur.effectiveBends().toMutableList()
                                    if (index in list.indices) list[index] = updated
                                    cur.withBends(list)
                                }
                            },
                            onMove = { delta ->
                                val target = index + delta
                                if (target in bends.indices) {
                                    vm.update { cur ->
                                        val list = cur.effectiveBends().toMutableList()
                                        val moved = list.removeAt(index)
                                        list.add(target, moved)
                                        cur.withBends(list)
                                    }
                                }
                            },
                            onRemove = {
                                vm.update { cur ->
                                    val list = cur.effectiveBends().filterIndexed { i, _ -> i != index }
                                    cur.withBends(list)
                                }
                                if (expandedBendId == item.id) {
                                    expandedBendId = bends.firstOrNull { it.id != item.id }?.id
                                }
                            },
                        )
                    }
                }

                FilledTonalButton(
                    onClick = {
                        val newItem = StickBendItem(
                            id = UUID.randomUUID().toString(),
                            type = BendType.OFFSET,
                            distanceAnchor = if (bends.isEmpty()) DistanceAnchor.FROM_END_A else DistanceAnchor.FROM_PREVIOUS,
                            hookDirection = HookDirection.TOWARD_END_A,
                        )
                        vm.update { cur -> cur.withBends(cur.effectiveBends() + newItem) }
                        expandedBendId = newItem.id
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add bend")
                }

                val tailSummary = inputs.tailLength?.toQuantity()?.let { "Tail ${settings.format(it)}" } ?: "No trailing tail"
                AdvancedSection(
                    title = "Cut tail & stick options",
                    summary = "${settings.formatInches(inputs.effectiveStickLength)} stick · $tailSummary",
                ) {
                    MeasureField(
                        label = "Stock stick length",
                        value = inputs.stickLength?.toQuantity() ?: Quantity.inches(StickBendItem.DEFAULT_STICK_LENGTH_IN),
                        onValueChange = { q -> vm.update { it.copy(stickLength = q?.stored()) } },
                        supportingText = "Defaults to 10' (120\"). Clear to restore 10'.",
                    )
                    MeasureField(
                        label = "Straight run past last bend (optional)",
                        value = inputs.tailLength?.toQuantity(),
                        onValueChange = { q -> vm.update { it.copy(tailLength = q?.stored()) } },
                        supportingText = "Added to developed length for total cut length.",
                    )
                }

                if (stickPlan.marks.isNotEmpty()) {
                    TapeMarkingSheetCard(stickPlan, settings)
                }

                ResultCard(
                    results = stickPlan.toSummaryResults(settings),
                    notices = stickPlan.issues.map { Notice(it.text, it.severity.toLevel()) },
                    emptyText = "Enter bend measurements above to see stick marks and cut length.",
                )
            }
        }
    }
}

@Composable
private fun PinnedStickHeaderCard(
    settings: AppSettings,
    inputs: ConduitBendingInputs,
    plan: StickPlan,
    path3d: com.thedirepack.constructioncalc.feature.conduitbending.domain.Pipe3DPath,
    onUpdate: ((ConduitBendingInputs) -> ConduitBendingInputs) -> Unit,
) {
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val canvasHeight = if (screenHeightDp < 600) 108.dp else 134.dp
    var showTypeMenu by remember { mutableStateOf(false) }
    var showSizeMenu by remember { mutableStateOf(false) }
    var editingStickLen by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Compact Conduit / Size / Stick-Length row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    AssistChip(
                        onClick = { showTypeMenu = true },
                        label = { Text("${inputs.conduit.displayName} ▾") },
                    )
                    DropdownMenu(expanded = showTypeMenu, onDismissRequest = { showTypeMenu = false }) {
                        Bendable.types.forEach { type ->
                            DropdownMenuItem(
                                text = { Text("${type.displayName} — ${type.fullName}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                onClick = {
                                    onUpdate {
                                        it.copy(
                                            conduitType = type.code,
                                            conduitSize = Bendable.sizeOrNearest(type, it.tradeSize).label,
                                        )
                                    }
                                    showTypeMenu = false
                                },
                            )
                        }
                    }
                }

                Box {
                    AssistChip(
                        onClick = { showSizeMenu = true },
                        label = { Text(fractionText("${settings.tradeSizeLabel(inputs.tradeSize)} ▾")) },
                    )
                    DropdownMenu(expanded = showSizeMenu, onDismissRequest = { showSizeMenu = false }) {
                        Bendable.sizes(inputs.conduit).forEach { size ->
                            DropdownMenuItem(
                                text = { Text(fractionText(settings.tradeSizeLabel(size))) },
                                onClick = {
                                    onUpdate { it.copy(conduitSize = size.label) }
                                    showSizeMenu = false
                                },
                            )
                        }
                    }
                }

                AssistChip(
                    onClick = { editingStickLen = true },
                    label = { Text(fractionText("Stick: ${settings.formatInches(inputs.effectiveStickLength)}")) },
                )
            }

            Pipe3DPreview(path = path3d, plan = plan, canvasHeight = canvasHeight)

            // Stick Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatCell(
                    label = if (plan.tailLengthIn != null) "Cut Length" else "Developed",
                    value = if (plan.hasResults) settings.formatInches(plan.cutLengthIn) else "—",
                    highlightError = plan.overflowsStick,
                    modifier = Modifier.weight(1f),
                )
                StatCell(
                    label = if (plan.overflowsStick) "Overflow" else "Remaining",
                    value = if (!plan.hasResults) {
                        settings.formatInches(plan.stickLengthIn)
                    } else if (plan.overflowsStick) {
                        "+${settings.formatInches(plan.overflowAmountIn)}"
                    } else {
                        settings.formatInches(plan.remainingStraightIn)
                    },
                    highlightError = plan.overflowsStick,
                    modifier = Modifier.weight(1f),
                )
                StatCell(
                    label = "Total Bend",
                    value = "${plan.totalDegrees.fmt()}° / 360°",
                    highlightError = plan.exceeds360Degrees,
                    modifier = Modifier.weight(1f),
                )
            }

            // Compact inline warning banner when any key limit triggers
            val banner = when {
                plan.issues.any { it.severity == Severity.ERROR } ->
                    plan.issues.first { it.severity == Severity.ERROR }.text to true
                plan.overflowsStick ->
                    "Exceeds ${settings.formatInches(plan.stickLengthIn)} stick by ${settings.formatInches(plan.overflowAmountIn)} — coupling needed." to false
                plan.exceeds360Degrees ->
                    "Total ${plan.totalDegrees.fmt()}° exceeds 360° NEC limit — add a pull point." to false
                plan.hasShoeClearanceWarning ->
                    plan.issues.firstOrNull { it.text.contains("clearance", ignoreCase = true) || it.text.contains("close", ignoreCase = true) || it.text.contains("overlap", ignoreCase = true) }?.text.orEmpty() to false
                else -> null
            }
            if (banner != null && banner.first.isNotEmpty()) {
                val (msg, isError) = banner
                Text(
                    "⚠ $msg",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    if (editingStickLen) {
        MeasureInputSheet(
            title = "Stock stick length",
            initial = inputs.stickLength?.toQuantity() ?: Quantity.inches(StickBendItem.DEFAULT_STICK_LENGTH_IN),
            allowed = setOf(Dimension.LENGTH),
            onDone = { q ->
                onUpdate { it.copy(stickLength = q.stored()) }
                editingStickLen = false
            },
            onDismiss = { editingStickLen = false },
        )
    }
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    highlightError: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            fractionText(value),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (highlightError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StickBendCard(
    item: StickBendItem,
    index: Int,
    totalCount: Int,
    result: StickBendResult?,
    expanded: Boolean,
    settings: AppSettings,
    onToggleExpand: () -> Unit,
    onChange: (StickBendItem) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val summaryTitle = bendCardTitle(item, index, settings)
    val markSummary = result?.marks?.takeIf { it.isNotEmpty() }?.joinToString(" · ") {
        settings.formatInches(it.positionFromEndAIn)
    }
    val hasIssue = result?.issues?.any { it.severity != Severity.INFO } == true

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (expanded) {
                MaterialTheme.colorScheme.surfaceContainerLow
            } else {
                MaterialTheme.colorScheme.surfaceContainerLowest
            },
        ),
    ) {
        Column(Modifier.fillMaxWidth()) {
            // Accordion header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onToggleExpand)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        fractionText(summaryTitle),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (hasIssue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitle = buildString {
                        if (index > 0) {
                            append(item.distanceAnchor.label)
                            item.distance?.toQuantity()?.let { append(" +${settings.format(it)}") }
                        } else {
                            append("From End A")
                        }
                        if (markSummary != null) {
                            append(" · Marks: $markSummary")
                        } else if (!expanded) {
                            append(" · Tap to enter")
                        }
                    }
                    Text(
                        fractionText(subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    if (expanded) "▴" else "▾",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    HorizontalDivider()

                    // Reorder / Remove bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Bend ${index + 1} of $totalCount",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onMove(-1) }, enabled = index > 0) {
                            Text("↑ Up")
                        }
                        TextButton(onClick = { onMove(1) }, enabled = index < totalCount - 1) {
                            Text("↓ Down")
                        }
                        IconButton(onClick = onRemove) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = "Remove bend ${index + 1}")
                        }
                    }

                    ChoiceField(
                        label = "Bend type",
                        options = BendType.entries,
                        selected = item.type,
                        onSelect = { newType ->
                            val defaultHook = if (newType == BendType.STUB_UP && index > 0) {
                                HookDirection.TOWARD_END_B
                            } else {
                                item.hookDirection
                            }
                            onChange(item.copy(type = newType, hookDirection = defaultHook))
                        },
                        optionLabel = { it.label },
                    )

                    if (index > 0) {
                        SegmentedChoice(
                            label = "Distance measured from",
                            options = DistanceAnchor.entries,
                            selected = item.distanceAnchor,
                            onSelect = { anchor -> onChange(item.copy(distanceAnchor = anchor)) },
                            optionLabel = { it.label },
                        )
                    }

                    BendItemMeasurements(item = item, index = index, onChange = onChange)

                    // Axial Rotation 0°-360°
                    AxialRotationEditor(
                        rotationDeg = item.axialRotationDeg,
                        onRotationChange = { deg -> onChange(item.copy(axialRotationDeg = deg)) },
                    )

                    // Hook Direction
                    SegmentedChoice(
                        label = "Bender hook direction",
                        options = HookDirection.entries,
                        selected = item.hookDirection,
                        onSelect = { dir -> onChange(item.copy(hookDirection = dir)) },
                        optionLabel = { it.label },
                    )

                    // Inline metrics & notices for this bend
                    if (result != null && result.hasResults) {
                        val metrics = result.plan.figures.take(3).joinToString(" · ") { fig ->
                            val valText = when (fig.kind) {
                                FigureKind.LENGTH -> settings.format(Quantity.inches(fig.value))
                                FigureKind.ANGLE -> "${formatDecimal(fig.value.toDouble(), 2)}°"
                                FigureKind.RATIO -> formatDecimal(fig.value.toDouble(), 3)
                            }
                            "${fig.label.substringBefore(" (")}: $valText"
                        }
                        if (metrics.isNotEmpty()) {
                            Text(
                                fractionText(metrics),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    result?.issues?.filter { it.severity != Severity.INFO }?.forEach { issue ->
                        Text(
                            "⚠ ${issue.text}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (issue.severity == Severity.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BendItemMeasurements(
    item: StickBendItem,
    index: Int,
    onChange: (StickBendItem) -> Unit,
) {
    val anchorSuffix = if (index > 0 && item.distanceAnchor == DistanceAnchor.FROM_PREVIOUS) "from previous bend" else "from End A"

    when (item.type) {
        BendType.OFFSET -> {
            MeasureField("Offset depth", item.offsetDepth?.toQuantity(), { q -> onChange(item.copy(offsetDepth = q?.stored())) })
            BendAngleSelector(item, onChange)
            MeasureField(
                label = "Distance to obstruction ($anchorSuffix)",
                value = item.distance?.toQuantity(),
                onValueChange = { q -> onChange(item.copy(distance = q?.stored())) },
                supportingText = "Optional. Leave blank to start at 0\" / previous bend.",
            )
            MeasureField(
                label = "Space available (optional)",
                value = item.space?.toQuantity(),
                onValueChange = { q -> onChange(item.copy(space = q?.stored())) },
            )
        }
        BendType.ROLLING_OFFSET -> {
            MeasureField("Rise (vertical)", item.rise?.toQuantity(), { q -> onChange(item.copy(rise = q?.stored())) })
            MeasureField("Roll (sideways)", item.roll?.toQuantity(), { q -> onChange(item.copy(roll = q?.stored())) })
            BendAngleSelector(item, onChange)
            MeasureField(
                label = "Distance to obstruction ($anchorSuffix)",
                value = item.distance?.toQuantity(),
                onValueChange = { q -> onChange(item.copy(distance = q?.stored())) },
            )
            MeasureField(
                label = "Space available (optional)",
                value = item.space?.toQuantity(),
                onValueChange = { q -> onChange(item.copy(space = q?.stored())) },
            )
        }
        BendType.SADDLE_3 -> {
            MeasureField("Obstruction height", item.saddleHeight?.toQuantity(), { q -> onChange(item.copy(saddleHeight = q?.stored())) })
            ChoiceField(
                label = "Center bend",
                options = ConduitBendingInputs.SADDLE_ANGLES,
                selected = item.saddleAngle,
                onSelect = { a -> onChange(item.copy(saddleAngle = a)) },
                optionLabel = { if (it == "45") "45° (outer bends 22.5°)" else "60° (outer bends 30°)" },
            )
            MeasureField(
                label = "Distance to center of obstruction ($anchorSuffix)",
                value = item.distance?.toQuantity(),
                onValueChange = { q -> onChange(item.copy(distance = q?.stored())) },
            )
        }
        BendType.SADDLE_4 -> {
            MeasureField("Obstruction height", item.saddleHeight?.toQuantity(), { q -> onChange(item.copy(saddleHeight = q?.stored())) })
            MeasureField("Obstruction width", item.saddleWidth?.toQuantity(), { q -> onChange(item.copy(saddleWidth = q?.stored())) })
            BendAngleSelector(item, onChange)
            MeasureField(
                label = "Distance to near edge ($anchorSuffix)",
                value = item.distance?.toQuantity(),
                onValueChange = { q -> onChange(item.copy(distance = q?.stored())) },
            )
        }
        BendType.STUB_UP -> {
            if (index == 0) {
                MeasureField(
                    label = "Stub height (to back of 90°)",
                    value = item.stubHeight?.toQuantity(),
                    onValueChange = { q -> onChange(item.copy(stubHeight = q?.stored())) },
                )
            } else {
                MeasureField(
                    label = "Distance to back of 90° ($anchorSuffix)",
                    value = item.distance?.toQuantity(),
                    onValueChange = { q -> onChange(item.copy(distance = q?.stored())) },
                )
                MeasureField(
                    label = "Stub height after bend (optional)",
                    value = item.stubHeight?.toQuantity(),
                    onValueChange = { q -> onChange(item.copy(stubHeight = q?.stored())) },
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Back-to-back 90s", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = item.backToBack, onCheckedChange = { on -> onChange(item.copy(backToBack = on)) })
            }
            if (item.backToBack) {
                MeasureField(
                    label = "Distance between the backs of the two 90s",
                    value = item.between?.toQuantity(),
                    onValueChange = { q -> onChange(item.copy(between = q?.stored())) },
                )
                MeasureField(
                    label = "Second stub height (optional, for cut length)",
                    value = item.secondStub?.toQuantity(),
                    onValueChange = { q -> onChange(item.copy(secondStub = q?.stored())) },
                )
            }
        }
        BendType.KICK -> {
            MeasureField("Kick height", item.kickHeight?.toQuantity(), { q -> onChange(item.copy(kickHeight = q?.stored())) })
            BendAngleSelector(item, onChange)
            MeasureField(
                label = "Distance to kick height point ($anchorSuffix)",
                value = item.distance?.toQuantity(),
                onValueChange = { q -> onChange(item.copy(distance = q?.stored())) },
            )
        }
    }
}

@Composable
private fun BendAngleSelector(
    item: StickBendItem,
    onChange: (StickBendItem) -> Unit,
) {
    ChoiceField(
        label = "Bend angle",
        options = ConduitBendingInputs.ANGLE_CHOICES,
        selected = item.angle,
        onSelect = { a -> onChange(item.copy(angle = a)) },
        optionLabel = { if (it == ConduitBendingInputs.CUSTOM) "Custom" else "$it°" },
    )
    if (item.angle == ConduitBendingInputs.CUSTOM) {
        NumberField(
            label = "Custom angle",
            value = item.customAngle,
            onValueChange = { text -> onChange(item.copy(customAngle = text)) },
            unit = "°",
        )
    }
}

@Composable
private fun AxialRotationEditor(
    rotationDeg: Double,
    onRotationChange: (Double) -> Unit,
) {
    var emitted by remember { mutableStateOf<Double?>(null) }
    var text by remember { mutableStateOf(rotationDeg.fmt()) }
    LaunchedEffect(rotationDeg) {
        if (emitted == null || abs(rotationDeg - emitted!!) > 1e-6) {
            text = rotationDeg.fmt()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Axial rotation (roll around pipe)",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConduitBendingInputs.QUICK_ROTATIONS.forEach { preset ->
                val selected = abs(normalizeRotationDeg(rotationDeg) - preset) < 0.05
                FilterChip(
                    selected = selected,
                    onClick = {
                        emitted = preset
                        text = preset.fmt()
                        onRotationChange(preset)
                    },
                    label = { Text("${preset.toInt()}°") },
                )
            }
        }
        NumberField(
            label = "Custom roll (0–360°)",
            value = text,
            onValueChange = { raw ->
                text = raw
                raw.toDecimalOrNull()?.let { parsed ->
                    val norm = normalizeRotationDeg(parsed)
                    emitted = norm
                    onRotationChange(norm)
                }
            },
            unit = "°",
            supportingText = "0° bends up, 90° right, 180° down, 270° left — or any 0–360° roll.",
        )
    }
}

/** Continuous hook-once-at-End-A tape marking sheet for the entire stick. */
@Composable
private fun TapeMarkingSheetCard(plan: StickPlan, settings: AppSettings) {
    val context = LocalContext.current
    val copyMark: (StickMark) -> Unit = { mark ->
        val posText = MeasureFormat.forClipboard(settings.format(Quantity.inches(mark.positionFromEndAIn)))
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(mark.label, posText))
    }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Continuous Tape Marking Sheet",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                fractionText("Hook tape once at End A (${settings.formatInches(plan.stickLengthIn)} stick). Tap a mark to copy."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            plan.marks.forEachIndexed { idx, mark ->
                if (idx > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                val posString = settings.format(Quantity.inches(mark.positionFromEndAIn))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = "Copy mark position") { copyMark(mark) }
                        .padding(vertical = 4.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "${mark.label}: ${spokenText(posString)}, align ${mark.alignment.label}, ${mark.hookDirection.label}"
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(mark.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        val detail = buildString {
                            append(mark.alignment.label)
                            append(" · ")
                            append(mark.hookDirection.label)
                            append(" · ")
                            append("${mark.bendAngleDeg.fmt()}°")
                            append(" · ")
                            append("${mark.axialRotationDeg.fmt()}° roll")
                            mark.note?.let { append(" ($it)") }
                        }
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        fractionText(posString),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

private fun bendCardTitle(item: StickBendItem, index: Int, settings: AppSettings): String {
    val num = index + 1
    val rollText = "${normalizeRotationDeg(item.axialRotationDeg).fmt()}° roll"
    val angleText = item.angleDegrees()?.let { "${it.fmt()}°" } ?: "—"
    return when (item.type) {
        BendType.OFFSET -> {
            val dim = item.offsetDepth?.toQuantity()?.let { settings.format(it) }
            if (dim != null) "$num. $angleText Offset · $dim · $rollText" else "$num. $angleText Offset · $rollText"
        }
        BendType.ROLLING_OFFSET -> {
            val rise = item.rise?.toQuantity()?.let { settings.format(it) }
            val roll = item.roll?.toQuantity()?.let { settings.format(it) }
            if (rise != null && roll != null) "$num. $angleText Rolling · $rise×$roll · $rollText" else "$num. $angleText Rolling Offset · $rollText"
        }
        BendType.SADDLE_3 -> {
            val dim = item.saddleHeight?.toQuantity()?.let { settings.format(it) }
            if (dim != null) "$num. $angleText 3-Pt Saddle · $dim · $rollText" else "$num. $angleText 3-Pt Saddle · $rollText"
        }
        BendType.SADDLE_4 -> {
            val h = item.saddleHeight?.toQuantity()?.let { settings.format(it) }
            val w = item.saddleWidth?.toQuantity()?.let { settings.format(it) }
            if (h != null && w != null) "$num. $angleText 4-Pt Saddle · $h×$w · $rollText" else "$num. $angleText 4-Pt Saddle · $rollText"
        }
        BendType.STUB_UP -> {
            val b2b = if (item.backToBack) " (B2B)" else ""
            val h = item.stubHeight?.toQuantity()?.let { settings.format(it) }
            if (h != null) "$num. 90° Stub$b2b · $h · $rollText" else "$num. 90° Stub$b2b · $rollText"
        }
        BendType.KICK -> {
            val h = item.kickHeight?.toQuantity()?.let { settings.format(it) }
            if (h != null) "$num. $angleText Kick · $h · $rollText" else "$num. $angleText Kick · $rollText"
        }
    }
}

private fun Severity.toLevel() = when (this) {
    Severity.INFO -> NoticeLevel.INFO
    Severity.WARNING -> NoticeLevel.WARNING
    Severity.ERROR -> NoticeLevel.ERROR
}

private fun Figure.toResult(settings: AppSettings): ResultValue = when (kind) {
    FigureKind.LENGTH -> ResultValue.of(key, label, Quantity.inches(value), settings.format(Quantity.inches(value)))
    FigureKind.ANGLE -> ResultValue.number(key, label, ValueKind.ANGLE, value, formatDecimal(value.toDouble(), 2) + "°")
    FigureKind.RATIO -> ResultValue.number(key, label, ValueKind.NUMBER, value, formatDecimal(value.toDouble(), 3))
}

/** Summary rows displayed in the bottom ResultCard. */
private fun StickPlan.toSummaryResults(settings: AppSettings): List<ResultValue> {
    if (!hasResults) return emptyList()
    return buildList {
        add(ResultValue.of("cut_length", "Cut length", Quantity.inches(cutLengthIn), settings.formatInches(cutLengthIn)))
        add(ResultValue.of("developed_length", "Total developed length", Quantity.inches(totalDevelopedLengthIn), settings.formatInches(totalDevelopedLengthIn)))
        if (overflowsStick) {
            add(ResultValue.of("stick_overflow", "Stick overflow (coupling needed)", Quantity.inches(overflowAmountIn), "+${settings.formatInches(overflowAmountIn)}"))
        } else {
            add(ResultValue.of("remaining_straight", "Remaining straight stick", Quantity.inches(remainingStraightIn), settings.formatInches(remainingStraightIn)))
        }
        add(ResultValue.number(ProjectAnalysis.BEND_DEGREES_KEY, "Total bend degrees", ValueKind.ANGLE, totalDegrees, "${totalDegrees.fmt()}°"))
        if (bendResults.size == 1) {
            bendResults.single().plan.figures
                .filterNot { it.key == "cut_length" }
                .forEach { add(it.toResult(settings)) }
        }
    }
}

/** Everything published to other tools and saved on a ProjectItem. */
private fun StickPlan.toResults(settings: AppSettings): List<ResultValue> {
    if (!hasResults) return emptyList()
    return buildList {
        addAll(toSummaryResults(settings))
        marks.forEach { mark ->
            add(ResultValue.of(mark.key, mark.label, Quantity.inches(mark.positionFromEndAIn), settings.formatInches(mark.positionFromEndAIn)))
        }
    }
}

private fun StickPlan.summary(settings: AppSettings): String {
    if (!hasResults) return "Conduit Bending"
    val cut = settings.formatInches(cutLengthIn)
    return if (bendResults.size == 1) {
        val b = bendResults.single().item
        "${b.type.label}: cut $cut (${totalDegrees.fmt()}°)"
    } else {
        "${bendResults.size}-bend stick: cut $cut (${totalDegrees.fmt()}°)"
    }
}

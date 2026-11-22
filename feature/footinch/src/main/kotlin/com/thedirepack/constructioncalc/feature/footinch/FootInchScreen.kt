package com.thedirepack.constructioncalc.feature.footinch

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.measure.MeasureFormat
import com.thedirepack.constructioncalc.core.measure.Precision
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.input.Key
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.components.ActionRow
import com.thedirepack.constructioncalc.core.ui.components.FractionPad
import com.thedirepack.constructioncalc.core.ui.components.LengthDisplayToggle
import com.thedirepack.constructioncalc.core.ui.components.MeasureKeypad
import com.thedirepack.constructioncalc.core.ui.components.SettingsGroup
import com.thedirepack.constructioncalc.core.ui.components.SwitchRow
import com.thedirepack.constructioncalc.core.ui.components.ToolScaffold
import com.thedirepack.constructioncalc.core.ui.components.rememberKeyPress
import com.thedirepack.constructioncalc.core.ui.components.rememberModuleBoolean
import com.thedirepack.constructioncalc.core.ui.fractionText
import com.thedirepack.constructioncalc.core.ui.isMetric
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost

@Composable
fun FootInchScreen(host: ToolHost) {
    val opened = host.openedItem?.takeIf { it.toolId == FootInchTool.ID }
    val viewModel: FootInchViewModel = viewModel(key = "foot-inch:${opened?.id}", factory = FootInchViewModel.Factory)
    LaunchedEffect(opened) { opened?.let(viewModel::restore) }

    val settings = rememberAppSettings()
    val context = LocalContext.current
    val view = LocalView.current
    val showFractionKeys = rememberModuleBoolean(FootInchTool.ID, FootInchPrefs.SHOW_FRACTION_KEYS, true)
    val showMemoryKeys = rememberModuleBoolean(FootInchTool.ID, FootInchPrefs.SHOW_MEMORY_KEYS, true)
    var showHistory by remember { mutableStateOf(false) }
    var showMemorySheet by remember { mutableStateOf(false) }
    var showHelpers by remember { mutableStateOf(false) }
    var editingTarget by remember { mutableStateOf<Pair<Long, Long>?>(null) }

    val state = viewModel.state
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val memoryEntries by viewModel.memoryEntries.collectAsStateWithLifecycle()
    val activeMemoryId by viewModel.activeMemoryId.collectAsStateWithLifecycle()

    val activeGroup = remember(groups, viewModel.activeGroupId) { viewModel.activeGroup(groups) }
    val activeMemEntry = remember(memoryEntries, activeMemoryId) { viewModel.activeMemory(memoryEntries, activeMemoryId) }

    val press = rememberKeyPress { key ->
        val outcome = viewModel.onKey(key, settings)
        outcome.completed?.let { calc ->
            host.publish(listOf(viewModel.resultValue(calc, settings)), context = viewModel.formatExpression(calc.expression, settings), immediate = true)
        }
        outcome.accepted
    }
    val hapticAction: (() -> Boolean) -> Unit = { action ->
        val accepted = action()
        val feedback = when {
            accepted -> HapticFeedbackConstants.KEYBOARD_TAP
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> HapticFeedbackConstants.REJECT
            else -> HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(feedback)
    }
    val copy: (String) -> Unit = { text ->
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Measurement", MeasureFormat.forClipboard(text)))
    }

    ToolScaffold(
        title = "Foot-Inch",
        host = host,
        onSave = viewModel.draft(settings)?.let { draft -> { host.saveToProject(draft) } },
        actions = {
            IconButton(onClick = { showHelpers = true }) {
                Icon(painterResource(R.drawable.ic_function), contentDescription = "Helpers: Right triangle and layout marks")
            }
            IconButton(onClick = { showHistory = true }) {
                Icon(painterResource(R.drawable.ic_history), contentDescription = "Calculation tape")
            }
            IconButton(onClick = host::openSettings) {
                Icon(painterResource(R.drawable.ic_tune), contentDescription = "Settings")
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
        ) {
            val inches = viewModel.preferInches()
            Display(
                activeGroup = activeGroup,
                showPendingExpression = activeGroup == null || state.answer == null,
                expression = viewModel.formatExpression(state.tokens, settings),
                result = state.result,
                isAnswer = state.answer != null,
                error = state.error,
                memory = activeMemEntry?.value?.toQuantity(),
                memoryPreferInches = activeMemEntry?.preferInches,
                memoryCount = memoryEntries.size,
                onOpenMemory = { showMemorySheet = true },
                onRecallMemory = { hapticAction { viewModel.memoryRecall() } },
                onEditStep = { gid, sid -> editingTarget = gid to sid },
                onOpenTape = { showHistory = true },
                settings = settings,
                preferInches = inches,
                showFractionKeys = showFractionKeys,
                onCopy = copy,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            ControlRow(
                metric = settings.isMetric,
                precision = settings.precision,
                onPrecision = LocalAppServices.current.settings::setPrecision,
                onBackspace = { press(Key.Backspace) },
                onClear = { press(Key.Clear) },
            )
            FunctionAndMemoryStrip(
                metric = settings.isMetric,
                showMemoryKeys = showMemoryKeys,
                hasMemory = memoryEntries.isNotEmpty(),
                hasAns = viewModel.effectiveAns != null,
                onMemoryClear = { hapticAction { viewModel.memoryClear() } },
                onMemoryRecall = { hapticAction { viewModel.memoryRecall() } },
                onMemoryStore = { hapticAction { viewModel.memoryStore() } },
                onMemoryAdd = { hapticAction { viewModel.memoryAdd() } },
                onMemorySubtract = { hapticAction { viewModel.memorySubtract() } },
                onOpenMemorySheet = { showMemorySheet = true },
                onAns = { hapticAction(viewModel::recallAns) },
                onKey = press,
            )
            Spacer(Modifier.height(6.dp))
            if (showFractionKeys && !settings.isMetric) {
                FractionPad(settings.precision, onKey = press)
                Spacer(Modifier.height(6.dp))
            }
            MeasureKeypad(onKey = press, metric = settings.isMetric, modifier = Modifier.weight(1.45f).fillMaxWidth())
        }
    }

    if (showHistory) {
        GroupedHistorySheet(
            groups = groups,
            activeGroupId = viewModel.activeGroupId,
            settings = settings,
            onContinueGroup = viewModel::continueGroup,
            onEditStep = { gid, sid -> editingTarget = gid to sid },
            onRecallStep = { step ->
                hapticAction { viewModel.recallQuantity(step.result.toQuantity(), step.preferInches) }
            },
            onDeleteStep = viewModel::deleteStep,
            onToggleFavorite = viewModel::toggleFavorite,
            onRenameGroup = viewModel::renameGroup,
            onRenameStep = viewModel::renameStep,
            onDeleteGroup = viewModel::deleteGroup,
            onClearRecent = viewModel::clearRecentGroups,
            onClearAll = viewModel::clearAllGroups,
            onDismiss = { showHistory = false },
        )
    }

    editingTarget?.let { (gid, sid) ->
        val group = groups.firstOrNull { it.id == gid }
        val stepIndex = group?.steps?.indexOfFirst { it.id == sid } ?: -1
        if (group == null || stepIndex < 0) {
            editingTarget = null
        } else {
            EditStepSheet(
                group = group,
                step = group.steps[stepIndex],
                stepIndex = stepIndex,
                settings = settings,
                onRenameStep = { label -> viewModel.renameStep(gid, sid, label) },
                onDeleteStep = {
                    val err = viewModel.deleteStep(gid, sid)
                    if (err == null) editingTarget = null
                    err
                },
                onApply = { newTokens ->
                    val err = viewModel.editStep(gid, sid, newTokens)
                    if (err == null) {
                        viewModel.groups.value.firstOrNull { it.id == gid }?.finalStep?.let { final ->
                            val qty = final.result.toQuantity()
                            val label = FootInchFormat.primary(qty, settings, final.preferInches)
                            val value = com.thedirepack.constructioncalc.core.model.ResultValue.of(
                                "result",
                                "Foot-Inch result",
                                qty,
                                label,
                            )
                            host.publish(listOf(value), context = group.label.ifBlank { "Edited calculation" }, immediate = true)
                        }
                        editingTarget = null
                    }
                    err
                },
                onDismiss = { editingTarget = null },
            )
        }
    }

    if (showMemorySheet) {
        MemorySheet(
            entries = memoryEntries,
            activeId = activeMemoryId,
            currentResult = state.result,
            currentPreferInches = viewModel.preferInches(),
            settings = settings,
            onStoreCurrent = { hapticAction { viewModel.memoryStore() } },
            onSelectActive = viewModel::memorySelectActive,
            onRecall = { entry -> hapticAction { viewModel.memoryRecall(entry) } },
            onApplyOp = { id, op -> viewModel.applyMemoryOpWithError(op, id) },
            onRename = viewModel::memoryRename,
            onDelete = { id -> hapticAction { viewModel.memoryClear(id) } },
            onClearAll = viewModel::memoryClearAll,
            onDismiss = { showMemorySheet = false },
        )
    }

    if (showHelpers) {
        FootInchToolsSheet(
            settings = settings,
            onUseQuantity = { qty ->
                hapticAction { viewModel.recallQuantity(qty) }
                showHelpers = false
            },
            onCopy = copy,
            onDismiss = { showHelpers = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FunctionAndMemoryStrip(
    metric: Boolean,
    showMemoryKeys: Boolean,
    hasMemory: Boolean,
    hasAns: Boolean,
    onMemoryClear: () -> Unit,
    onMemoryRecall: () -> Unit,
    onMemoryStore: () -> Unit,
    onMemoryAdd: () -> Unit,
    onMemorySubtract: () -> Unit,
    onOpenMemorySheet: () -> Unit,
    onAns: () -> Unit,
    onKey: (Key) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (showMemoryKeys) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                MiniKey("MC", "Memory clear", enabled = hasMemory, onClick = onMemoryClear, modifier = Modifier.weight(1f))
                MiniKey(
                    "MR",
                    "Memory recall",
                    enabled = hasMemory,
                    onClick = onMemoryRecall,
                    onLongClick = onOpenMemorySheet,
                    modifier = Modifier.weight(1f),
                )
                MiniKey(
                    "MS",
                    "Memory store",
                    onClick = onMemoryStore,
                    onLongClick = onOpenMemorySheet,
                    modifier = Modifier.weight(1f),
                )
                MiniKey("M+", "Memory add", onClick = onMemoryAdd, modifier = Modifier.weight(1f))
                MiniKey("M−", "Memory subtract", onClick = onMemorySubtract, modifier = Modifier.weight(1f))
                MiniKey("Ans", "Previous answer", enabled = hasAns, onClick = onAns, modifier = Modifier.weight(1f))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            MiniKey("(", "Open parenthesis", onClick = { onKey(Key.OpenParen) }, modifier = Modifier.weight(1f))
            MiniKey(")", "Close parenthesis", onClick = { onKey(Key.CloseParen) }, modifier = Modifier.weight(1f))
            MiniKey("√", "Square root", onClick = { onKey(Key.Sqrt) }, modifier = Modifier.weight(1f))
            MiniKey("x²", "Square", onClick = { onKey(Key.Square) }, modifier = Modifier.weight(1f))
            if (!showMemoryKeys) {
                MiniKey("Ans", "Previous answer", enabled = hasAns, onClick = onAns, modifier = Modifier.weight(1f))
            }
            if (metric) {
                MiniKey("'", "Feet", accent = true, onClick = { onKey(Key.Char('\'')) }, modifier = Modifier.weight(1f))
                MiniKey("\"", "Inches", accent = true, onClick = { onKey(Key.Char('"')) }, modifier = Modifier.weight(1f))
                MiniKey("/", "Fraction bar", accent = true, onClick = { onKey(Key.Char('/')) }, modifier = Modifier.weight(1f))
            } else {
                MiniKey("mm", "Millimeters", accent = true, onClick = { onKey(Key.Text("mm")) }, modifier = Modifier.weight(1f))
                MiniKey("cm", "Centimeters", accent = true, onClick = { onKey(Key.Text("cm")) }, modifier = Modifier.weight(1f))
                MiniKey("m", "Meters", accent = true, onClick = { onKey(Key.Text("m")) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MiniKey(
    label: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    accent: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val container = when {
        !enabled -> scheme.surfaceContainerLow
        accent -> scheme.tertiaryContainer.copy(alpha = 0.65f)
        else -> scheme.surfaceContainerHighest
    }
    val content = when {
        !enabled -> scheme.onSurface.copy(alpha = 0.35f)
        accent -> scheme.onTertiaryContainer
        else -> scheme.onSurface
    }
    val shape = RoundedCornerShape(10.dp)
    Surface(
        shape = shape,
        color = container,
        contentColor = content,
        modifier = modifier
            .height(34.dp)
            .clip(shape)
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 14.sp),
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun Display(
    activeGroup: CalcGroup?,
    showPendingExpression: Boolean,
    expression: String,
    result: Quantity?,
    isAnswer: Boolean,
    error: String?,
    memory: Quantity?,
    memoryPreferInches: Boolean?,
    memoryCount: Int,
    onOpenMemory: () -> Unit,
    onRecallMemory: () -> Unit,
    onEditStep: (groupId: Long, stepId: Long) -> Unit,
    onOpenTape: () -> Unit,
    settings: AppSettings,
    preferInches: Boolean?,
    showFractionKeys: Boolean,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val steps = activeGroup?.steps.orEmpty()
    val hasMiniTape = steps.isNotEmpty()
    val tapeScrollState = rememberLazyListState()

    LaunchedEffect(steps.size, activeGroup?.id) {
        if (steps.isNotEmpty()) {
            tapeScrollState.animateScrollToItem(steps.lastIndex)
        }
    }

    Column(
        modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.End,
    ) {
        if ((memory != null && (!memory.value.isZero || memoryCount > 0)) || hasMiniTape) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (hasMiniTape && activeGroup != null) {
                    Text(
                        "${activeGroup.label.ifBlank { "Active calculation" }} · ${steps.size} step${if (steps.size == 1) "" else "s"} · tap step to edit",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onOpenTape)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                if (memory != null) {
                    val memText = FootInchFormat.primary(memory, settings, memoryPreferInches)
                    val extra = if (memoryCount > 1) " · $memoryCount" else ""
                    val chipShape = RoundedCornerShape(8.dp)
                    Surface(
                        shape = chipShape,
                        color = scheme.secondaryContainer,
                        contentColor = scheme.onSecondaryContainer,
                        modifier = Modifier
                            .clip(chipShape)
                            .combinedClickable(
                                onClickLabel = "Open memory registers",
                                onClick = onOpenMemory,
                                onLongClickLabel = "Recall active memory",
                                onLongClick = onRecallMemory,
                            )
                            .semantics { contentDescription = "Memory $memText$extra. Tap to manage memory slots." },
                    ) {
                        Text(
                            fractionText("M  $memText$extra"),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }

        if (hasMiniTape && activeGroup != null) {
            LazyColumn(
                state = tapeScrollState,
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(steps, key = { _, step -> step.id }) { idx, step ->
                    val prevPrefer = if (idx > 0) steps[idx - 1].preferInches else null
                    val inputText = CalculationTapeOps.formatStepInput(step, settings, prevPrefer)
                    val runningText = FootInchFormat.primary(step.result.toQuantity(), settings, step.preferInches)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClickLabel = "Edit step ${idx + 1}") {
                                onEditStep(activeGroup.id, step.id)
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            fractionText(if (step.label.isNotBlank()) "${step.label}: $inputText" else inputText),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            fractionText("= $runningText"),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (idx == steps.lastIndex && isAnswer) scheme.primary else scheme.onSurface,
                            maxLines = 1,
                        )
                    }
                    if (idx < steps.lastIndex) {
                        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.35f))
                    }
                }
            }
        }

        if (showPendingExpression) {
            if (expression.isEmpty()) {
                Text(
                    fractionText(FootInchFormat.hint(settings, showFractionKeys)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.outline,
                )
            } else {
                BasicText(
                    fractionText(expression),
                    style = TextStyle(
                        color = if (isAnswer) scheme.onSurfaceVariant else scheme.onSurface,
                        textAlign = TextAlign.End,
                        fontSize = if (hasMiniTape) 22.sp else 28.sp,
                    ),
                    maxLines = if (hasMiniTape) 2 else 3,
                    autoSize = TextAutoSize.StepBased(minFontSize = 15.sp, maxFontSize = if (hasMiniTape) 22.sp else 28.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(if (hasMiniTape) 4.dp else 8.dp))

        when {
            error != null -> Text(error, color = scheme.error, style = MaterialTheme.typography.titleLarge)
            result != null -> {
                val primary = FootInchFormat.primary(result, settings, preferInches)
                val rounding = FootInchFormat.roundingDirection(result, settings)
                val answerMaxSize = when {
                    !isAnswer -> 34.sp
                    hasMiniTape -> 42.sp
                    else -> 56.sp
                }
                BasicText(
                    fractionText((if (rounding != 0) "≈ " else "") + primary),
                    style = TextStyle(
                        color = if (isAnswer) scheme.primary else scheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        fontWeight = if (isAnswer) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = answerMaxSize,
                    ),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 20.sp, maxFontSize = answerMaxSize),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = "Copy result") { onCopy(primary) },
                )
                if (rounding != 0) {
                    Text(
                        fractionText("Rounded ${if (rounding < 0) "down" else "up"} to nearest ${settings.precision.label}\""),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
                val conversions = FootInchFormat.conversions(result, settings, preferInches)
                if (conversions.isNotEmpty()) {
                    Spacer(Modifier.height(if (hasMiniTape) 4.dp else 8.dp))
                    if (hasMiniTape) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        ) {
                            conversions.forEach { c ->
                                AssistChip(
                                    onClick = { onCopy(c.value) },
                                    label = { Text(fractionText(c.value), maxLines = 1) },
                                    modifier = Modifier.semantics { contentDescription = "${c.label}: ${c.value}" },
                                )
                            }
                        }
                    } else {
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            conversions.forEach { c ->
                                AssistChip(
                                    onClick = { onCopy(c.value) },
                                    label = { Text(fractionText(c.value)) },
                                    modifier = Modifier.semantics { contentDescription = "${c.label}: ${c.value}" },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlRow(
    metric: Boolean,
    precision: Precision,
    onPrecision: (Precision) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!metric) Box {
            TextButton(
                onClick = { menu = true },
                modifier = Modifier.semantics { contentDescription = "Precision ${precision.label} inch. Change." },
            ) {
                Text(fractionText("± ${precision.label}\""), style = MaterialTheme.typography.titleSmall)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                Precision.entries.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(fractionText("${p.label}\"")) },
                        onClick = {
                            onPrecision(p)
                            menu = false
                        },
                    )
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        LengthDisplayToggle(Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .semantics { contentDescription = "Backspace. Long press to clear." }
                .combinedClickable(onClick = onBackspace, onLongClick = onClear),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_backspace),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(4.dp))
    }
}

/** Foot-Inch's page in the app's Settings screen. */
@Composable
internal fun FootInchSettingsPage() {
    val context = LocalContext.current
    val store = LocalAppServices.current.settings.module(FootInchTool.ID)
    val showFractionKeys = rememberModuleBoolean(FootInchTool.ID, FootInchPrefs.SHOW_FRACTION_KEYS, true)
    val showMemoryKeys = rememberModuleBoolean(FootInchTool.ID, FootInchPrefs.SHOW_MEMORY_KEYS, true)
    SettingsGroup("Keypad") {
        SwitchRow(
            "Fraction keys",
            checked = showFractionKeys,
            onCheckedChange = { store.setBoolean(FootInchPrefs.SHOW_FRACTION_KEYS, it) },
            subtitle = "One-tap ⅛ ¼ ½ … keys above the keypad.",
        )
        SwitchRow(
            "Memory keys",
            checked = showMemoryKeys,
            onCheckedChange = { store.setBoolean(FootInchPrefs.SHOW_MEMORY_KEYS, it) },
            subtitle = "Show MC, MR, MS, M+ and M− keys above the keypad.",
        )
    }
    SettingsGroup("History & memory") {
        ActionRow("Clear calculation tape", onClick = { FootInchHistory.get(context).clear() })
        ActionRow("Clear saved memory", onClick = { FootInchHistory.get(context).clearAllMemory() })
    }
    val metric = rememberAppSettings().isMetric
    SettingsGroup("Entering measurements") {
        Text(
            fractionText(
                if (metric) {
                    "Type a number then m, cm or mm — 1 m 20 cm  →  1.2 m.\n" +
                        "A number with no unit next to a length counts as millimeters: 1 m + 50 = 1.05 m.\n" +
                        "Chained operations group together on the tape; tap any step to edit it and update later steps.\n" +
                        "Use MS/MR/M+/M− or tap the M pill to manage saved memory slots. Long-press ⌫ to clear."
                } else
                "Type feet, then ' — inches — then tap a fraction key (or type \" and a fraction with /).\n" +
                    "5 ' 3 [1/2]  →  5' 3 1/2\"     [7/16]  →  7/16\"\n" +
                    "Chained steps group together on the tape; tap any step to edit it and recompute later steps.\n" +
                    "Use MS/MR/M+/M− or tap the M pill to store and combine saved memory slots. Long-press ⌫ to clear.",
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

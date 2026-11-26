package com.thedirepack.constructioncalc.feature.footinch

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.FeetInchStyle
import com.thedirepack.constructioncalc.core.measure.FractionRounding
import com.thedirepack.constructioncalc.core.measure.MeasureFormat
import com.thedirepack.constructioncalc.core.measure.MetricDisplay
import com.thedirepack.constructioncalc.core.measure.MetricResolution
import com.thedirepack.constructioncalc.core.measure.Precision
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import com.thedirepack.constructioncalc.core.measure.input.CalcInput
import com.thedirepack.constructioncalc.core.measure.input.Key
import com.thedirepack.constructioncalc.core.measure.input.LengthDisplay
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.components.ActionRow
import com.thedirepack.constructioncalc.core.ui.components.ChoiceRow
import com.thedirepack.constructioncalc.core.ui.components.FractionPad
import com.thedirepack.constructioncalc.core.ui.components.LengthDisplayToggle
import com.thedirepack.constructioncalc.core.ui.components.MeasureKeypad
import com.thedirepack.constructioncalc.core.ui.components.SettingsGroup
import com.thedirepack.constructioncalc.core.ui.components.SwitchRow
import com.thedirepack.constructioncalc.core.ui.components.ToolScaffold
import com.thedirepack.constructioncalc.core.ui.components.rememberKeyPress
import com.thedirepack.constructioncalc.core.ui.format
import com.thedirepack.constructioncalc.core.ui.fractionText
import com.thedirepack.constructioncalc.core.ui.isMetric
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost

@Composable
internal fun rememberFootInchSettings(): FootInchSettings {
    val store = LocalAppServices.current.settings.module(FootInchTool.ID)
    val version by store.changes.collectAsStateWithLifecycle()
    return remember(version, store) { FootInchSettings.read(store) }
}

private fun performKeyHaptic(view: View, accepted: Boolean) {
    val feedback = when {
        accepted -> HapticFeedbackConstants.KEYBOARD_TAP
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> HapticFeedbackConstants.REJECT
        else -> HapticFeedbackConstants.LONG_PRESS
    }
    view.performHapticFeedback(feedback)
}

@Composable
fun FootInchScreen(host: ToolHost) {
    val opened = host.openedItem?.takeIf { it.toolId == FootInchTool.ID }
    val viewModel: FootInchViewModel = viewModel(key = "foot-inch:${opened?.id}", factory = FootInchViewModel.Factory)
    LaunchedEffect(opened) { opened?.let(viewModel::restore) }

    val settings = rememberAppSettings()
    val footInchSettings = rememberFootInchSettings()
    val services = LocalAppServices.current
    val store = remember(services) { services.settings.module(FootInchTool.ID) }
    val context = LocalContext.current
    val view = LocalView.current
    var showHistory by remember { mutableStateOf(false) }
    var showHelpers by remember { mutableStateOf(false) }
    var showMemory by remember { mutableStateOf(false) }
    var savingMemory by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var labelingStep by remember { mutableStateOf<Pair<Long, CalcStep>?>(null) }
    var namingGroup by remember { mutableStateOf<CalcGroup?>(null) }
    var tapeError by remember { mutableStateOf<String?>(null) }
    val state = viewModel.state
    val groups by viewModel.history.groups.collectAsStateWithLifecycle()
    val memory by viewModel.history.memory.collectAsStateWithLifecycle()

    val publishIfCompleted: (CalcInput.Outcome) -> Unit = { outcome ->
        outcome.completed?.let { calc ->
            host.publish(
                listOf(viewModel.resultValue(calc, settings)),
                context = viewModel.formatExpression(calc.expression, settings),
                immediate = true,
            )
        }
    }
    val press = rememberKeyPress { key ->
        val outcome = viewModel.onKey(key, settings)
        publishIfCompleted(outcome)
        outcome.accepted
    }
    val hapticAction: (() -> Boolean) -> Unit = { action ->
        performKeyHaptic(view, action())
    }
    val copy: (String) -> Unit = { text ->
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Measurement", MeasureFormat.forClipboard(text)))
    }

    val useDualSplit = !settings.isMetric && footInchSettings.keypadLayout == KeypadLayout.DUAL_SPLIT
    val horizontalPad = if (useDualSplit) 8.dp else 12.dp

    ToolScaffold(
        title = "Foot-Inch",
        host = host,
        onSave = viewModel.draft(settings)?.let { draft -> { host.saveToProject(draft) } },
        actions = {
            IconButton(onClick = { showHelpers = true }) {
                Icon(painterResource(R.drawable.ic_function), contentDescription = "Helpers: Right triangle, stairs, and layout marks")
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
                .padding(horizontal = horizontalPad)
                .padding(bottom = 12.dp),
        ) {
            if (footInchSettings.showInlineTape && groups.isNotEmpty()) {
                InlineCalculationTape(
                    groups = groups,
                    activeGroupId = viewModel.activeGroupId,
                    settings = settings,
                    onRecall = { q, inches -> hapticAction { viewModel.recallQuantity(q, inches) } },
                    onCopy = copy,
                    onEditStep = { g, st -> editing = g to st },
                    onLabelStep = { g, st -> labelingStep = g to st },
                    onDeleteStep = { g, st -> tapeError = viewModel.deleteStep(g, st) },
                    onContinue = viewModel::continueGroup,
                    onNameGroup = { namingGroup = it },
                    onDeleteGroup = viewModel::deleteGroup,
                )
            }
            tapeError?.let { msg ->
                Text(
                    msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp).clickable { tapeError = null },
                )
            }
            val inches = viewModel.preferInches()
            Display(
                expression = viewModel.formatExpression(state.tokens, settings),
                result = state.result,
                isAnswer = state.answer != null,
                error = state.error,
                memory = memory,
                onOpenMemory = { showMemory = true },
                settings = settings,
                preferInches = inches,
                showFractionKeys = footInchSettings.showFractionKeys,
                keypadLayout = footInchSettings.keypadLayout,
                onCopy = copy,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            ControlRow(
                metric = settings.isMetric,
                precision = settings.precision,
                onPrecision = services.settings::setPrecision,
                dualSplit = footInchSettings.keypadLayout == KeypadLayout.DUAL_SPLIT,
                onToggleDualSplit = {
                    val next = if (footInchSettings.keypadLayout == KeypadLayout.DUAL_SPLIT) {
                        KeypadLayout.CLASSIC
                    } else {
                        KeypadLayout.DUAL_SPLIT
                    }
                    store.setString(FootInchPrefs.KEYPAD_LAYOUT, next.name)
                },
                onBackspace = { press(Key.Backspace) },
                onClear = { press(Key.Clear) },
            )
            if (footInchSettings.showFunctionStrip) {
                FunctionAndMemoryStrip(
                    metric = settings.isMetric,
                    canSave = state.result != null,
                    hasMemory = memory.isNotEmpty(),
                    hasAns = viewModel.effectiveAns != null,
                    onMemorySave = { savingMemory = true },
                    onMemoryOpen = { showMemory = true },
                    onMemoryAdd = { hapticAction(viewModel::memoryAdd) },
                    onMemorySubtract = { hapticAction(viewModel::memorySubtract) },
                    onAns = { hapticAction(viewModel::recallAns) },
                    onKey = press,
                )
                Spacer(Modifier.height(6.dp))
            }
            when {
                settings.isMetric -> {
                    MeasureKeypad(onKey = press, metric = true, modifier = Modifier.weight(1.45f).fillMaxWidth())
                }
                footInchSettings.keypadLayout == KeypadLayout.DUAL_SPLIT -> {
                    DualSplitKeypad(
                        fractionPrecision = footInchSettings.effectiveFractionPrecision(settings.precision),
                        colorBlindShapes = footInchSettings.colorBlindShapes,
                        onAction = { action ->
                            val outcome = viewModel.onDualSplitAction(action, settings)
                            publishIfCompleted(outcome)
                            performKeyHaptic(view, outcome.accepted)
                        },
                        modifier = Modifier.weight(1.55f).fillMaxWidth(),
                    )
                }
                else -> {
                    if (footInchSettings.showFractionKeys) {
                        FractionPad(footInchSettings.effectiveFractionPrecision(settings.precision), onKey = press)
                        Spacer(Modifier.height(6.dp))
                    }
                    MeasureKeypad(onKey = press, metric = false, modifier = Modifier.weight(1.45f).fillMaxWidth())
                }
            }
        }
    }

    if (showHistory) {
        GroupedHistorySheet(
            groups = groups,
            activeGroupId = viewModel.activeGroupId,
            settings = settings,
            onContinueGroup = viewModel::continueGroup,
            onEditStep = { g, st -> editing = g to st },
            onRecallStep = { step -> hapticAction { viewModel.recallQuantity(step.result.toQuantity(), step.preferInches) } },
            onToggleFavorite = viewModel.history::toggleFavorite,
            onRenameGroup = viewModel.history::setGroupLabel,
            onRenameStep = viewModel.history::setStepLabel,
            onDeleteGroup = viewModel::deleteGroup,
            onClearRecent = viewModel::clearRecentGroups,
            onClearAll = viewModel::clearAllGroups,
            onDismiss = { showHistory = false },
        )
    }

    editing?.let { (groupId, stepId) ->
        val group = groups.firstOrNull { it.id == groupId }
        val stepIndex = group?.steps?.indexOfFirst { it.id == stepId } ?: -1
        if (group == null || stepIndex < 0) {
            LaunchedEffect(Unit) { editing = null }
        } else {
            EditStepSheet(
                group = group,
                step = group.steps[stepIndex],
                stepIndex = stepIndex,
                settings = settings,
                onRenameStep = { viewModel.history.setStepLabel(groupId, stepId, it) },
                onDeleteStep = { viewModel.deleteStep(groupId, stepId).also { if (it == null) editing = null } },
                onApply = { tokens -> viewModel.editStep(groupId, stepId, tokens).also { if (it == null) editing = null } },
                onDismiss = { editing = null },
            )
        }
    }

    labelingStep?.let { (groupId, step) ->
        NoteLabelDialog(
            title = "Note for this step",
            initial = step.label,
            onConfirm = {
                viewModel.history.setStepLabel(groupId, step.id, it)
                labelingStep = null
            },
            onDismiss = { labelingStep = null },
        )
    }

    namingGroup?.let { group ->
        NoteLabelDialog(
            title = "Name calculation",
            initial = group.label,
            onConfirm = {
                viewModel.history.setGroupLabel(group.id, it)
                namingGroup = null
            },
            onDismiss = { namingGroup = null },
        )
    }

    if (showMemory) {
        MemorySheet(
            entries = memory,
            currentResult = state.result,
            currentPreferInches = viewModel.preferInches(),
            settings = settings,
            onSaveCurrent = { savingMemory = true },
            onRecall = { entry -> hapticAction { viewModel.memoryRecall(entry) } },
            onRename = viewModel.history::renameMemory,
            onDelete = viewModel.history::removeMemory,
            onClearAll = viewModel.history::clearMemory,
            onDismiss = { showMemory = false },
        )
    }

    val valueToSave = state.result
    if (savingMemory && valueToSave != null) {
        SaveMemoryDialog(
            valueText = FootInchFormat.primary(valueToSave, settings, viewModel.preferInches()),
            suggestedName = MemoryOps.defaultName(memory),
            existingNames = memory.map { it.label },
            onSave = { name ->
                viewModel.memorySave(name)
                savingMemory = false
            },
            onDismiss = { savingMemory = false },
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

@Composable
private fun FunctionAndMemoryStrip(
    metric: Boolean,
    canSave: Boolean,
    hasMemory: Boolean,
    hasAns: Boolean,
    onMemorySave: () -> Unit,
    onMemoryOpen: () -> Unit,
    onMemoryAdd: () -> Unit,
    onMemorySubtract: () -> Unit,
    onAns: () -> Unit,
    onKey: (Key) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            MiniKey("MS", "Save the value to memory with a name", enabled = canSave, onClick = onMemorySave, modifier = Modifier.weight(1f))
            MiniKey("MR", "Open memory to use a saved value", enabled = hasMemory, onClick = onMemoryOpen, modifier = Modifier.weight(1f))
            MiniKey("M+", "Add the value to the top memory value", enabled = canSave, onClick = onMemoryAdd, modifier = Modifier.weight(1f))
            MiniKey("M−", "Subtract the value from the top memory value", enabled = canSave, onClick = onMemorySubtract, modifier = Modifier.weight(1f))
            MiniKey("Ans", "Previous answer", enabled = hasAns, onClick = onAns, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            MiniKey("(", "Open parenthesis", onClick = { onKey(Key.OpenParen) }, modifier = Modifier.weight(1f))
            MiniKey(")", "Close parenthesis", onClick = { onKey(Key.CloseParen) }, modifier = Modifier.weight(1f))
            MiniKey("√", "Square root", onClick = { onKey(Key.Sqrt) }, modifier = Modifier.weight(1f))
            MiniKey("x²", "Square", onClick = { onKey(Key.Square) }, modifier = Modifier.weight(1f))
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

@Composable
private fun MiniKey(
    label: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
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
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = container,
        contentColor = content,
        modifier = modifier
            .height(34.dp)
            .semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
private fun Display(
    expression: String,
    result: Quantity?,
    isAnswer: Boolean,
    error: String?,
    memory: List<MemoryEntry>,
    onOpenMemory: () -> Unit,
    settings: AppSettings,
    preferInches: Boolean?,
    showFractionKeys: Boolean,
    keypadLayout: KeypadLayout,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.End,
    ) {
        memory.firstOrNull()?.let { top ->
            val memText = FootInchFormat.primary(top.value.toQuantity(), settings, top.preferInches)
            val more = if (memory.size > 1) "  +${memory.size - 1}" else ""
            Surface(
                onClick = onOpenMemory,
                shape = RoundedCornerShape(8.dp),
                color = scheme.secondaryContainer,
                contentColor = scheme.onSecondaryContainer,
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .semantics { contentDescription = "Memory ${top.label}: $memText. Tap to open memory." },
            ) {
                Text(
                    fractionText("M ${top.label}  $memText$more"),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        if (expression.isEmpty()) {
            Text(
                fractionText(FootInchFormat.hint(settings, showFractionKeys, keypadLayout)),
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.outline,
            )
        } else {
            BasicText(
                fractionText(expression),
                style = TextStyle(
                    color = if (isAnswer) scheme.onSurfaceVariant else scheme.onSurface,
                    textAlign = TextAlign.End,
                    fontSize = 28.sp,
                ),
                maxLines = 3,
                autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = 28.sp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(6.dp))

        when {
            error != null -> Text(error, color = scheme.error, style = MaterialTheme.typography.headlineSmall)
            result != null -> {
                val primary = FootInchFormat.primary(result, settings, preferInches)
                val rounding = FootInchFormat.roundingDirection(result, settings)
                BasicText(
                    fractionText((if (rounding != 0) "≈ " else "") + primary),
                    style = TextStyle(
                        color = if (isAnswer) scheme.primary else scheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        fontWeight = if (isAnswer) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 52.sp,
                    ),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 20.sp, maxFontSize = if (isAnswer) 52.sp else 34.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = "Copy result") { onCopy(primary) },
                )
                if (rounding != 0) {
                    val direction = if (rounding < 0) "down" else "up"
                    val modeLabel = when (settings.fractionRounding) {
                        FractionRounding.NEAREST -> "nearest ${settings.precision.label}\""
                        FractionRounding.UP -> "${settings.precision.label}\" (round up)"
                        FractionRounding.DOWN -> "${settings.precision.label}\" (round down)"
                    }
                    Text(
                        fractionText("Rounded $direction to $modeLabel"),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
                val conversions = FootInchFormat.conversions(result, settings, preferInches)
                if (conversions.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ControlRow(
    metric: Boolean,
    precision: Precision,
    onPrecision: (Precision) -> Unit,
    dualSplit: Boolean,
    onToggleDualSplit: () -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!metric) Box {
            TextButton(
                onClick = { menu = true },
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.semantics { contentDescription = "Precision ${precision.label} inch. Change." },
            ) {
                Text(fractionText("± ${precision.label}\""), style = MaterialTheme.typography.titleSmall, maxLines = 1)
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
        if (!metric) {
            Spacer(Modifier.width(4.dp))
            FilterChip(
                selected = dualSplit,
                onClick = onToggleDualSplit,
                label = {
                    Text(
                        "Dual",
                        maxLines = 1,
                        softWrap = false,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                modifier = Modifier.semantics {
                    contentDescription = if (dualSplit) {
                        "Dual-Split feet and inches keypad enabled. Switch to Classic keypad."
                    } else {
                        "Classic keypad enabled. Switch to Dual-Split feet and inches keypad."
                    }
                },
            )
        }
        Spacer(Modifier.width(4.dp))
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

private val SETTINGS_PREVIEW_LENGTH = Quantity.inches(Rational.of(127, 2)) // 5' 3 1/2"

/** Foot-Inch's page in the app's Settings screen. */
@Composable
internal fun FootInchSettingsPage() {
    val context = LocalContext.current
    val repo = LocalAppServices.current.settings
    val store = repo.module(FootInchTool.ID)
    val appSettings = rememberAppSettings()
    val footInchSettings = rememberFootInchSettings()

    SettingsGroup("Keypad") {
        ChoiceRow(
            title = "Keypad layout",
            options = KeypadLayout.entries,
            selected = footInchSettings.keypadLayout,
            onSelect = { store.setString(FootInchPrefs.KEYPAD_LAYOUT, it.name) },
            optionLabel = { it.label },
            subtitle = "Classic single pad or ArchCalc-style Dual-Split (Feet left, Inches middle, Fractions right).",
        )
        ChoiceRow(
            title = "Input fraction keys",
            options = InputFractionPrecision.entries,
            selected = footInchSettings.inputFractionPrecision,
            onSelect = { store.setString(FootInchPrefs.INPUT_FRACTION_PRECISION, it.name) },
            optionLabel = { if (it == InputFractionPrecision.MATCH_OUTPUT) "Match" else it.label },
            subtitle = "Fraction keys shown on the keypad (Match follows output rounding precision).",
        )
        SwitchRow(
            "Fraction keys (Classic)",
            checked = footInchSettings.showFractionKeys,
            onCheckedChange = { store.setBoolean(FootInchPrefs.SHOW_FRACTION_KEYS, it) },
            subtitle = "One-tap ⅛ ¼ ½ … keys above the Classic keypad.",
        )
        SwitchRow(
            "Function & memory strip",
            checked = footInchSettings.showFunctionStrip,
            onCheckedChange = { store.setBoolean(FootInchPrefs.SHOW_FUNCTION_STRIP, it) },
            subtitle = "Show MS, MR, M+, M−, Ans, parentheses, √ and x² keys.",
        )
        SwitchRow(
            "Color-blind key shapes",
            checked = footInchSettings.colorBlindShapes,
            onCheckedChange = { store.setBoolean(FootInchPrefs.COLOR_BLIND_SHAPES, it) },
            subtitle = "Use square Feet keys and rounded Inches keys in Dual-Split mode.",
        )
    }

    SettingsGroup("Footage formatting") {
        Text(
            fractionText("Sample (63 1/2\"): ${appSettings.format(SETTINGS_PREVIEW_LENGTH)}"),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        ChoiceRow(
            title = "Units",
            options = UnitSystem.entries,
            selected = appSettings.unitSystem,
            onSelect = repo::setUnitSystem,
            optionLabel = { it.label },
        )
        if (appSettings.isMetric) {
            ChoiceRow(
                title = "Show lengths in",
                options = MetricDisplay.entries,
                selected = appSettings.metricDisplay,
                onSelect = repo::setMetricDisplay,
                optionLabel = { it.label },
                subtitle = "Auto shows millimeters under 1 m and meters from there.",
            )
            ChoiceRow(
                title = "Round lengths to",
                options = MetricResolution.entries,
                selected = appSettings.metricResolution,
                onSelect = repo::setMetricResolution,
                optionLabel = { it.label },
            )
        } else {
            ChoiceRow(
                title = "Show lengths as",
                options = LengthDisplay.entries,
                selected = appSettings.lengthDisplay,
                onSelect = repo::setLengthDisplay,
                optionLabel = { it.label },
                subtitle = "Auto answers in inches when you entered inches, or force ft-in / inches.",
            )
            ChoiceRow(
                title = "Feet-inch style",
                options = FeetInchStyle.entries,
                selected = appSettings.feetInchStyle,
                onSelect = repo::setFeetInchStyle,
                optionLabel = { it.label },
                subtitle = "Space, hyphenated drawing style, or architectural (10-04-03).",
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Output precision", style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Precision.entries.forEach { p ->
                        FilterChip(
                            selected = p == appSettings.precision,
                            onClick = { repo.setPrecision(p) },
                            label = { Text(fractionText(p.label + "\"")) },
                        )
                    }
                }
            }
            ChoiceRow(
                title = "Fraction rounding",
                options = FractionRounding.entries,
                selected = appSettings.fractionRounding,
                onSelect = repo::setFractionRounding,
                optionLabel = {
                    when (it) {
                        FractionRounding.NEAREST -> "Nearest"
                        FractionRounding.UP -> "Round up"
                        FractionRounding.DOWN -> "Round down"
                    }
                },
                subtitle = "Round fractional inches to nearest step, always up, or always down.",
            )
        }
    }

    SettingsGroup("Calculation tape") {
        SwitchRow(
            "Inline calculation tape",
            checked = footInchSettings.showInlineTape,
            onCheckedChange = { store.setBoolean(FootInchPrefs.SHOW_INLINE_TAPE, it) },
            subtitle = "Show the steps of the current calculation above the display, with earlier calculations as one line each.",
        )
        ActionRow("Clear calculation tape", onClick = { FootInchHistory.get(context).clear() })
        ActionRow("Clear memory", onClick = { FootInchHistory.get(context).clearMemory() })
    }

    SettingsGroup("Entering measurements") {
        Text(
            fractionText(
                when {
                    appSettings.isMetric ->
                        "Type a number then m, cm or mm — 1 m 20 cm  →  1.2 m.\n" +
                            "A number with no unit next to a length counts as millimeters: 1 m + 50 = 1.05 m.\n" +
                            "Feet and inches (5' 3\") also work using the quick ' and \" keys.\n" +
                            "Tap the result or a conversion to copy it. Long-press ⌫ to clear."
                    footInchSettings.keypadLayout == KeypadLayout.DUAL_SPLIT ->
                        "Dual-Split: tap blue Feet keys, green Inches keys, and the Fractions column.\n" +
                            "5 [Feet] 3 [Inches] [1/2]  →  5' 3 1/2\"     Tap '# Num' for plain numbers (× 3).\n" +
                            "Tap a tape line to use its result; long-press to copy, edit, or delete it."
                    else ->
                        "Type feet, then ' — inches — then tap a fraction key (or type \" and a fraction with /).\n" +
                            "5 ' 3 [1/2]  →  5' 3 1/2\"     [7/16]  →  7/16\"\n" +
                            "Metric units (mm, cm, m), parentheses, √, x², and memory keys are above the keypad.\n" +
                            "A number with no mark next to a length counts as inches: 5' + 6 = 5' 6\".\n" +
                            "Tap the result or a conversion to copy it. Long-press ⌫ to clear."
                } + "\n\nTape: chaining (= then +, −, ×, ÷) keeps steps together; a new number starts a new calculation. " +
                    "Edit any step and the later steps recompute. ⌫ right after = reopens the last entry to fix it.\n" +
                    "Memory: MS saves the value with a name, MR lists saved values to use, M+ / M− change the top one.",
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

package com.thedirepack.constructioncalc.feature.footinch

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    var showHistory by remember { mutableStateOf(false) }
    var showHelpers by remember { mutableStateOf(false) }
    val state = viewModel.state
    val entries by viewModel.history.entries.collectAsStateWithLifecycle()

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
                expression = viewModel.formatExpression(state.tokens, settings),
                result = state.result,
                isAnswer = state.answer != null,
                error = state.error,
                memory = viewModel.memory?.value,
                memoryPreferInches = viewModel.memory?.preferInches,
                onRecallMemory = { hapticAction(viewModel::memoryRecall) },
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
                hasMemory = viewModel.hasMemory,
                hasAns = viewModel.effectiveAns != null,
                onMemoryClear = { hapticAction(viewModel::memoryClear) },
                onMemoryRecall = { hapticAction(viewModel::memoryRecall) },
                onMemoryAdd = { hapticAction(viewModel::memoryAdd) },
                onMemorySubtract = { hapticAction(viewModel::memorySubtract) },
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
        HistorySheet(
            entries = entries,
            settings = settings,
            onPick = {
                press(Key.Recall(it.result.toQuantity(), it.preferInches))
                showHistory = false
            },
            onDelete = viewModel.history::remove,
            onRename = viewModel.history::setLabel,
            onClear = viewModel.history::clear,
            onDismiss = { showHistory = false },
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
    hasMemory: Boolean,
    hasAns: Boolean,
    onMemoryClear: () -> Unit,
    onMemoryRecall: () -> Unit,
    onMemoryAdd: () -> Unit,
    onMemorySubtract: () -> Unit,
    onAns: () -> Unit,
    onKey: (Key) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            MiniKey("MC", "Memory clear", enabled = hasMemory, onClick = onMemoryClear, modifier = Modifier.weight(1f))
            MiniKey("MR", "Memory recall", enabled = hasMemory, onClick = onMemoryRecall, modifier = Modifier.weight(1f))
            MiniKey("M+", "Memory add", onClick = onMemoryAdd, modifier = Modifier.weight(1f))
            MiniKey("M−", "Memory subtract", onClick = onMemorySubtract, modifier = Modifier.weight(1f))
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Display(
    expression: String,
    result: Quantity?,
    isAnswer: Boolean,
    error: String?,
    memory: Quantity?,
    memoryPreferInches: Boolean?,
    onRecallMemory: () -> Unit,
    settings: AppSettings,
    preferInches: Boolean?,
    showFractionKeys: Boolean,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.End,
    ) {
        if (memory != null && !memory.value.isZero) {
            val memText = FootInchFormat.primary(memory, settings, memoryPreferInches)
            Surface(
                onClick = onRecallMemory,
                shape = RoundedCornerShape(8.dp),
                color = scheme.secondaryContainer,
                contentColor = scheme.onSecondaryContainer,
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .semantics { contentDescription = "Memory $memText. Tap to recall." },
            ) {
                Text(
                    fractionText("M  $memText"),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
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
                    fontSize = 28.sp,
                ),
                maxLines = 3,
                autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = 28.sp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(8.dp))

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
                        fontSize = 56.sp,
                    ),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 22.sp, maxFontSize = if (isAnswer) 56.sp else 36.sp),
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
                    Spacer(Modifier.height(8.dp))
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun HistorySheet(
    entries: List<HistoryEntry>,
    settings: AppSettings,
    onPick: (HistoryEntry) -> Unit,
    onDelete: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var labeling by remember { mutableStateOf<HistoryEntry?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Calculation Tape", style = MaterialTheme.typography.titleLarge)
                if (entries.isNotEmpty()) {
                    Text(
                        "${entries.size} saved · tap to recall, long-press to label",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (entries.isNotEmpty()) TextButton(onClick = onClear) { Text("Clear all") }
        }
        if (entries.isEmpty()) {
            Text(
                "Finished calculations show up here. Tap one to recall its result, or long-press to add a note.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        } else {
            LazyColumn(Modifier.navigationBarsPadding()) {
                items(entries, key = { it.id }) { entry ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClickLabel = "Recall result",
                                onClick = { onPick(entry) },
                                onLongClickLabel = "Label calculation",
                                onLongClick = { labeling = entry },
                            )
                            .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            Modifier.weight(1f),
                            horizontalAlignment = Alignment.End,
                        ) {
                            if (entry.label.isNotBlank()) {
                                Text(
                                    entry.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    textAlign = TextAlign.End,
                                )
                            }
                            Text(
                                fractionText(entry.expression),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                            )
                            Text(
                                fractionText("= " + FootInchFormat.primary(entry.result.toQuantity(), settings, entry.preferInches)),
                                style = MaterialTheme.typography.titleLarge,
                                textAlign = TextAlign.End,
                            )
                        }
                        IconButton(onClick = { labeling = entry }) {
                            Icon(
                                painterResource(R.drawable.ic_edit),
                                contentDescription = "Label calculation",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onDelete(entry.id) }) {
                            Icon(
                                painterResource(R.drawable.ic_delete),
                                contentDescription = "Delete calculation",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    labeling?.let { entry ->
        TapeLabelDialog(
            initial = entry.label,
            onConfirm = { newLabel ->
                onRename(entry.id, newLabel)
                labeling = null
            },
            onDismiss = { labeling = null },
        )
    }
}

@Composable
private fun TapeLabelDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable(initial) { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Label calculation") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Note (blank to clear)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm(text.trim()) }),
                modifier = Modifier.focusRequester(focus),
            )
            LaunchedEffect(focus) { focus.requestFocus() }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Foot-Inch's page in the app's Settings screen. */
@Composable
internal fun FootInchSettingsPage() {
    val context = LocalContext.current
    val store = LocalAppServices.current.settings.module(FootInchTool.ID)
    val showFractionKeys = rememberModuleBoolean(FootInchTool.ID, FootInchPrefs.SHOW_FRACTION_KEYS, true)
    SettingsGroup("Keypad") {
        SwitchRow(
            "Fraction keys",
            checked = showFractionKeys,
            onCheckedChange = { store.setBoolean(FootInchPrefs.SHOW_FRACTION_KEYS, it) },
            subtitle = "One-tap ⅛ ¼ ½ … keys above the keypad.",
        )
    }
    SettingsGroup("History") {
        ActionRow("Clear calculation tape", onClick = { FootInchHistory.get(context).clear() })
    }
    val metric = rememberAppSettings().isMetric
    SettingsGroup("Entering measurements") {
        Text(
            fractionText(
                if (metric) {
                    "Type a number then m, cm or mm — 1 m 20 cm  →  1.2 m.\n" +
                        "A number with no unit next to a length counts as millimeters: 1 m + 50 = 1.05 m.\n" +
                        "Feet and inches (5' 3\") also work using the quick ' and \" keys.\n" +
                        "Tap the result or a conversion to copy it. Long-press ⌫ to clear."
                } else
                "Type feet, then ' — inches — then tap a fraction key (or type \" and a fraction with /).\n" +
                    "5 ' 3 [1/2]  →  5' 3 1/2\"     [7/16]  →  7/16\"\n" +
                    "Metric units (mm, cm, m), parentheses, √, x², and memory keys are above the keypad.\n" +
                    "A number with no mark next to a length counts as inches: 5' + 6 = 5' 6\".\n" +
                    "Tap the result or a conversion to copy it. Long-press ⌫ to clear.",
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

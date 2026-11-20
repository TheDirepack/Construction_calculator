package com.thedirepack.constructioncalc.feature.footinch

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
import com.thedirepack.constructioncalc.core.ui.lengthStyle
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
    val showFractionKeys = rememberModuleBoolean(FootInchTool.ID, FootInchPrefs.SHOW_FRACTION_KEYS, true)
    var showHistory by remember { mutableStateOf(false) }
    val state = viewModel.state

    val press = rememberKeyPress { key ->
        val outcome = viewModel.onKey(key, settings)
        outcome.completed?.let { calc ->
            host.publish(listOf(viewModel.resultValue(calc, settings)), context = viewModel.formatExpression(calc.expression, settings))
        }
        outcome.accepted
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
            IconButton(onClick = { showHistory = true }) {
                Icon(painterResource(R.drawable.ic_history), contentDescription = "History")
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
                settings = settings,
                preferInches = inches,
                showFractionKeys = showFractionKeys,
                onCopy = copy,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            ControlRow(
                precision = settings.precision,
                onPrecision = LocalAppServices.current.settings::setPrecision,
                onBackspace = { press(Key.Backspace) },
                onClear = { press(Key.Clear) },
            )
            if (showFractionKeys) FractionPad(settings.precision, onKey = press)
            Spacer(Modifier.height(8.dp))
            MeasureKeypad(onKey = press, modifier = Modifier.weight(1.45f).fillMaxWidth())
        }
    }

    if (showHistory) {
        val entries by viewModel.history.entries.collectAsStateWithLifecycle()
        HistorySheet(
            entries = entries,
            settings = settings,
            onPick = {
                press(Key.Recall(it.result.toQuantity(), it.preferInches))
                showHistory = false
            },
            onClear = viewModel.history::clear,
            onDismiss = { showHistory = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Display(
    expression: String,
    result: Quantity?,
    isAnswer: Boolean,
    error: String?,
    settings: AppSettings,
    preferInches: Boolean?,
    showFractionKeys: Boolean,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val style = settings.lengthStyle(preferInches)
    Column(
        modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.End,
    ) {
        if (expression.isEmpty()) {
            Text(
                fractionText(if (showFractionKeys) "Try  5  '  3  1/2  for  5' 3 1/2\"" else "Try  5 ' 3 \" 1 / 2  for  5' 3 1/2\""),
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
                val primary = MeasureFormat.primary(result, settings.precision, style)
                val rounding = MeasureFormat.roundingDirection(result, settings.precision)
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
                val conversions = MeasureFormat.conversions(result, settings.precision, style)
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
    precision: Precision,
    onPrecision: (Precision) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(
    entries: List<HistoryEntry>,
    settings: AppSettings,
    onPick: (HistoryEntry) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("History", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (entries.isNotEmpty()) TextButton(onClick = onClear) { Text("Clear") }
        }
        if (entries.isEmpty()) {
            Text(
                "Finished calculations show up here. Tap one to use its result.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        } else {
            LazyColumn(Modifier.navigationBarsPadding()) {
                items(entries) { entry ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(entry) }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        Text(fractionText(entry.expression), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
                        val style = settings.lengthStyle(entry.preferInches)
                        Text(
                            fractionText("= " + MeasureFormat.primary(entry.result.toQuantity(), settings.precision, style)),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 24.dp))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
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
        ActionRow("Clear calculation history", onClick = { FootInchHistory.get(context).clear() })
    }
    SettingsGroup("Entering measurements") {
        Text(
            fractionText(
                "Type feet, then ' — inches — then tap a fraction key (or type \" and a fraction with /).\n" +
                    "5 ' 3 [1/2]  →  5' 3 1/2\"     [7/16]  →  7/16\"\n" +
                    "A number with no mark next to a length counts as inches: 5' + 6 = 5' 6\".\n" +
                    "Tap the result or a conversion to copy it. Long-press ⌫ to clear.",
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

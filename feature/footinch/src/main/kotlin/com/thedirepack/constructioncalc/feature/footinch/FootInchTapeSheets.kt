package com.thedirepack.constructioncalc.feature.footinch

import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.measure.ExpressionText
import com.thedirepack.constructioncalc.core.measure.Operator
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.Token
import com.thedirepack.constructioncalc.core.measure.input.CalcInput
import com.thedirepack.constructioncalc.core.measure.input.CalcState
import com.thedirepack.constructioncalc.core.measure.input.Key
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.components.FractionPad
import com.thedirepack.constructioncalc.core.ui.components.MeasureKeypad
import com.thedirepack.constructioncalc.core.ui.components.rememberKeyPress
import com.thedirepack.constructioncalc.core.ui.fractionText
import com.thedirepack.constructioncalc.core.ui.isMetric
import com.thedirepack.constructioncalc.core.ui.lengthFormat

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun GroupedHistorySheet(
    groups: List<CalcGroup>,
    activeGroupId: Long?,
    settings: AppSettings,
    onContinueGroup: (CalcGroup) -> Unit,
    onEditStep: (groupId: Long, stepId: Long) -> Unit,
    onRecallStep: (CalcStep) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onRenameGroup: (Long, String) -> Unit,
    onRenameStep: (Long, Long, String) -> Unit,
    onDeleteGroup: (Long) -> Unit,
    onClearRecent: () -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    var favoritesOnly by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var labelingGroup by remember { mutableStateOf<CalcGroup?>(null) }
    var labelingStep by remember { mutableStateOf<Pair<Long, CalcStep>?>(null) }

    val favoriteCount = remember(groups) { groups.count { it.favorite } }
    val visibleGroups = remember(groups, favoritesOnly) {
        if (favoritesOnly) groups.filter { it.favorite } else groups
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Calculation Tape", style = MaterialTheme.typography.titleLarge)
                if (groups.isNotEmpty()) {
                    Text(
                        "Tap a line to use its result · ✎ to fix a step (later steps update)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (groups.isNotEmpty()) {
                if (favoriteCount > 0 && favoriteCount < groups.size) {
                    TextButton(onClick = onClearRecent) { Text("Clear recent") }
                } else {
                    TextButton(onClick = onClearAll) { Text("Clear all") }
                }
            }
        }

        if (favoriteCount > 0) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = !favoritesOnly,
                    onClick = { favoritesOnly = false },
                    label = { Text("All (${groups.size})") },
                )
                FilterChip(
                    selected = favoritesOnly,
                    onClick = { favoritesOnly = true },
                    label = { Text("Starred ($favoriteCount)") },
                )
                if (favoriteCount == groups.size && groups.isNotEmpty()) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClearAll) { Text("Clear all") }
                }
            }
        }

        errorMessage?.let { msg ->
            ErrorBanner(msg, onDismiss = { errorMessage = null }, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        }

        if (visibleGroups.isEmpty()) {
            Text(
                if (favoritesOnly) "No starred calculations yet. Star one to keep it when you clear the tape."
                else "Finished calculations show up here. Steps you chain together (= then +, −, ×, ÷) stay on one card; typing a new number starts a new card.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        } else {
            LazyColumn(
                Modifier.navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(visibleGroups, key = { it.id }) { group ->
                    val isActive = group.id == activeGroupId
                    val scheme = MaterialTheme.colorScheme
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isActive) scheme.surfaceContainer else scheme.surfaceContainerLow,
                        border = BorderStroke(
                            width = if (isActive) 1.5.dp else 1.dp,
                            color = if (isActive) scheme.primary else scheme.outlineVariant,
                        ),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            group.label.ifBlank {
                                                "Calculation · ${group.steps.size} step${if (group.steps.size == 1) "" else "s"}"
                                            },
                                            style = MaterialTheme.typography.titleSmall,
                                            color = if (group.label.isNotBlank()) scheme.primary else scheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (isActive) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = scheme.primaryContainer,
                                                contentColor = scheme.onPrimaryContainer,
                                            ) {
                                                Text(
                                                    "On display",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                )
                                            }
                                        }
                                    }
                                    if (group.label.isNotBlank()) {
                                        Text(
                                            "${group.steps.size} step${if (group.steps.size == 1) "" else "s"}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = scheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                TextButton(onClick = {
                                    onContinueGroup(group)
                                    onDismiss()
                                }) {
                                    Text("Continue")
                                }
                                IconButton(
                                    onClick = { onToggleFavorite(group.id) },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        painterResource(if (group.favorite) R.drawable.ic_star else R.drawable.ic_star_border),
                                        contentDescription = if (group.favorite) "Unstar calculation" else "Star calculation (kept when clearing)",
                                        tint = if (group.favorite) scheme.primary else scheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(
                                    onClick = { labelingGroup = group },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_edit),
                                        contentDescription = "Name calculation",
                                        tint = scheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(
                                    onClick = { onDeleteGroup(group.id) },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_delete),
                                        contentDescription = "Delete calculation",
                                        tint = scheme.onSurfaceVariant,
                                    )
                                }
                            }

                            HorizontalDivider(Modifier.padding(horizontal = 14.dp, vertical = 4.dp))

                            group.steps.forEachIndexed { idx, step ->
                                val prevPrefer = if (idx > 0) group.steps[idx - 1].preferInches else null
                                val stepInput = CalculationTapeOps.formatStepInput(step, settings, prevPrefer)
                                val qty = step.result.toQuantity()
                                val primaryText = FootInchFormat.primary(qty, settings, step.preferInches)
                                val altText = FootInchFormat.conversions(qty, settings, step.preferInches).firstOrNull()?.value
                                val isFinal = idx == group.steps.lastIndex

                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClickLabel = "Use step ${idx + 1} result",
                                            onClick = {
                                                onRecallStep(step)
                                                onDismiss()
                                            },
                                            onLongClickLabel = "Label step ${idx + 1}",
                                            onLongClick = { labelingStep = group.id to step },
                                        )
                                        .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        if (step.label.isNotBlank()) {
                                            Text(step.label, style = MaterialTheme.typography.labelSmall, color = scheme.primary)
                                        }
                                        Text(
                                            fractionText(stepInput),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = scheme.onSurfaceVariant,
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            fractionText("= $primaryText"),
                                            style = if (isFinal) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                                            fontWeight = if (isFinal) FontWeight.SemiBold else FontWeight.Medium,
                                            color = if (isFinal) scheme.primary else scheme.onSurface,
                                            textAlign = TextAlign.End,
                                        )
                                        if (altText != null) {
                                            Text(
                                                fractionText(altText),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = scheme.onSurfaceVariant,
                                                textAlign = TextAlign.End,
                                            )
                                        }
                                    }
                                    IconButton(onClick = { onEditStep(group.id, step.id) }) {
                                        Icon(
                                            painterResource(R.drawable.ic_edit),
                                            contentDescription = "Edit step ${idx + 1}",
                                            modifier = Modifier.size(18.dp),
                                            tint = scheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (idx < group.steps.lastIndex) {
                                    HorizontalDivider(
                                        Modifier.padding(start = 28.dp, end = 14.dp),
                                        color = scheme.outlineVariant.copy(alpha = 0.5f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    labelingGroup?.let { group ->
        NoteLabelDialog(
            title = "Name calculation",
            initial = group.label,
            onConfirm = { label ->
                onRenameGroup(group.id, label)
                labelingGroup = null
            },
            onDismiss = { labelingGroup = null },
        )
    }

    labelingStep?.let { (groupId, step) ->
        NoteLabelDialog(
            title = "Label step",
            initial = step.label,
            onConfirm = { label ->
                onRenameStep(groupId, step.id, label)
                labelingStep = null
            },
            onDismiss = { labelingStep = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditStepSheet(
    group: CalcGroup,
    step: CalcStep,
    stepIndex: Int,
    settings: AppSettings,
    onRenameStep: (String) -> Unit,
    onDeleteStep: () -> String?,
    onApply: (List<SavedToken>) -> String?,
    onDismiss: () -> Unit,
) {
    val prevStep = group.steps.getOrNull(stepIndex - 1)
    val prevQty = prevStep?.result?.toQuantity()
    val prevPreferInches = prevStep?.preferInches

    val spec = remember(group.id, step.id, step.tokens, stepIndex) {
        CalculationTapeOps.classifyForEdit(step, stepIndex)
    }

    var selectedOp by remember(group.id, step.id) {
        mutableStateOf((spec as? EditableStepSpec.BinaryContinuation)?.operator ?: Operator.ADD)
    }

    var workingCompoundTokens by remember(group.id, step.id) {
        mutableStateOf(
            when (spec) {
                is EditableStepSpec.FullExpression -> spec.tokens
                is EditableStepSpec.BinaryContinuation -> emptyList()
            },
        )
    }
    val compoundValueIndices = remember(workingCompoundTokens) {
        CalculationTapeOps.editableValueIndices(workingCompoundTokens)
    }
    var selectedChipTokenIndex by remember(group.id, step.id) {
        mutableStateOf<Int?>(
            if (spec is EditableStepSpec.FullExpression && compoundValueIndices.size >= 2) {
                compoundValueIndices.first()
            } else {
                null
            },
        )
    }

    fun initialCalcStateForSelection(chipIndex: Int?): CalcState {
        val tokens = when {
            spec is EditableStepSpec.BinaryContinuation ->
                spec.operandTokens.map { it.toToken(prevQty, prevPreferInches) }
            chipIndex != null && chipIndex in workingCompoundTokens.indices ->
                listOf(workingCompoundTokens[chipIndex].toToken(prevQty, prevPreferInches))
            workingCompoundTokens.isEmpty() ->
                listOf(Token.Value(step.result.toQuantity(), step.preferInches))
            else ->
                workingCompoundTokens.map { it.toToken(prevQty, prevPreferInches) }
        }
        return CalcState(tokens = tokens)
    }

    var calcState by remember(group.id, step.id) {
        mutableStateOf(initialCalcStateForSelection(selectedChipTokenIndex))
    }
    var armedReplace by remember(group.id, step.id) {
        mutableStateOf(
            calcState.tokens.size == 1 &&
                (calcState.tokens[0] is Token.Literal || calcState.tokens[0] is Token.Value),
        )
    }
    var actionError by remember { mutableStateOf<String?>(null) }
    var showLabelDialog by remember { mutableStateOf(false) }

    val candidateTokens: List<SavedToken> = remember(spec, selectedOp, workingCompoundTokens, selectedChipTokenIndex, calcState.tokens) {
        val edited = calcState.tokens.map(SavedToken::ofForTape)
        when (spec) {
            is EditableStepSpec.BinaryContinuation -> {
                if (edited.isEmpty()) emptyList()
                else listOf(SavedToken.PrevAnswer, SavedToken.Op(selectedOp)) + edited
            }
            is EditableStepSpec.FullExpression -> {
                val chipIdx = selectedChipTokenIndex
                if (chipIdx != null) {
                    if (edited.isEmpty()) emptyList()
                    else CalculationTapeOps.replaceToken(workingCompoundTokens, chipIdx, edited)
                } else {
                    edited
                }
            }
        }
    }

    val liveCascade = remember(group, step.id, candidateTokens) {
        CalculationTapeOps.editStep(listOf(group), group.id, step.id, candidateTokens)
    }
    val canApply = liveCascade is TapeEditResult.Updated && liveCascade.group != null

    fun switchChipTarget(nextChipIndex: Int?) {
        actionError = null
        val curChip = selectedChipTokenIndex
        val edited = calcState.tokens.map(SavedToken::ofForTape)
        val committed = when {
            edited.isEmpty() -> workingCompoundTokens
            curChip != null -> CalculationTapeOps.replaceToken(workingCompoundTokens, curChip, edited)
            else -> edited
        }
        workingCompoundTokens = committed
        val updatedIndices = CalculationTapeOps.editableValueIndices(committed)
        val clampedNext = nextChipIndex?.takeIf { it in committed.indices && it in updatedIndices }
        selectedChipTokenIndex = clampedNext
        val nextTokens = if (clampedNext != null) {
            listOf(committed[clampedNext].toToken(prevQty, prevPreferInches))
        } else {
            committed.map { it.toToken(prevQty, prevPreferInches) }
        }
        calcState = CalcState(tokens = nextTokens)
        armedReplace = nextTokens.size == 1 &&
            (nextTokens[0] is Token.Literal || nextTokens[0] is Token.Value)
    }

    val handleKey: (Key) -> Boolean = handle@{ key ->
        actionError = null
        if (key == Key.Equals) {
            if (canApply) {
                val err = onApply(candidateTokens)
                if (err != null) actionError = err
                return@handle err == null
            }
            return@handle false
        }
        val shouldClearFirst = armedReplace && when (key) {
            is Key.Char -> key.char.isDigit() || key.char == '.'
            is Key.Recall, Key.OpenParen, Key.Sqrt -> true
            else -> false
        }
        val baseState = if (shouldClearFirst) CalcState() else calcState
        armedReplace = false
        val outcome = CalcInput.reduce(baseState, key)
        calcState = outcome.state
        outcome.accepted
    }
    val press = rememberKeyPress(handleKey)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Edit Step ${stepIndex + 1} of ${group.steps.size}",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    if (prevQty != null && prevStep != null) {
                        Text(
                            fractionText("Previous result: ${FootInchFormat.primary(prevQty, settings, prevPreferInches)}"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (step.label.isNotBlank()) {
                        Text(step.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                IconButton(onClick = { showLabelDialog = true }) {
                    Icon(painterResource(R.drawable.ic_edit), contentDescription = "Label step")
                }
                IconButton(onClick = {
                    val err = onDeleteStep()
                    if (err != null) actionError = err
                }) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = "Delete step")
                }
            }

            if (spec is EditableStepSpec.BinaryContinuation) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Operator.entries.forEach { op ->
                        FilterChip(
                            selected = selectedOp == op,
                            onClick = {
                                selectedOp = op
                                actionError = null
                            },
                            label = { Text(op.symbol, fontWeight = FontWeight.SemiBold) },
                        )
                    }
                }
            } else if (compoundValueIndices.size >= 2) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    compoundValueIndices.forEachIndexed { n, tokIdx ->
                        val label = CalculationTapeOps.formatTokens(
                            listOf(workingCompoundTokens[tokIdx]),
                            settings,
                            prevPreferInches,
                        )
                        FilterChip(
                            selected = selectedChipTokenIndex == tokIdx,
                            onClick = { switchChipTarget(tokIdx) },
                            label = { Text(fractionText("#${n + 1}: $label")) },
                        )
                    }
                    FilterChip(
                        selected = selectedChipTokenIndex == null,
                        onClick = { switchChipTarget(null) },
                        label = { Text("Full step") },
                    )
                }
            }

            // Current edited expression / operand display
            val displayExpr = remember(calcState.tokens, settings) {
                ExpressionText.format(calcState.tokens, settings.lengthFormat(CalcInput.prefersInches(calcState.tokens)))
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when {
                            armedReplace -> "Type to replace · ⌫ to edit"
                            selectedChipTokenIndex != null -> "Editing value"
                            else -> "Expression"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        fractionText(displayExpr.ifEmpty { " " }),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.End,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { press(Key.Backspace) }, modifier = Modifier.size(36.dp)) {
                        Icon(painterResource(R.drawable.ic_backspace), contentDescription = "Backspace")
                    }
                }
            }

            // Live cascade preview / validation status
            when {
                actionError != null -> Text(
                    actionError!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                liveCascade is TapeEditResult.Invalid -> Text(
                    liveCascade.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                liveCascade is TapeEditResult.Updated && liveCascade.group != null -> {
                    val updatedGroup = liveCascade.group
                    val updatedStep = updatedGroup.steps[stepIndex]
                    val stepResultText = FootInchFormat.primary(
                        updatedStep.result.toQuantity(),
                        settings,
                        updatedStep.preferInches,
                    )
                    val downstreamCount = updatedGroup.steps.lastIndex - stepIndex
                    val previewText = if (downstreamCount > 0) {
                        val finalStep = updatedGroup.finalStep!!
                        val finalResultText = FootInchFormat.primary(
                            finalStep.result.toQuantity(),
                            settings,
                            finalStep.preferInches,
                        )
                        "Step ${stepIndex + 1} = $stepResultText  ·  $downstreamCount later step${if (downstreamCount == 1) "" else "s"} update → total $finalResultText"
                    } else {
                        "New result: $stepResultText"
                    }
                    Text(
                        fractionText(previewText),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (selectedChipTokenIndex == null && spec is EditableStepSpec.FullExpression) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf(
                        Triple("(", "Open parenthesis") { press(Key.OpenParen) },
                        Triple(")", "Close parenthesis") { press(Key.CloseParen) },
                        Triple("√", "Square root") { press(Key.Sqrt) },
                        Triple("x²", "Square") { press(Key.Square) },
                    ).forEach { (lbl, desc, click) ->
                        EditMiniKey(lbl, desc, onClick = click, modifier = Modifier.weight(1f))
                    }
                    if (prevQty != null) {
                        EditMiniKey(
                            "Ans",
                            "Insert previous step result",
                            onClick = { press(Key.Recall(prevQty, prevPreferInches, fromPreviousAnswer = true)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (!settings.isMetric) {
                FractionPad(settings.precision, onKey = press)
            }
            MeasureKeypad(
                onKey = press,
                equalsLabel = "Apply",
                metric = settings.isMetric,
                modifier = Modifier.fillMaxWidth().height(248.dp),
            )

            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    enabled = canApply,
                    onClick = {
                        val err = onApply(candidateTokens)
                        if (err != null) actionError = err
                    },
                ) {
                    Text("Apply")
                }
            }
        }
    }

    if (showLabelDialog) {
        NoteLabelDialog(
            title = "Label step",
            initial = step.label,
            onConfirm = { newLabel ->
                onRenameStep(newLabel)
                showLabelDialog = false
            },
            onDismiss = { showLabelDialog = false },
        )
    }
}

@Composable
private fun EditMiniKey(
    label: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .height(32.dp)
            .semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

/** Named memory: tap a value to use it; rename, delete, or clear. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemorySheet(
    entries: List<MemoryEntry>,
    currentResult: Quantity?,
    currentPreferInches: Boolean?,
    settings: AppSettings,
    onSaveCurrent: () -> Unit,
    onRecall: (MemoryEntry) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    var renaming by remember { mutableStateOf<MemoryEntry?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Memory", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (entries.isEmpty()) "Save values you'll need again, with a name." else "Tap a value to use it. M+ and M− change the top one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            if (entries.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear all") }
        }

        val curText = currentResult?.let { FootInchFormat.primary(it, settings, currentPreferInches) }
        Button(
            enabled = currentResult != null,
            onClick = onSaveCurrent,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Text(fractionText(if (curText != null) "Save $curText…" else "Calculate a value to save it"))
        }

        LazyColumn(
            Modifier.navigationBarsPadding().heightIn(max = 460.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(entries, key = { _, it -> it.id }) { index, entry ->
                val qty = entry.value.toQuantity()
                val primary = FootInchFormat.primary(qty, settings, entry.preferInches)
                Surface(
                    onClick = {
                        onRecall(entry)
                        onDismiss()
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = if (index == 0) scheme.secondaryContainer.copy(alpha = 0.55f) else scheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, scheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${entry.label}: $primary. Tap to use." },
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.label, style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                            Text(fractionText(primary), style = MaterialTheme.typography.titleLarge)
                        }
                        IconButton(onClick = { renaming = entry }) {
                            Icon(painterResource(R.drawable.ic_edit), contentDescription = "Rename ${entry.label}")
                        }
                        IconButton(onClick = { onDelete(entry.id) }) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = "Delete ${entry.label}")
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    renaming?.let { entry ->
        NoteLabelDialog(
            title = "Rename memory",
            initial = entry.label,
            fieldLabel = "Name",
            onConfirm = { name ->
                onRename(entry.id, name)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all memory?") },
            text = { Text("All ${entries.size} saved value${if (entries.size == 1) "" else "s"} will be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    onClearAll()
                    confirmClear = false
                }) { Text("Clear all") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

/** Names a value before saving it to memory. A name already in memory is updated instead of duplicated. */
@Composable
internal fun SaveMemoryDialog(
    valueText: String,
    suggestedName: String,
    existingNames: List<String>,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val finalName = name.trim().ifEmpty { suggestedName }
    val replaces = existingNames.any { it.equals(finalName, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(fractionText("Save $valueText")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Name") },
                    placeholder = { Text(suggestedName) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSave(finalName) }),
                    modifier = Modifier.focusRequester(focus),
                )
                Text(
                    if (replaces) "Replaces the value saved as \"$finalName\"." else "For example: Header, Stud spacing, Wall A.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LaunchedEffect(focus) { focus.requestFocus() }
        },
        confirmButton = { TextButton(onClick = { onSave(finalName) }) { Text(if (replaces) "Replace" else "Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The compact tape above the display, newest at the bottom. The calculation on the display shows
 * every step; earlier ones collapse to one line. Tap a line to use its result; long-press for more.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun InlineCalculationTape(
    groups: List<CalcGroup>,
    activeGroupId: Long?,
    settings: AppSettings,
    onRecall: (Quantity, Boolean?) -> Unit,
    onCopy: (String) -> Unit,
    onEditStep: (groupId: Long, stepId: Long) -> Unit,
    onLabelStep: (groupId: Long, step: CalcStep) -> Unit,
    onDeleteStep: (groupId: Long, stepId: Long) -> Unit,
    onContinue: (CalcGroup) -> Unit,
    onNameGroup: (CalcGroup) -> Unit,
    onDeleteGroup: (Long) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    // Newest first; reverseLayout puts it at the bottom. Steps of the active group, one line for others.
    val rows = remember(groups, activeGroupId) {
        buildList {
            groups.forEach { g ->
                if (g.id == activeGroupId) {
                    g.steps.indices.reversed().forEach { add(InlineRow.Step(g, it)) }
                } else {
                    add(InlineRow.Group(g))
                }
            }
        }
    }
    LaunchedEffect(rows.firstOrNull()?.key) { if (rows.isNotEmpty()) listState.scrollToItem(0) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp)) {
            itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                var menuOpen by remember { mutableStateOf(false) }
                val line = when (row) {
                    is InlineRow.Step -> {
                        val step = row.group.steps[row.index]
                        val prev = row.group.steps.getOrNull(row.index - 1)
                        InlineText(
                            CalculationTapeOps.formatStepInput(step, settings, prev?.preferInches),
                            step.result.toQuantity(),
                            step.preferInches,
                            step.label,
                        )
                    }
                    is InlineRow.Group -> InlineText(
                        CalculationTapeOps.groupSummary(row.group.copy(label = ""), settings),
                        row.group.finalResult!!.toQuantity(),
                        row.group.finalPreferInches,
                        row.group.label,
                    )
                }
                val resultText = FootInchFormat.primary(line.result, settings, line.preferInches)
                // A heavier line separates calculations; a hairline separates steps of one calculation.
                val next = rows.getOrNull(index + 1)
                val sameGroupAsNext = row is InlineRow.Step && next is InlineRow.Step && next.group.id == row.group.id
                Column(Modifier.fillMaxWidth()) {
                    if (index < rows.lastIndex) {
                        HorizontalDivider(
                            thickness = if (sameGroupAsNext) 0.5.dp else 1.5.dp,
                            color = scheme.outlineVariant.copy(alpha = if (sameGroupAsNext) 0.3f else 0.6f),
                        )
                    }
                    Box {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClickLabel = "Use result",
                                    onClick = { onRecall(line.result, line.preferInches) },
                                    onLongClickLabel = "More actions",
                                    onLongClick = { menuOpen = true },
                                )
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (line.label.isNotBlank()) {
                                Surface(shape = RoundedCornerShape(6.dp), color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer) {
                                    Text(
                                        line.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            Text(
                                fractionText(line.input),
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                fractionText("= $resultText"),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = scheme.primary,
                                maxLines = 1,
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            val actions: List<Triple<String, Boolean, () -> Unit>> = buildList {
                                add(Triple("Copy result", false) { onCopy(resultText) })
                                when (row) {
                                    is InlineRow.Step -> {
                                        val step = row.group.steps[row.index]
                                        add(Triple("Edit step", false) { onEditStep(row.group.id, step.id) })
                                        add(Triple(if (step.label.isBlank()) "Add note" else "Edit note", false) { onLabelStep(row.group.id, step) })
                                        add(Triple("Delete step", true) { onDeleteStep(row.group.id, step.id) })
                                    }
                                    is InlineRow.Group -> {
                                        add(Triple("Continue this calculation", false) { onContinue(row.group) })
                                        add(Triple(if (row.group.label.isBlank()) "Name" else "Rename", false) { onNameGroup(row.group) })
                                        add(Triple("Delete", true) { onDeleteGroup(row.group.id) })
                                    }
                                }
                            }
                            actions.forEach { (text, danger, action) ->
                                DropdownMenuItem(
                                    text = { Text(text, color = if (danger) scheme.error else Color.Unspecified) },
                                    onClick = {
                                        menuOpen = false
                                        action()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private sealed interface InlineRow {
    val key: String

    data class Step(val group: CalcGroup, val index: Int) : InlineRow {
        override val key: String get() = "s${group.id}-${group.steps[index].id}"
    }

    data class Group(val group: CalcGroup) : InlineRow {
        override val key: String get() = "g${group.id}"
    }
}

private data class InlineText(val input: String, val result: Quantity, val preferInches: Boolean?, val label: String)

@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = "Dismiss error", modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
internal fun NoteLabelDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    fieldLabel: String = "Note (blank to clear)",
) {
    var text by rememberSaveable(initial) { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(fieldLabel) },
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

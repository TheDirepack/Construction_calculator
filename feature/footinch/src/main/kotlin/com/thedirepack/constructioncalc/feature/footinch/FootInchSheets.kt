package com.thedirepack.constructioncalc.feature.footinch

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
    onDeleteStep: (groupId: Long, stepId: Long) -> String?,
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
                        "${groups.size} calculation${if (groups.size == 1) "" else "s"} · tap a step to edit, tap total to recall",
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
                    label = { Text("Favorites ($favoriteCount)") },
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
                if (favoritesOnly) "No favorite calculations yet. Tap the star on a calculation to keep it."
                else "Finished calculations show up here grouped by sequence. Starting from scratch begins a new card.",
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
                                                    "Active",
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
                                        contentDescription = if (group.favorite) "Remove favorite" else "Favorite calculation",
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
                                            onClickLabel = "Edit step ${idx + 1}",
                                            onClick = {
                                                errorMessage = null
                                                onEditStep(group.id, step.id)
                                            },
                                            onLongClickLabel = "Label step ${idx + 1}",
                                            onLongClick = { labelingStep = group.id to step },
                                        )
                                        .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        if (step.label.isNotBlank()) {
                                            Text(
                                                step.label,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = scheme.primary,
                                            )
                                        }
                                        Text(
                                            fractionText("${idx + 1}. $stepInput"),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = scheme.onSurfaceVariant,
                                        )
                                    }
                                    Column(
                                        horizontalAlignment = Alignment.End,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable(onClickLabel = "Recall step result") {
                                                onRecallStep(step)
                                                onDismiss()
                                            }
                                            .padding(horizontal = 8.dp, vertical = 2.dp),
                                    ) {
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
                                    IconButton(
                                        onClick = {
                                            errorMessage = onDeleteStep(group.id, step.id)
                                        },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(
                                            painterResource(R.drawable.ic_close),
                                            contentDescription = "Remove step",
                                            modifier = Modifier.size(16.dp),
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
                        "Step ${stepIndex + 1} = $stepResultText  ·  Updates $downstreamCount later step${if (downstreamCount == 1) "" else "s"} → Total: $finalResultText"
                    } else {
                        "Updated result = $stepResultText"
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemorySheet(
    entries: List<MemoryEntry>,
    activeId: Long?,
    currentResult: Quantity?,
    currentPreferInches: Boolean?,
    settings: AppSettings,
    onStoreCurrent: () -> Unit,
    onSelectActive: (Long) -> Unit,
    onRecall: (MemoryEntry) -> Unit,
    onApplyOp: (Long, Operator) -> String?,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var labeling by remember { mutableStateOf<MemoryEntry?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Memory Registers", style = MaterialTheme.typography.titleLarge)
                val status = if (entries.isEmpty()) "No stored values yet"
                else "${entries.size} saved slot${if (entries.size == 1) "" else "s"} · tap to recall"
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (entries.isNotEmpty()) {
                TextButton(onClick = onClearAll) { Text("Clear all") }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                enabled = currentResult != null,
                onClick = {
                    errorMessage = null
                    onStoreCurrent()
                },
            ) {
                val curText = currentResult?.let { FootInchFormat.primary(it, settings, currentPreferInches) }
                Text(fractionText(if (curText != null) "Store $curText (MS)" else "Store current (MS)"))
            }
        }

        errorMessage?.let { msg ->
            ErrorBanner(msg, onDismiss = { errorMessage = null }, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }

        if (entries.isEmpty()) {
            Text(
                "Tap MS to store measurements in memory, then recall or combine them with M+, M−, M×, and M÷.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        } else {
            LazyColumn(
                Modifier.navigationBarsPadding().heightIn(max = 460.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    val isActive = entry.id == activeId
                    val qty = entry.value.toQuantity()
                    val primary = FootInchFormat.primary(qty, settings, entry.preferInches)
                    val secondary = FootInchFormat.conversions(qty, settings, entry.preferInches).firstOrNull()?.value
                    val scheme = MaterialTheme.colorScheme

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isActive) scheme.secondaryContainer.copy(alpha = 0.55f) else scheme.surfaceContainerLow,
                        border = BorderStroke(
                            width = if (isActive) 1.5.dp else 1.dp,
                            color = if (isActive) scheme.primary else scheme.outlineVariant,
                        ),
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable {
                            onRecall(entry)
                            onDismiss()
                        },
                    ) {
                        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 6.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (entry.label.isNotBlank()) {
                                            Text(entry.label, style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                                        }
                                        if (isActive) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = scheme.primary,
                                                contentColor = scheme.onPrimary,
                                            ) {
                                                Text(
                                                    "Active",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                                )
                                            }
                                        }
                                    }
                                    Text(fractionText(primary), style = MaterialTheme.typography.titleLarge)
                                    if (secondary != null) {
                                        Text(
                                            fractionText(secondary),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = scheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                IconButton(onClick = { labeling = entry }, modifier = Modifier.size(36.dp)) {
                                    Icon(painterResource(R.drawable.ic_edit), contentDescription = "Label memory slot")
                                }
                                IconButton(onClick = { onDelete(entry.id) }, modifier = Modifier.size(36.dp)) {
                                    Icon(painterResource(R.drawable.ic_delete), contentDescription = "Clear memory slot")
                                }
                            }
                            Row(
                                Modifier.fillMaxWidth().padding(end = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (!isActive) {
                                    TextButton(onClick = { onSelectActive(entry.id) }) { Text("Set active") }
                                }
                                Spacer(Modifier.weight(1f))
                                listOf(
                                    Operator.ADD to "M+",
                                    Operator.SUBTRACT to "M−",
                                    Operator.MULTIPLY to "M×",
                                    Operator.DIVIDE to "M÷",
                                ).forEach { (op, lbl) ->
                                    TextButton(
                                        enabled = currentResult != null,
                                        onClick = { errorMessage = onApplyOp(entry.id, op) },
                                    ) {
                                        Text(lbl)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    labeling?.let { entry ->
        NoteLabelDialog(
            title = "Label memory slot",
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

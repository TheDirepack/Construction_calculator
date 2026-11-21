package com.thedirepack.constructioncalc.core.ui.components

import com.thedirepack.constructioncalc.core.ui.lengthFormat

import com.thedirepack.constructioncalc.core.ui.isMetric

import com.thedirepack.constructioncalc.core.measure.Units

import com.thedirepack.constructioncalc.core.model.ValueKind
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thedirepack.constructioncalc.core.measure.Dimension
import com.thedirepack.constructioncalc.core.measure.ExpressionText
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.Token
import com.thedirepack.constructioncalc.core.measure.input.CalcInput
import com.thedirepack.constructioncalc.core.measure.input.CalcState
import com.thedirepack.constructioncalc.core.measure.input.Key
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.format
import com.thedirepack.constructioncalc.core.ui.fractionText
import com.thedirepack.constructioncalc.core.ui.lengthStyle
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices

/**
 * The feet-inch calculator as an input: type `12' 6 1/2"`, or `10' − 3 3/8"`, or tap a value from
 * another tool, then **Use**. Returns a [Quantity] whose dimension is in [allowed].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasureInputSheet(
    title: String,
    initial: Quantity?,
    onDone: (Quantity) -> Unit,
    onDismiss: () -> Unit,
    allowed: Set<Dimension> = setOf(Dimension.LENGTH),
) {
    val settings = rememberAppSettings()
    val recents by LocalAppServices.current.recents.recents.collectAsStateWithLifecycle()
    var state by remember { mutableStateOf(CalcState(tokens = listOfNotNull(initial?.let { Token.Value(it) }))) }
    var problem by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    val lengthOnly = allowed.size == 1 && Dimension.LENGTH in allowed
    val metric = settings.isMetric

    // A bare number in a length-only field is in the system's small unit: inches, or millimeters in metric.
    fun fieldValue(value: Quantity, tokens: List<Token>): Quantity =
        if (lengthOnly && value.dimension == Dimension.NUMBER && tokens.singleOrNull() is Token.Literal) {
            Quantity.inches(if (metric) value.value / Units.MM_PER_INCH else value.value)
        } else {
            value
        }

    val handle: (Key) -> Boolean = handle@{ key ->
        problem = null
        val outcome = CalcInput.reduce(state, key)
        state = outcome.state
        val done = outcome.completed ?: return@handle outcome.accepted
        val result = fieldValue(done.result, done.expression)
        if (result.dimension in allowed) {
            onDone(result)
        } else {
            problem = "Needs ${allowed.joinToString(" or ") { it.name.lowercase() }}, got ${done.result.dimension.name.lowercase()}"
            state = state.copy(answer = null)
        }
        true
    }
    val press = rememberKeyPress(handle)
    val quickValues = remember(recents, allowed) {
        recents.mapNotNull { r -> r.value.toQuantity()?.takeIf { it.dimension in allowed }?.let { r to it } }
            .distinctBy { it.second }
            .take(8)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { picking = true }) {
                    Icon(painterResource(R.drawable.ic_pick), contentDescription = "Use a value from another tool or the project")
                }
                IconButton(onClick = { press(Key.Backspace) }) {
                    Icon(painterResource(R.drawable.ic_backspace), contentDescription = "Backspace")
                }
            }
            Text(
                fractionText(ExpressionText.format(state.tokens, settings.lengthFormat(CalcInput.prefersInches(state.tokens))).ifEmpty { " " }),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
            )
            if (lengthOnly) {
                Text(
                    if (metric) "A number without a unit is in millimeters." else "A number without a unit is in inches.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val preview = state.result?.let { fieldValue(it, state.tokens) }
            Text(
                when {
                    problem != null -> problem!!
                    state.error != null -> state.error!!
                    preview != null && state.tokens.any { it !is Token.Value } -> "= " + settings.format(preview, CalcInput.prefersInches(state.tokens))
                    else -> " "
                }.let { fractionText(it) },
                style = MaterialTheme.typography.titleMedium,
                color = if (problem != null || state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
            )
            if (quickValues.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    quickValues.forEach { (recent, quantity) ->
                        AssistChip(
                            onClick = { press(Key.Recall(quantity)) },
                            label = { Text(fractionText("${recent.value.label}: ${settings.format(quantity)}"), maxLines = 1) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (!metric) {
                FractionPad(settings.precision, onKey = press)
                Spacer(Modifier.height(8.dp))
            }
            MeasureKeypad(onKey = press, equalsLabel = "Use", metric = metric, modifier = Modifier.fillMaxWidth().height(320.dp))
            Spacer(Modifier.height(12.dp))
        }
    }
    if (picking) {
        ValuePickerSheet(
            title = title,
            kinds = allowed.map { ValueKind.of(it) }.toSet(),
            onPick = { value ->
                value.toQuantity()?.let { press(Key.Recall(it)) }
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

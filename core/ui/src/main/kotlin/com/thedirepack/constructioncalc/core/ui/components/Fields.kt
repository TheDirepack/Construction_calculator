package com.thedirepack.constructioncalc.core.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import com.thedirepack.constructioncalc.core.measure.Dimension
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.format
import com.thedirepack.constructioncalc.core.ui.fractionText
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.toFieldText

/**
 * A length (or area/volume/number) input. Tapping it opens the feet-inch calculator
 * ([MeasureInputSheet]), so every tool gets fraction entry, math and values from other tools.
 * Shown with the user's precision and ft-in/in setting.
 */
@Composable
fun MeasureField(
    label: String,
    value: Quantity?,
    onValueChange: (Quantity?) -> Unit,
    modifier: Modifier = Modifier,
    allowed: Set<Dimension> = setOf(Dimension.LENGTH),
    supportingText: String? = null,
    isError: Boolean = false,
    preferInches: Boolean? = null,
) {
    val settings = rememberAppSettings()
    var editing by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    LaunchedEffect(pressed) { if (pressed) editing = true }

    OutlinedTextField(
        value = value?.let { settings.format(it, preferInches) }.orEmpty(),
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        placeholder = { Text("Tap to enter") },
        supportingText = supportingText?.let { { Text(it) } },
        isError = isError,
        singleLine = true,
        interactionSource = interaction,
        trailingIcon = {
            if (value != null) {
                IconButton(onClick = { onValueChange(null) }) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = "Clear $label")
                }
            } else {
                Icon(painterResource(R.drawable.ic_calculate), contentDescription = null)
            }
        },
        modifier = modifier.fillMaxWidth(),
    )

    if (editing) {
        MeasureInputSheet(
            title = label,
            initial = value,
            allowed = allowed,
            onDone = {
                onValueChange(it)
                editing = false
            },
            onDismiss = { editing = false },
        )
    }
}

/**
 * A plain number input with a unit suffix. If [pickKinds] is given, a button offers matching
 * results from other tools and the active project; [onPick] converts a picked value (by default
 * its exact value in the kind's base unit).
 */
@Composable
fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    unit: String = "",
    supportingText: String? = null,
    isError: Boolean = false,
    pickKinds: Set<ValueKind> = emptySet(),
    onPick: ((ResultValue) -> Unit)? = null,
    allowNegative: Boolean = false,
) {
    var picking by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = { text ->
            val allowed = text.filter { it.isDigit() || it == '.' || it == ',' || (allowNegative && it == '-') }
            onValueChange(allowed)
        },
        label = { Text(label) },
        suffix = if (unit.isNotEmpty()) ({ Text(unit) }) else null,
        supportingText = supportingText?.let { { Text(it) } },
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        trailingIcon = if (pickKinds.isEmpty()) null else ({
            IconButton(onClick = { picking = true }) {
                Icon(painterResource(R.drawable.ic_pick), contentDescription = "Use a value from another tool")
            }
        }),
        modifier = modifier.fillMaxWidth(),
    )
    if (picking) {
        ValuePickerSheet(
            title = label,
            kinds = pickKinds,
            onPick = { picked ->
                if (onPick != null) onPick(picked) else picked.rational?.let { onValueChange(it.toFieldText()) }
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

/** A dropdown for picking one of many options (wire size, conduit type…). */
@Composable
fun <T> ChoiceField(
    label: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    optionLabel: (T) -> String = { it.toString() },
    supportingText: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    LaunchedEffect(pressed) { if (pressed) expanded = true }
    Box(modifier) {
        OutlinedTextField(
            value = optionLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            supportingText = supportingText?.let { { Text(it) } },
            singleLine = true,
            interactionSource = interaction,
            trailingIcon = { Text("▾", style = MaterialTheme.typography.titleMedium) },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$label: ${optionLabel(selected)}" },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(fractionText(optionLabel(option)), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** A row of 2–4 mutually exclusive options. */
@Composable
fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    optionLabel: (T) -> String = { it.toString() },
) {
    Column(modifier) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    icon = {},
                ) { Text(fractionText(optionLabel(option)), maxLines = 1) }
            }
        }
    }
}

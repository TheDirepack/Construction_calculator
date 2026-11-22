package com.thedirepack.constructioncalc.core.ui.components

import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.LaunchedEffect

import com.thedirepack.constructioncalc.core.measure.Decimals

import com.thedirepack.constructioncalc.core.measure.UnitSystem

import com.thedirepack.constructioncalc.core.measure.Measure

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
import androidx.compose.ui.unit.dp
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
    PickerField(
        label = label,
        value = value?.let { settings.format(it, preferInches) } ?: "Tap to enter",
        onClick = { editing = true },
        supportingText = supportingText,
        isError = isError,
        trailingIcon = {
            if (value != null) {
                IconButton(onClick = { onValueChange(null) }) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = "Clear $label")
                }
            } else {
                Icon(painterResource(R.drawable.ic_calculate), contentDescription = null)
            }
        },
        modifier = modifier,
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

/**
 * A number in a [Measure] (temperature, run length, weight, tension…), shown and typed in the
 * user's unit system. [value] and [onValueChange] are always in the measure's **base** unit (what
 * inputs store and engines use), so switching systems never changes saved data. A picked value
 * from another tool is taken as base units (e.g. a published length in inches for
 * [Measure.SMALL_LENGTH]) unless [onPick] converts it.
 */
@Composable
fun UnitField(
    label: String,
    measure: Measure,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    isError: Boolean = false,
    pickKinds: Set<ValueKind> = emptySet(),
    onPick: ((ResultValue) -> Unit)? = null,
) {
    val system = rememberAppSettings().unitSystem
    var emitted by remember { mutableStateOf<String?>(null) }
    var text by remember(system) { mutableStateOf(UnitText.shown(measure, value, system)) }
    LaunchedEffect(value, system) { if (value != emitted) text = UnitText.shown(measure, value, system) }
    NumberField(
        label = label,
        value = text,
        onValueChange = { typed ->
            text = typed
            val base = UnitText.base(measure, typed, system)
            emitted = base
            onValueChange(base)
        },
        modifier = modifier,
        unit = measure.unit(system).symbol,
        supportingText = supportingText,
        isError = isError,
        pickKinds = pickKinds,
        onPick = onPick ?: { picked -> picked.rational?.let { onValueChange(it.toFieldText(6)) }; Unit },
        allowNegative = measure == Measure.TEMPERATURE,
    )
}

/** Converts a [UnitField]'s text between the base unit (stored) and the user's system (shown). */
object UnitText {
    /** Base-unit text → what the field shows. Blank or unreadable text is shown as is. */
    fun shown(measure: Measure, base: String, system: UnitSystem): String {
        val unit = measure.unit(system)
        if (unit == measure.base) return base
        val value = Decimals.parse(base) ?: return base
        return Decimals.trim(unit.fromBase(value), unit.decimals + 1)
    }

    /** What was typed → base-unit text to store. */
    fun base(measure: Measure, typed: String, system: UnitSystem): String {
        val unit = measure.unit(system)
        if (unit == measure.base) return typed
        val value = Decimals.parse(typed) ?: return typed
        return Decimals.trim(unit.toBase(value), 6)
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
    Box(modifier) {
        PickerField(
            label = label,
            value = optionLabel(selected),
            onClick = { expanded = true },
            supportingText = supportingText,
            trailingIcon = { Text("▾", style = MaterialTheme.typography.titleMedium) },
            modifier = Modifier.semantics { contentDescription = "$label: ${optionLabel(selected)}" },
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

/**
 * A tap-to-choose field drawn as a read-only outlined text field, so it lines up with
 * [NumberField] and [UnitField] beside it (same height, border and floating label).
 */
@Composable
private fun PickerField(
    label: String,
    value: String,
    onClick: () -> Unit,
    trailingIcon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    isError: Boolean = false,
) {
    // Taps on the read-only field open the choices; a trailing IconButton (clear) still gets its own taps.
    val interaction = remember { MutableInteractionSource() }
    val currentOnClick by rememberUpdatedState(onClick)
    LaunchedEffect(interaction) {
        interaction.interactions.collect { if (it is PressInteraction.Release) currentOnClick() }
    }
    OutlinedTextField(
        value = TextFieldValue(fractionText(value)),
        onValueChange = {},
        readOnly = true,
        singleLine = true,
        label = { Text(label) },
        trailingIcon = trailingIcon,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        interactionSource = interaction,
        modifier = modifier
            .fillMaxWidth()
            .semantics { role = Role.DropdownList; onClick { currentOnClick(); true } },
    )
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
                ) {
                    Text(
                        fractionText(optionLabel(option)),
                        maxLines = 1,
                        softWrap = false,
                        autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize),
                    )
                }
            }
        }
    }
}

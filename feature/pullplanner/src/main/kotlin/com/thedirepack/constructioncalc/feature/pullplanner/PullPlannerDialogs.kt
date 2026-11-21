package com.thedirepack.constructioncalc.feature.pullplanner

import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.electrical.CableConfiguration
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.InsulationType
import com.thedirepack.constructioncalc.core.measure.Decimals
import com.thedirepack.constructioncalc.core.measure.Measure
import com.thedirepack.constructioncalc.core.ui.components.UnitField
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.show
import com.thedirepack.constructioncalc.core.model.BendPlane
import com.thedirepack.constructioncalc.core.model.PathSegment
import com.thedirepack.constructioncalc.core.model.PullCable
import com.thedirepack.constructioncalc.core.model.PullSetup
import com.thedirepack.constructioncalc.core.model.RunPath
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.core.ui.components.ChoiceField
import com.thedirepack.constructioncalc.core.ui.components.MeasureField
import com.thedirepack.constructioncalc.core.ui.components.NumberField
import com.thedirepack.constructioncalc.core.ui.components.ToggleRow

internal enum class SegmentKind(val label: String) {
    STRAIGHT("Straight"), BEND("Bend"), PULL_POINT("Pull point");

    companion object {
        fun of(segment: PathSegment) = when (segment) {
            is PathSegment.Straight -> STRAIGHT
            is PathSegment.Bend -> BEND
            is PathSegment.PullPoint -> PULL_POINT
        }
    }
}

@Composable
internal fun PullSegmentDialog(
    kind: SegmentKind,
    initial: PathSegment?,
    onSave: (PathSegment) -> Unit,
    onDismiss: () -> Unit,
) {
    val straight = initial as? PathSegment.Straight
    val bend = initial as? PathSegment.Bend
    var length by remember { mutableStateOf(straight?.length?.toQuantity()) }
    var slope by remember { mutableStateOf(numberText(straight?.slopeDegrees)) }
    var angle by remember { mutableStateOf(numberText(bend?.angleDegrees)) }
    var radius by remember { mutableStateOf(bend?.radius?.toQuantity()) }
    var plane by remember { mutableStateOf(bend?.plane ?: BendPlane.HORIZONTAL) }
    var label by remember { mutableStateOf((initial as? PathSegment.PullPoint)?.label.orEmpty()) }

    val parsedSlope = if (slope.isBlank()) 0.0 else Decimals.parse(slope)
    val parsedAngle = Decimals.parse(angle)
    val segment = when (kind) {
        SegmentKind.STRAIGHT -> length?.takeIf {
            it.value.signum >= 0 && it.value.toDouble().isFinite()
        }?.let { value ->
            parsedSlope?.takeIf { it in -90.0..90.0 }?.let { PathSegment.Straight(value.stored(), it) }
        }
        SegmentKind.BEND -> parsedAngle?.takeIf { it > 0 && it <= 180 }?.let {
            val radiusValue = radius?.value
            if (radiusValue == null || (radiusValue.signum > 0 && radiusValue.toDouble().isFinite())) {
                PathSegment.Bend(it, radius?.stored(), plane, bend?.sourceItemId)
            } else null
        }
        SegmentKind.PULL_POINT -> PathSegment.PullPoint(label.trim())
    }
    PullOptionsDialog(kind.label, segment?.let { { onSave(it) } }, onDismiss) {
        when (kind) {
            SegmentKind.STRAIGHT -> {
                MeasureField("Length", length, { length = it })
                NumberField("Slope", slope, { slope = it }, unit = "°", allowNegative = true,
                    supportingText = "−90° to 90°. Negative slopes go downhill toward the end.")
            }
            SegmentKind.BEND -> {
                NumberField("Bend angle", angle, { angle = it }, unit = "°", supportingText = "Above 0° and at most 180°.")
                MeasureField("Centerline radius", radius, { radius = it }, supportingText = "Leave blank to use the ${CodeRule.BEND_RADIUS.cite} radius.")
                ChoiceField("Bend plane", BendPlane.entries, plane, { plane = it }, optionLabel = { it.label })
            }
            SegmentKind.PULL_POINT ->
                OutlinedTextField(label, { label = it.take(200) }, label = { Text("Name (optional)") }, singleLine = true)
        }
    }
}

@Composable
internal fun PullCableDialog(initial: PullCable, onSave: (PullCable) -> Unit, onDismiss: () -> Unit) {
    var count by remember { mutableStateOf(initial.count.toString()) }
    var size by remember { mutableStateOf(initial.size) }
    var insulation by remember { mutableStateOf(initial.insulation) }
    var material by remember { mutableStateOf(initial.material) }
    var od by remember { mutableStateOf(numberText(initial.diameterIn)) }
    var weight by remember { mutableStateOf(numberText(initial.weightLbPerFt)) }
    var bears by remember { mutableStateOf(initial.bearsTension) }
    val parsedCount = count.toIntOrNull()?.takeIf { it in 1..PullCable.MAX_COUNT }
    val parsedOd = Decimals.parse(od)?.takeIf { it > 0 }
    val parsedWeight = Decimals.parse(weight)?.takeIf { it > 0 }
    val cable = parsedCount?.takeIf {
        (od.isBlank() || parsedOd != null) && (weight.isBlank() || parsedWeight != null)
    }?.let { initial.copy(count = it, size = size, insulation = insulation, material = material, diameterIn = parsedOd, weightLbPerFt = parsedWeight, bearsTension = bears) }
    PullOptionsDialog("Cable group", cable?.let { { onSave(it) } }, onDismiss) {
        NumberField("Number of conductors", count, { count = it }, supportingText = "1 to ${PullCable.MAX_COUNT}.")
        ChoiceField("Wire size", ConductorSize.entries, size, { size = it }, optionLabel = { it.label })
        ChoiceField("Insulation", InsulationType.entries, insulation, { insulation = it }, optionLabel = { it.code })
        ChoiceField("Material", ConductorMaterial.entries, material, { material = it }, optionLabel = { it.label })
        UnitField("Outside diameter", Measure.SMALL_LENGTH, od, { od = it }, supportingText = "Leave blank to use the table value.")
        UnitField("Weight per conductor", Measure.LINEAR_WEIGHT, weight, { weight = it }, supportingText = "Leave blank for an estimate.")
        ToggleRow("Bears pulling tension", bears, { bears = it })
    }
}

@Composable
internal fun PullRatingsDialog(
    setup: PullSetup,
    sectionCount: Int,
    onSave: (PullSetup) -> Unit,
    onDismiss: () -> Unit,
) {
    var friction by remember { mutableStateOf(numberText(setup.frictionOverride)) }
    var pressure by remember { mutableStateOf(numberText(setup.pressureLimit)) }
    var configuration by remember { mutableStateOf(setup.configuration) }
    val incomingCount = sectionCount.coerceAtMost(RunPath.MAX_SEGMENTS)
    var incoming by remember { mutableStateOf(List(incomingCount) { index -> numberText(setup.incomingTension.getOrNull(index)) }) }
    var equipment by remember { mutableStateOf(setup.equipment.map { numberText(it.pounds) }) }
    val mu = Decimals.parse(friction)?.takeIf { it > 0 && it <= 2 }
    val swbp = Decimals.parse(pressure)?.takeIf { it > 0 }
    val tensions = incoming.map { if (it.isBlank()) 0.0 else Decimals.parse(it)?.takeIf { v -> v >= 0 } }
    val ratings = equipment.map { Decimals.parse(it)?.takeIf { v -> v >= 0 } }
    val valid = (friction.isBlank() || mu != null) && (pressure.isBlank() || swbp != null) &&
        tensions.none { it == null } && equipment.indices.all { equipment[it].isBlank() || ratings[it] != null }
    PullOptionsDialog("Pull tensions and limits", if (valid) {
        {
            onSave(setup.copy(
                frictionOverride = mu,
                configuration = configuration,
                incomingTension = tensions.filterNotNull(),
                pressureLimit = swbp,
                equipment = setup.equipment.zip(ratings).map { (rating, pounds) -> rating.copy(pounds = pounds) },
            ))
        }
    } else null, onDismiss) {
        NumberField("Friction override", friction, { friction = it }, supportingText = "Above 0 and at most 2. Leave blank for the preset (guidance, not a code value).")
        ChoiceField("Cable configuration", listOf(null) + CableConfiguration.entries, configuration,
            { configuration = it }, optionLabel = { it?.label ?: "Automatic" })
        val settings = rememberAppSettings()
        UnitField(
            "Sidewall pressure limit", Measure.SIDEWALL_PRESSURE, pressure, { pressure = it },
            supportingText = "The cable maker's rating. Blank uses Southwire's building-wire guidance: " +
                "${settings.show(Measure.SIDEWALL_PRESSURE, 500.0)} for 14–10 AWG, ${settings.show(Measure.SIDEWALL_PRESSURE, 1000.0)} for 8 AWG and larger.",
        )
        incoming.forEachIndexed { index, value ->
            UnitField("Section ${index + 1} incoming tension", Measure.FORCE, value, { updated ->
                incoming = incoming.toMutableList().also { it[index] = updated }
            })
        }
        setup.equipment.forEachIndexed { index, rating ->
            UnitField(rating.name, Measure.FORCE, equipment[index], { updated ->
                equipment = equipment.toMutableList().also { it[index] = updated }
            })
        }
    }
}

private fun numberText(value: Double?) = value?.toBigDecimal()?.stripTrailingZeros()?.toPlainString().orEmpty()

@Composable
private fun PullOptionsDialog(
    title: String,
    onSave: (() -> Unit)?,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { content() }
        },
        confirmButton = { TextButton(onClick = { onSave?.invoke() }, enabled = onSave != null) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

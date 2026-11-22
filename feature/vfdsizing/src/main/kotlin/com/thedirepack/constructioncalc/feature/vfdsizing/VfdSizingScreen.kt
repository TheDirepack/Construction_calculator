package com.thedirepack.constructioncalc.feature.vfdsizing

import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.codePercent
import com.thedirepack.constructioncalc.core.electrical.AcPhases
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.ui.tool.toolViewModel
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.MotorFullLoadCurrent
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.ui.components.ChoiceField
import com.thedirepack.constructioncalc.core.ui.components.FormSection
import com.thedirepack.constructioncalc.core.ui.components.NumberField
import com.thedirepack.constructioncalc.core.ui.components.ResultCard
import com.thedirepack.constructioncalc.core.ui.components.SegmentedChoice
import com.thedirepack.constructioncalc.core.ui.components.ToolScaffold
import com.thedirepack.constructioncalc.core.ui.tool.ItemDraft
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.feature.vfdsizing.domain.LoadType
import com.thedirepack.constructioncalc.feature.vfdsizing.domain.RatingUnit
import com.thedirepack.constructioncalc.feature.vfdsizing.domain.VfdSizing

private val TERMINATIONS = listOf(60, 75)

@Composable
fun VfdSizingScreen(host: ToolHost) {
    val vm: VfdSizingViewModel = toolViewModel(VfdSizingTool.ID, host)
    val inputs = vm.inputs
    val settings = com.thedirepack.constructioncalc.core.ui.rememberAppSettings()
    val output = remember(inputs, settings) { vfdSizingOutput(inputs, settings) }
    val results = output.results

    LaunchedEffect(results) {
        if (results.isNotEmpty()) host.publish(results, context = output.summary)
    }

    ToolScaffold(
        title = VfdSizingTool.title,
        host = host,
        onSave = if (results.isEmpty()) null else ({
            host.saveToProject(
                ItemDraft(VfdSizingTool.ID, "VFD sizing", ToolInputs.encode(inputs), results, output.summary),
            )
        }),
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FormSection("Motor") {
                SegmentedChoice(
                    options = RatingUnit.entries,
                    selected = inputs.unit,
                    onSelect = { u -> vm.update { it.copy(unit = u) } },
                    label = "Rating in",
                    optionLabel = { it.label },
                )
                ChoiceField(
                    label = "Motor voltage",
                    options = VfdSizing.MOTOR_VOLTAGES,
                    selected = inputs.volts,
                    onSelect = { v -> vm.update { it.copy(volts = v) } },
                    optionLabel = { "$it V" },
                    supportingText = "Three-phase motor. 480 V motors use the 460 V column.",
                )
                if (inputs.unit == RatingUnit.HP) {
                    ChoiceField(
                        label = "Horsepower",
                        options = VfdSizing.horsepowers(inputs.volts),
                        selected = inputs.hp,
                        onSelect = { h -> vm.update { it.copy(hp = h) } },
                        optionLabel = { "${MotorFullLoadCurrent.label(it)} HP" },
                    )
                } else {
                    NumberField(
                        "Motor power",
                        inputs.kw,
                        { t -> vm.update { it.copy(kw = t) } },
                        unit = "kW",
                        supportingText = "Converted to the next standard horsepower",
                    )
                }
                NumberField(
                    "Nameplate FLA (optional)",
                    inputs.nameplateFla,
                    { t -> vm.update { it.copy(nameplateFla = t) } },
                    unit = "A",
                    pickKinds = setOf(ValueKind.CURRENT),
                    supportingText = "Sizes the drive and overload. Conductors and protection use the table",
                )
            }

            FormSection("Drive") {
                SegmentedChoice(
                    options = listOf(AcPhases.THREE, AcPhases.SINGLE),
                    selected = inputs.supplyPhase,
                    onSelect = { p -> vm.update { it.copy(supplyPhase = p) } },
                    label = "Supply into the drive",
                    optionLabel = { it.label },
                )
                ChoiceField(
                    label = "Load type",
                    options = LoadType.entries,
                    selected = inputs.loadType,
                    onSelect = { l -> vm.update { it.copy(loadType = l) } },
                    optionLabel = { it.label },
                    supportingText = "Picks the drive's normal-duty or heavy-duty current rating",
                )
                NumberField(
                    "Drive rated input current (optional)",
                    inputs.driveInputAmps,
                    { t -> vm.update { it.copy(driveInputAmps = t) } },
                    unit = "A",
                    pickKinds = setOf(ValueKind.CURRENT),
                    supportingText = "From the drive's nameplate. Blank: ${codePercent(Code.book.values.driveInputConductorFactor)} of motor FLC is used",
                )
            }

            FormSection("Conductors") {
                ChoiceField(
                    options = ConductorMaterial.entries,
                    selected = inputs.conductorMaterial,
                    onSelect = { m -> vm.update { it.copy(material = m.name) } },
                    label = "Material",
                    optionLabel = { it.label },
                )
                SegmentedChoice(
                    options = TERMINATIONS,
                    selected = inputs.terminationRating.celsius,
                    onSelect = { t -> vm.update { it.copy(termination = t) } },
                    label = "Termination temperature rating",
                    optionLabel = { "$it °C" },
                )
            }

            ResultCard(results, notices = output.notices)
        }
    }
}

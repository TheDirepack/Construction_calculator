package com.thedirepack.constructioncalc.feature.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.electrical.AcPhases
import com.thedirepack.constructioncalc.core.electrical.Article220
import com.thedirepack.constructioncalc.core.electrical.ComparisonRequest
import com.thedirepack.constructioncalc.core.electrical.ConductorComparison
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.LightingOccupancy
import com.thedirepack.constructioncalc.core.electrical.code.codeNumber
import com.thedirepack.constructioncalc.core.electrical.code.codePercent
import com.thedirepack.constructioncalc.core.electrical.OptionalDwellingInput
import com.thedirepack.constructioncalc.core.electrical.StandardLoadInput
import com.thedirepack.constructioncalc.core.electrical.StandardOcpd
import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeEdition
import com.thedirepack.constructioncalc.core.electrical.code.CodeProfile
import com.thedirepack.constructioncalc.core.electrical.code.CodeRegions
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.electrical.code.codePercentValue
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Measure
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.model.design.ProjectAnalysis
import com.thedirepack.constructioncalc.core.model.design.Wire
import com.thedirepack.constructioncalc.core.ui.components.ChoiceField
import com.thedirepack.constructioncalc.core.ui.components.FormSection
import com.thedirepack.constructioncalc.core.ui.components.MeasureField
import com.thedirepack.constructioncalc.core.ui.components.Notice
import com.thedirepack.constructioncalc.core.ui.components.NoticeLevel
import com.thedirepack.constructioncalc.core.ui.components.NumberField
import com.thedirepack.constructioncalc.core.ui.components.ResultCard
import com.thedirepack.constructioncalc.core.ui.components.SegmentedChoice
import com.thedirepack.constructioncalc.core.ui.components.ToggleRow
import com.thedirepack.constructioncalc.core.ui.components.UnitField
import com.thedirepack.constructioncalc.core.ui.formatDecimal
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.show
import com.thedirepack.constructioncalc.core.ui.toDecimalOrNull
import kotlinx.coroutines.delay

/** The project's design assumptions (they live in the project file, not in app settings). */
private const val TYPING_SETTLE_MS = 600L

private enum class Article220Mode {
    STANDARD,
    OPTIONAL_DWELLING,
    ;

    val label: String get() = when (this) {
        STANDARD -> "Standard (Part III)"
        OPTIONAL_DWELLING -> "Optional dwelling (${CodeRule.OPTIONAL_DWELLING.cite})"
    }
}

@Composable
internal fun DesignTab(ctx: PanelsContext, modifier: Modifier = Modifier) {
    val design = ctx.project.design
    var ambientText by remember(ctx.project.id) { mutableStateOf(formatDecimal(design.ambientC, 1)) }
    var targetText by remember(ctx.project.id) { mutableStateOf(formatDecimal(design.voltageDropTargetPercent, 1)) }
    fun update(change: (com.thedirepack.constructioncalc.core.model.DesignDefaults) -> com.thedirepack.constructioncalc.core.model.DesignDefaults) =
        ctx.edit { p, now -> p.copy(design = change(p.design), updatedAt = now) }
    // Apply typed numbers once they settle, so typing "45" doesn't save and re-run the design for 4 °C first.
    LaunchedEffect(ambientText) {
        delay(TYPING_SETTLE_MS)
        ambientText.toDecimalOrNull()?.takeIf { it in -50.0..100.0 && it != ctx.project.design.ambientC }
            ?.let { v -> update { it.copy(ambientC = v) } }
    }
    LaunchedEffect(targetText) {
        delay(TYPING_SETTLE_MS)
        targetText.toDecimalOrNull()?.takeIf { it > 0.0 && it <= 20.0 && it != ctx.project.design.voltageDropTargetPercent }
            ?.let { v -> update { it.copy(voltageDropTargetPercent = v) } }
    }

    val allWarnings = remember(ctx.design) {
        buildList {
            ctx.design.panels.forEach { pd ->
                pd.conflicts.forEach { add("${pd.panel.name}: $it") }
                pd.notes.filter { it.isWarning }.forEach { add("${pd.panel.name}: ${it.text}") }
            }
            ctx.design.circuits.values.forEach { cd ->
                cd.notes.filter { it.isWarning }.forEach { note ->
                    add("${cd.panel.name} #${cd.circuit.slots.joinToString(",")}: ${note.text}")
                }
            }
            ctx.design.runs.values.forEach { rd ->
                rd.notes.filter { it.isWarning }.forEach { note ->
                    add("${rd.run.name}: ${note.text}")
                }
            }
        }
    }

    val appSettings = rememberAppSettings()
    val codeProfile = remember(ctx.project.codeEdition, ctx.project.codeRegion, appSettings.codeEdition, appSettings.codeRegion) {
        ProjectAnalysis.codeProfileFor(ctx.project)
    }
    val vdSupportText = remember(codeProfile) {
        Code.withProfile(codeProfile) {
            "NEC recommends ${codePercentValue(Code.book.values.voltageDropBranchPercent, spaced = false)} on a branch circuit, ${codePercentValue(Code.book.values.voltageDropTotalPercent, spaced = false)} overall (informational notes)."
        }
    }
    val footerText = remember(codeProfile) {
        Code.withProfile(codeProfile) {
            "Sizing follows ${Code.label}: ${CodeRule.AMPACITY_TABLE.cite} ampacity, ${CodeRule.AMPACITY.cite} ambient and conductor-count adjustment, ${CodeRule.TERMINATION_TEMPERATURE.cite} terminals, " +
                "${CodeRule.CONDUCTOR_PROTECTION.cite} protection, ${CodeRule.FILL_PERCENT.cite}, ${CodeRule.CONDUIT_DIMENSIONS.cite}, ${CodeRule.CONDUCTOR_DIMENSIONS.cite} and ${CodeRule.AC_IMPEDANCE.cite}. It guides fieldwork and doesn't replace a code check."
        }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (allWarnings.isNotEmpty()) {
            OutOfSpecBanner(
                title = "${plural(allWarnings.size, "out-of-spec design warning")}",
                warnings = allWarnings,
            )
        }
        Text(
            "Every circuit starts from these assumptions unless it has its own conductor override. They are saved with the project.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CodeProfileCard(ctx)
        FormSection("Default conductor") {
            if (ctx.readOnly) {
                Text(
                    "${design.conductor.material.label}, ${design.conductor.insulation.label}, " +
                        "insulation ${design.conductor.insulationRating.label}, terminals ${design.conductor.terminationRating.label}",
                )
            } else {
                ConductorFields(design.conductor) { spec -> update { it.copy(conductor = spec) } }
            }
        }
        FormSection("Conditions") {
            if (ctx.readOnly) {
                Text(
                    "Ambient ${appSettings.show(Measure.TEMPERATURE, design.ambientC, 1)} · voltage-drop target ${formatDecimal(design.voltageDropTargetPercent, 1)}% · " +
                        "upsize for voltage drop: ${if (design.upsizeForVoltageDrop) "on" else "off"}",
                )
            } else {
                UnitField(
                    label = "Default ambient temperature",
                    measure = Measure.TEMPERATURE,
                    value = ambientText,
                    onValueChange = { ambientText = it },
                    supportingText = "Runs can set their own (hot attic, rooftop).",
                )
                NumberField(
                    label = "Voltage-drop target",
                    value = targetText,
                    onValueChange = { targetText = it },
                    unit = "%",
                    supportingText = vdSupportText,
                )
                ToggleRow(
                    "Upsize wire for voltage drop",
                    design.upsizeForVoltageDrop,
                    { v -> update { it.copy(upsizeForVoltageDrop = v) } },
                    "Steps the wire (and the ground with it) up until the target is met.",
                )
            }
        }

        Article220Card(ctx, codeProfile)
        ConductorComparisonCard(ctx)

        Text(
            footerText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CodeProfileCard(ctx: PanelsContext) {
    val appSettings = rememberAppSettings()
    val appEdition = CodeEdition.entries.firstOrNull { it.name == appSettings.codeEdition && it.isImplemented } ?: CodeEdition.NEC_2023
    val appRegion = CodeRegions.getOrDefault(appSettings.codeRegion)
    val projectEdition = ctx.project.codeEdition?.let { name -> CodeEdition.entries.firstOrNull { it.name == name && it.isImplemented } }
    val effectiveEdition = projectEdition ?: appEdition
    val selectedRegionId = ctx.project.codeRegion?.takeIf { CodeRegions.find(it) != null }
    val effectiveRegion = selectedRegionId?.let(CodeRegions::getOrDefault) ?: appRegion

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Code Edition & Region", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Effective: ${effectiveEdition.label} · ${effectiveRegion.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (ctx.readOnly) {
                Text(
                    "Edition: ${if (ctx.project.codeEdition == null) "Inherit app setting (${appEdition.label})" else effectiveEdition.label}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Region: ${if (selectedRegionId == null) "Inherit app setting (${appRegion.name})" else effectiveRegion.name}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Code edition", style = MaterialTheme.typography.bodyLarge)
                    CodeEditionOptionRow(
                        title = "Inherit app setting (${appEdition.label})",
                        subtitle = "Follows the app-wide electrical code setting.",
                        selected = ctx.project.codeEdition == null,
                        enabled = true,
                        onClick = { ctx.edit { p, now -> p.copy(codeEdition = null, updatedAt = now) } },
                    )
                    CodeEdition.entries.forEach { edition ->
                        val enabled = edition.isImplemented
                        val title = if (enabled) "${edition.label} (Active)" else "${edition.label} (Planned)"
                        CodeEditionOptionRow(
                            title = title,
                            subtitle = edition.statusNote,
                            selected = enabled && ctx.project.codeEdition == edition.name,
                            enabled = enabled,
                            onClick = { ctx.edit { p, now -> p.copy(codeEdition = edition.name, updatedAt = now) } },
                        )
                    }
                }
                val regionNote = buildString {
                    if (selectedRegionId == null) {
                        append("Inheriting app setting (${appRegion.name}). ")
                    }
                    append(effectiveRegion.description)
                    if (!effectiveRegion.hasOverrides && effectiveRegion.id != CodeRegions.STANDARD_ID) {
                        append(" Uses unamended ${effectiveEdition.label} until jurisdiction overrides are verified.")
                    }
                }
                ChoiceField<String?>(
                    label = "Code region",
                    options = listOf<String?>(null) + CodeRegions.entries.map { it.id },
                    selected = selectedRegionId,
                    onSelect = { id -> ctx.edit { p, now -> p.copy(codeRegion = id, updatedAt = now) } },
                    optionLabel = { id ->
                        if (id == null) "Inherit app setting (${appRegion.name})" else CodeRegions.getOrDefault(id).name
                    },
                    supportingText = regionNote,
                )
            }
        }
    }
}

@Composable
private fun CodeEditionOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
        }
    }
}

@Composable
private fun Article220Card(ctx: PanelsContext, codeProfile: CodeProfile) {
    var expanded by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(Article220Mode.STANDARD) }
    var occupancy by remember { mutableStateOf(LightingOccupancy.DWELLING) }
    var lightingVa by remember { mutableStateOf("6000") }
    var floorSqFt by remember { mutableStateOf("2000") }
    var smallAppCount by remember { mutableStateOf("2") }
    var laundryCount by remember { mutableStateOf("1") }
    var receptacleVa by remember { mutableStateOf("0") }
    var fixedApplianceVa by remember { mutableStateOf("0") }
    var fixedApplianceCount by remember { mutableStateOf("0") }
    var dryerCount by remember { mutableStateOf("1") }
    var rangeCount by remember { mutableStateOf("1") }
    var rangeKw by remember { mutableStateOf("12") }
    var heatingVa by remember { mutableStateOf("0") }
    var coolingVa by remember { mutableStateOf("4800") }
    var largestMotorVa by remember { mutableStateOf("0") }
    var otherVa by remember { mutableStateOf("0") }
    var voltage by remember { mutableStateOf("240") }
    var phases by remember { mutableStateOf(AcPhases.SINGLE) }

    val codeSubtitle = remember(codeProfile) {
        Code.withProfile(codeProfile) {
            "${Code.label} demand factors: lighting, receptacles, ranges, dryers, HVAC, optional dwelling."
        }
    }
    val codeCites = remember(codeProfile) {
        Code.withProfile(codeProfile) {
            mapOf(
                "lighting" to CodeRule.LIGHTING_DEMAND.cite,
                "dryer" to CodeRule.DRYER_DEMAND.cite,
                "range" to CodeRule.RANGE_DEMAND.cite,
                "receptacle" to CodeRule.RECEPTACLE_DEMAND.cite,
                "noncoincident" to CodeRule.NONCOINCIDENT_LOADS.cite,
                "smallAppVa" to codeNumber(Code.book.loadCalculation.smallApplianceCircuitVa),
                "laundryVa" to codeNumber(Code.book.loadCalculation.laundryCircuitVa),
                "largestMotorAdder" to codePercent(Code.book.loadCalculation.largestMotorAdder, false),
                "optSqFt" to codeNumber(Code.book.loadCalculation.optionalVaPerSqFt),
                "optCool" to codePercent(Code.book.loadCalculation.optionalCoolingFactor, false),
                "optHeat" to codePercent(Code.book.loadCalculation.optionalCentralHeatFactor, false),
            )
        }
    }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Feeder & Service Demand (Article 220)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        codeSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide" else "Open") }
            }

            if (expanded) {
                val panelTotalVa = ctx.design.panels.sumOf { it.totalVa }
                if (panelTotalVa > 0.0) {
                    val firstPanel = ctx.project.panels.firstOrNull()
                    OutlinedButton(onClick = {
                        // Panel totals are already-itemized loads: count them at 100%, not as lighting.
                        mode = Article220Mode.STANDARD
                        otherVa = formatDecimal(panelTotalVa, 0)
                        if (firstPanel != null) {
                            voltage = firstPanel.system.lineToLine.toString()
                            phases = if (firstPanel.system.phases == 3) AcPhases.THREE else AcPhases.SINGLE
                        }
                    }) {
                        Text("Add ${Wire.va(panelTotalVa)} of panel loads at 100%")
                    }
                }

                SegmentedChoice(
                    options = Article220Mode.entries,
                    selected = mode,
                    onSelect = { mode = it },
                    label = "Calculation method",
                    optionLabel = { it.label },
                )

                val vInt = voltage.toIntOrNull()?.takeIf { it > 0 } ?: 240
                if (mode == Article220Mode.STANDARD) {
                    ChoiceField(
                        label = "Occupancy (${codeCites.getValue("lighting")})",
                        options = LightingOccupancy.entries,
                        selected = occupancy,
                        onSelect = { occupancy = it },
                        optionLabel = { it.label },
                    )
                    SegmentedChoice(
                        options = AcPhases.entries,
                        selected = phases,
                        onSelect = { phases = it },
                        label = "System phase",
                        optionLabel = { it.label },
                    )
                    NumberField("Line voltage", voltage, { voltage = it }, unit = "V")
                    NumberField("General lighting", lightingVa, { lightingVa = it }, unit = "VA")
                    if (occupancy == LightingOccupancy.DWELLING) {
                        NumberField("Small-appliance circuits (${codeCites.getValue("smallAppVa")} VA ea)", smallAppCount, { smallAppCount = it })
                        NumberField("Laundry circuits (${codeCites.getValue("laundryVa")} VA ea)", laundryCount, { laundryCount = it })
                        NumberField("Fixed appliances total", fixedApplianceVa, { fixedApplianceVa = it }, unit = "VA")
                        NumberField("Fixed appliance count", fixedApplianceCount, { fixedApplianceCount = it })
                        NumberField("Electric dryers (${codeCites.getValue("dryer")})", dryerCount, { dryerCount = it })
                        NumberField("Electric ranges (${codeCites.getValue("range")})", rangeCount, { rangeCount = it })
                        NumberField("Range rating (each)", rangeKw, { rangeKw = it }, unit = "kW")
                    } else {
                        NumberField("Receptacles (${codeCites.getValue("receptacle")})", receptacleVa, { receptacleVa = it }, unit = "VA")
                    }
                    NumberField("Heating load (${codeCites.getValue("noncoincident")})", heatingVa, { heatingVa = it }, unit = "VA")
                    NumberField("Cooling load (${codeCites.getValue("noncoincident")})", coolingVa, { coolingVa = it }, unit = "VA")
                    NumberField(
                        "Largest motor (${codeCites.getValue("largestMotorAdder")} adder)",
                        largestMotorVa,
                        { largestMotorVa = it },
                        unit = "VA",
                    )
                    NumberField("Other loads (100%)", otherVa, { otherVa = it }, unit = "VA")
                } else {
                    NumberField("Line voltage", voltage, { voltage = it }, unit = "V")
                    NumberField("Floor area (${codeCites.getValue("optSqFt")} VA/sq ft)", floorSqFt, { floorSqFt = it }, unit = "sq ft")
                    NumberField("Small-appliance circuits", smallAppCount, { smallAppCount = it })
                    NumberField("Laundry circuits", laundryCount, { laundryCount = it })
                    NumberField("Appliances, ranges & dryers total", fixedApplianceVa, { fixedApplianceVa = it }, unit = "VA")
                    NumberField("Cooling load (${codeCites.getValue("optCool")})", coolingVa, { coolingVa = it }, unit = "VA")
                    NumberField("Central electric heat (${codeCites.getValue("optHeat")})", heatingVa, { heatingVa = it }, unit = "VA")
                }

                val calc = remember(
                    mode, occupancy, lightingVa, floorSqFt, smallAppCount, laundryCount,
                    receptacleVa, fixedApplianceVa, fixedApplianceCount, dryerCount, rangeCount,
                    rangeKw, heatingVa, coolingVa, largestMotorVa, otherVa, vInt, phases, codeProfile,
                ) {
                    Code.withProfile(codeProfile) {
                        if (mode == Article220Mode.STANDARD) {
                            Article220.standardCalculation(
                                StandardLoadInput(
                                    occupancy = occupancy,
                                    generalLightingVa = lightingVa.toDecimalOrNull() ?: 0.0,
                                    smallApplianceCircuits = smallAppCount.toIntOrNull() ?: 0,
                                    laundryCircuits = laundryCount.toIntOrNull() ?: 0,
                                    receptacleVa = receptacleVa.toDecimalOrNull() ?: 0.0,
                                    fixedApplianceVa = fixedApplianceVa.toDecimalOrNull() ?: 0.0,
                                    fixedApplianceCount = fixedApplianceCount.toIntOrNull() ?: 0,
                                    dryerCount = dryerCount.toIntOrNull() ?: 0,
                                    rangeCount = rangeCount.toIntOrNull() ?: 0,
                                    rangeEachKw = rangeKw.toDecimalOrNull() ?: 12.0,
                                    heatingVa = heatingVa.toDecimalOrNull() ?: 0.0,
                                    coolingVa = coolingVa.toDecimalOrNull() ?: 0.0,
                                    largestMotorVa = largestMotorVa.toDecimalOrNull() ?: 0.0,
                                    otherNonContinuousVa = otherVa.toDecimalOrNull() ?: 0.0,
                                    lineVoltage = vInt,
                                    phases = phases,
                                ),
                            )
                        } else {
                            Article220.optionalDwellingCalculation(
                                OptionalDwellingInput(
                                    floorAreaSqFt = floorSqFt.toDecimalOrNull() ?: 0.0,
                                    smallApplianceCircuits = smallAppCount.toIntOrNull() ?: 0,
                                    laundryCircuits = laundryCount.toIntOrNull() ?: 0,
                                    appliancesVa = fixedApplianceVa.toDecimalOrNull() ?: 0.0,
                                    coolingVa = coolingVa.toDecimalOrNull() ?: 0.0,
                                    centralHeatingVa = heatingVa.toDecimalOrNull() ?: 0.0,
                                    lineVoltage = vInt,
                                ),
                            )
                        }
                    }
                }

                val rows = buildList {
                    add(ResultValue.text("connected", "Connected load", Wire.va(calc.connectedVa)))
                    add(ResultValue.text("demand", "Demand load", Wire.va(calc.demandVa)))
                    add(ResultValue.text("amps", "Demand current", Wire.amps(calc.demandAmps)))
                    calc.recommendedOcpdAmps?.let { add(ResultValue.text("ocpd", "Next standard OCPD", "$it A")) }
                    calc.breakdown.forEachIndexed { idx, item ->
                        val label = if (item.rule.isEmpty()) item.label else "${item.label} (${item.rule})"
                        add(ResultValue.text("b$idx", label, Wire.va(item.demandVa)))
                    }
                }
                ResultCard(
                    results = rows,
                    notices = calc.notes.map { Notice(it, NoticeLevel.INFO) },
                )
            }
        }
    }
}

@Composable
private fun ConductorComparisonCard(ctx: PanelsContext) {
    var expanded by remember { mutableStateOf(false) }
    var ocpd by remember { mutableStateOf(400) }
    var loadText by remember { mutableStateOf("350") }
    var voltageText by remember { mutableStateOf("480") }
    var phases by remember { mutableStateOf(AcPhases.THREE) }
    var length by remember { mutableStateOf<Quantity?>(Quantity.feet(Rational.of(200))) }
    var conduitType by remember { mutableStateOf(ConduitType.EMT) }
    var continuous by remember { mutableStateOf(false) }
    var includeNeutral by remember { mutableStateOf(false) }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Cu / Al & Parallel Comparison", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Compare Copper vs. Aluminum across 1–4 parallel sets (ampacity, VD %, EGC, min conduit & fill).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide" else "Open") }
            }

            if (expanded) {
                ChoiceField(
                    label = "OCPD rating",
                    options = StandardOcpd.ratings.filter { it in 60..4000 },
                    selected = ocpd,
                    onSelect = { ocpd = it },
                    optionLabel = { "$it A" },
                )
                NumberField("Load current", loadText, { loadText = it }, unit = "A")
                NumberField("Line voltage", voltageText, { voltageText = it }, unit = "V")
                SegmentedChoice(
                    options = AcPhases.entries,
                    selected = phases,
                    onSelect = { phases = it },
                    label = "Phases",
                    optionLabel = { it.label },
                )
                MeasureField(
                    label = "One-way length",
                    value = length,
                    onValueChange = { length = it },
                )
                ChoiceField(
                    label = "Conduit type",
                    options = ConduitType.entries,
                    selected = conduitType,
                    onSelect = { conduitType = it },
                    optionLabel = { it.displayName },
                )
                val appSettings = rememberAppSettings()
                val codeProfile = remember(
                    ctx.project.codeEdition,
                    ctx.project.codeRegion,
                    appSettings.codeEdition,
                    appSettings.codeRegion,
                ) {
                    ProjectAnalysis.codeProfileFor(ctx.project)
                }
                val continuousPercent = remember(codeProfile) {
                    Code.withProfile(codeProfile) { codePercent(Code.book.values.continuousLoadFactor, false) }
                }
                ToggleRow("Continuous load ($continuousPercent)", continuous, { continuous = it })
                ToggleRow("Include neutral in each raceway", includeNeutral, { includeNeutral = it })

                val loadAmps = loadText.toDecimalOrNull()?.coerceAtLeast(0.0) ?: 0.0
                val voltage = voltageText.toIntOrNull()?.takeIf { it > 0 } ?: 480
                val lengthFt = (length?.value?.toDouble() ?: 0.0) / 12.0
                val comparison = remember(
                    ocpd, loadAmps, voltage, phases, lengthFt, conduitType, continuous,
                    includeNeutral, ctx.project.design, codeProfile,
                ) {
                    Code.withProfile(codeProfile) {
                        ConductorComparison.compare(
                            ComparisonRequest(
                                ocpdAmps = ocpd,
                                loadAmps = loadAmps,
                                voltage = voltage,
                                phases = phases,
                                lengthFeet = lengthFt,
                                continuous = continuous,
                                includeNeutral = includeNeutral,
                                conduitType = conduitType,
                                insulation = ctx.project.design.conductor.insulation,
                                insulationRating = ctx.project.design.conductor.insulationRating,
                                terminationRating = ctx.project.design.conductor.terminationRating,
                                ambientC = ctx.project.design.ambientC,
                                voltageDropTargetPercent = ctx.project.design.voltageDropTargetPercent,
                            ),
                        )
                    }
                }

                listOf(ConductorMaterial.COPPER, ConductorMaterial.ALUMINUM).forEach { mat ->
                    Text(
                        mat.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    comparison.options.filter { it.material == mat }.forEach { opt ->
                        HorizontalDivider()
                        Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${opt.sets}× ${opt.size?.label ?: "No size fits"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                )
                                if (opt.size != null && !opt.meetsVoltageDropTarget) {
                                    WarningBadge("VD ${opt.voltageDropPercent?.let { Wire.percent(it) } ?: "—"}")
                                }
                            }
                            if (opt.size != null) {
                                val summary = buildList {
                                    opt.totalAmpacity?.let { add("Ampacity ${Wire.amps(it)}") }
                                    opt.voltageDropPercent?.let { add("VD ${Wire.percent(it)}") }
                                    opt.egcSize?.let { add("EGC ${it.label}") }
                                    opt.minConduit?.let { c ->
                                        val fill = opt.conduitFillPercent?.let { " (${Wire.percent(it)})" }.orEmpty()
                                        add("Min ${c.displayName}$fill")
                                    }
                                }.joinToString(" · ")
                                Text(summary, style = MaterialTheme.typography.bodySmall)
                            } else {
                                opt.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
        }
    }
}

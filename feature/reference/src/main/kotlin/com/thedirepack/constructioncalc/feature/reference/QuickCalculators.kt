package com.thedirepack.constructioncalc.feature.reference

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.electrical.AcPhases
import com.thedirepack.constructioncalc.core.electrical.BoxConductorEntry
import com.thedirepack.constructioncalc.core.electrical.BoxDeviceEntry
import com.thedirepack.constructioncalc.core.electrical.BoxFill
import com.thedirepack.constructioncalc.core.electrical.BoxFillRequest
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.GecBonding
import com.thedirepack.constructioncalc.core.electrical.GroundingElectrodeType
import com.thedirepack.constructioncalc.core.electrical.MotorCircuit
import com.thedirepack.constructioncalc.core.electrical.MotorCircuitRequest
import com.thedirepack.constructioncalc.core.electrical.MotorFullLoadCurrent
import com.thedirepack.constructioncalc.core.electrical.MotorProtectiveDevice
import com.thedirepack.constructioncalc.core.electrical.MotorType
import com.thedirepack.constructioncalc.core.electrical.PullBoxSizing
import com.thedirepack.constructioncalc.core.electrical.PullBoxType
import com.thedirepack.constructioncalc.core.electrical.RacewayInstall
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.measure.Decimals
import com.thedirepack.constructioncalc.core.measure.Measure
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.ui.components.ChoiceField
import com.thedirepack.constructioncalc.core.ui.components.Notice
import com.thedirepack.constructioncalc.core.ui.components.NoticeLevel
import com.thedirepack.constructioncalc.core.ui.components.NumberField
import com.thedirepack.constructioncalc.core.ui.components.ResultCard
import com.thedirepack.constructioncalc.core.ui.components.SegmentedChoice
import com.thedirepack.constructioncalc.core.ui.components.ToggleRow
import com.thedirepack.constructioncalc.core.ui.components.UnitField
import com.thedirepack.constructioncalc.core.ui.conductorSizeLabel
import com.thedirepack.constructioncalc.core.ui.isMetric
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.show
import com.thedirepack.constructioncalc.core.ui.tradeSizeLabel
import com.thedirepack.constructioncalc.feature.reference.content.QuickCalc

/** A Reference page's quick calculator, in a card under the page's text. */
@Composable
internal fun QuickCalculator(kind: QuickCalc) {
    val settings = rememberAppSettings()
    OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(kind.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            when (kind) {
                QuickCalc.BOX_FILL -> BoxFillCalc(settings)
                QuickCalc.PULL_BOX -> PullBoxCalc(settings)
                QuickCalc.GEC -> GecCalc(settings)
                QuickCalc.MOTOR_CIRCUIT -> MotorCalc(settings)
                QuickCalc.RACEWAY_SUPPORT -> SupportCalc(settings)
                QuickCalc.PVC_EXPANSION -> PvcCalc(settings)
            }
        }
    }
}

private fun count(text: String): Int = text.trim().toIntOrNull()?.coerceIn(0, 999) ?: 0
private fun digits(text: String) = text.filter(Char::isDigit).take(3)

/** Cubic inches, or cm³ in metric (no shared measure for small volumes yet). */
private fun AppSettings.volume(cuIn: Double) = if (isMetric) "${Decimals.trim(cuIn * 16.387064, 0)} cm³" else "${Decimals.trim(cuIn, 2)} in³"

private fun sizeName(settings: AppSettings, size: ConductorSize) = settings.conductorSizeLabel(size).replace("/0", "∕0")

@Composable
private fun BoxFillCalc(settings: AppSettings) {
    val sizes = BoxFill.supportedSizes
    var sizeA by rememberSaveable { mutableStateOf(ConductorSize.AWG_12) }
    var countA by rememberSaveable { mutableStateOf("4") }
    var sizeB by rememberSaveable { mutableStateOf(ConductorSize.AWG_14) }
    var countB by rememberSaveable { mutableStateOf("") }
    var clamps by rememberSaveable { mutableStateOf(false) }
    var studs by rememberSaveable { mutableStateOf("") }
    var devices by rememberSaveable { mutableStateOf("1") }
    var egcs by rememberSaveable { mutableStateOf("2") }
    var boxVolume by rememberSaveable { mutableStateOf("") }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("Conductors", countA, { countA = digits(it) }, Modifier.weight(0.4f))
        ChoiceField("Size", sizes, sizeA, { sizeA = it }, Modifier.weight(0.6f), optionLabel = { sizeName(settings, it) })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("More conductors", countB, { countB = digits(it) }, Modifier.weight(0.4f))
        ChoiceField("Size", sizes, sizeB, { sizeB = it }, Modifier.weight(0.6f), optionLabel = { sizeName(settings, it) })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("Devices (yokes)", devices, { devices = digits(it) }, Modifier.weight(1f))
        NumberField("Grounds (EGCs)", egcs, { egcs = digits(it) }, Modifier.weight(1f))
    }
    NumberField("Luminaire studs or hickeys", studs, { studs = digits(it) })
    ToggleRow("Internal cable clamps", clamps, { clamps = it })

    val conductors = listOf(BoxConductorEntry(sizeA, count(countA)), BoxConductorEntry(sizeB, count(countB))).filter { it.count > 0 }
    val largest = conductors.maxByOrNull { it.size.circularMils }?.size
    val request = BoxFillRequest(
        conductors = conductors,
        hasInternalClamps = clamps,
        supportFittings = count(studs),
        // Devices are counted against the largest conductor in the box, the safe assumption.
        devices = largest?.let { listOf(BoxDeviceEntry(count(devices), it)) }.orEmpty(),
        egcCount = count(egcs),
        largestEgcSize = largest,
    )
    val result = remember(request) { if (conductors.isEmpty()) null else BoxFill.calculate(request) }
    val own = Decimals.parse(boxVolume)?.takeIf { it > 0 }?.let { if (settings.isMetric) it / 16.387064 else it }
    val results = result?.let { r ->
        listOfNotNull(
            ResultValue.text("box_fill", "Volume needed", settings.volume(r.totalVolumeCuIn)),
            ResultValue.text("box_smallest", "Smallest standard metal box", r.smallestBox?.name ?: "None in the table: use a larger box"),
            ResultValue.text("box_breakdown", "Conductors · clamps · studs · devices · grounds",
                listOf(r.conductorVolumeCuIn, r.clampVolumeCuIn, r.supportVolumeCuIn, r.deviceVolumeCuIn, r.egcVolumeCuIn).joinToString(" · ") { Decimals.trim(it, 2) }),
            own?.let { ResultValue.text("box_yours", "Your box", if (BoxFill.fits(it, r.totalVolumeCuIn)) "Fits (${settings.volume(it)})" else "Too small (${settings.volume(it)})") },
        )
    }.orEmpty()
    NumberField(
        "Your box's marked volume (optional)", boxVolume, { boxVolume = it.filter { c -> c.isDigit() || c == '.' }.take(6) },
        unit = if (settings.isMetric) "cm³" else "in³",
    )
    ResultCard(
        results,
        notices = listOf(Notice("Each device is counted against the largest conductor in the box (${CodeRule.BOX_FILL.cite}).")),
        emptyText = "Enter at least one conductor.",
    )
}

@Composable
private fun PullBoxCalc(settings: AppSettings) {
    val trade = TradeSize.entries.filter { it >= TradeSize.S_1_2 }
    var type by rememberSaveable { mutableStateOf(PullBoxType.ANGLE) }
    var largestA by rememberSaveable { mutableStateOf(TradeSize.S_3) }
    var othersA by rememberSaveable { mutableStateOf("") }
    var otherSizeA by rememberSaveable { mutableStateOf(TradeSize.S_2) }
    var largestB by rememberSaveable { mutableStateOf(TradeSize.S_3) }
    var othersB by rememberSaveable { mutableStateOf("") }
    var otherSizeB by rememberSaveable { mutableStateOf(TradeSize.S_2) }

    SegmentedChoice(PullBoxType.entries, type, { type = it }, optionLabel = { it.label.substringBefore(" (") })
    val rowLabel = if (type == PullBoxType.ANGLE) "Wall A" else "Raceways"
    ChoiceField("$rowLabel: largest raceway", trade, largestA, { largestA = it }, optionLabel = { settings.tradeSizeLabel(it) })
    if (type != PullBoxType.STRAIGHT) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Others in row", othersA, { othersA = digits(it) }, Modifier.weight(0.4f))
            ChoiceField("Size", trade, otherSizeA, { otherSizeA = it }, Modifier.weight(0.6f), optionLabel = { settings.tradeSizeLabel(it) })
        }
    }
    if (type == PullBoxType.ANGLE) {
        ChoiceField("Wall B: largest raceway", trade, largestB, { largestB = it }, optionLabel = { settings.tradeSizeLabel(it) })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Others in row", othersB, { othersB = digits(it) }, Modifier.weight(0.4f))
            ChoiceField("Size", trade, otherSizeB, { otherSizeB = it }, Modifier.weight(0.6f), optionLabel = { settings.tradeSizeLabel(it) })
        }
    }
    val rowA = listOf(largestA) + List(count(othersA).coerceAtMost(20)) { otherSizeA }
    val rowB = listOf(largestB) + List(count(othersB).coerceAtMost(20)) { otherSizeB }
    val result = when (type) {
        PullBoxType.STRAIGHT -> PullBoxSizing.straightPull(listOf(largestA))
        PullBoxType.ANGLE -> PullBoxSizing.anglePull(rowA, rowB)
        PullBoxType.U_PULL -> PullBoxSizing.uPull(rowA)
    }
    fun len(inches: Double) = settings.show(Measure.SMALL_LENGTH, inches, if (settings.isMetric) 0 else 2)
    val results = result?.let { r ->
        listOfNotNull(
            ResultValue.text("pull_box_length", if (type == PullBoxType.ANGLE) "Wall A to opposite wall" else "Minimum length", len(r.minimumLengthIn)),
            r.minimumWidthIn?.let { ResultValue.text("pull_box_width", "Wall B to opposite wall", len(it)) },
            r.minimumDiagonalBetweenSameConductorsIn?.let { ResultValue.text("pull_box_spacing", "Between entries of the same conductors", "at least ${len(it)}") },
        )
    }.orEmpty()
    val othersLargerA = otherSizeA > largestA && count(othersA) > 0
    val othersLargerB = type == PullBoxType.ANGLE && otherSizeB > largestB && count(othersB) > 0
    ResultCard(
        results,
        notices = buildList {
            add(Notice("For conductors 4 AWG and larger (${CodeRule.PULL_BOX_STRAIGHT.cite}, ${CodeRule.PULL_BOX_ANGLE.cite})."))
            if (othersLargerA || othersLargerB) add(Notice("An \"other\" raceway is larger than the one marked largest; the larger one governs.", NoticeLevel.WARNING))
        },
    )
}

@Composable
private fun GecCalc(settings: AppSettings) {
    val sizes = ConductorSize.entries.filter { it >= ConductorSize.AWG_8 }
    var size by rememberSaveable { mutableStateOf(ConductorSize.KCMIL_350) }
    var sets by rememberSaveable { mutableStateOf("1") }
    var serviceMaterial by rememberSaveable { mutableStateOf(ConductorMaterial.COPPER) }
    var gecMaterial by rememberSaveable { mutableStateOf(ConductorMaterial.COPPER) }
    var electrode by rememberSaveable { mutableStateOf(GroundingElectrodeType.FULL_TABLE) }
    val materials = listOf(ConductorMaterial.COPPER, ConductorMaterial.ALUMINUM)

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceField("Service conductor", sizes, size, { size = it }, Modifier.weight(0.6f), optionLabel = { sizeName(settings, it) })
        NumberField("Parallel sets", sets, { sets = digits(it).take(2) }, Modifier.weight(0.4f))
    }
    SegmentedChoice(materials, serviceMaterial, { serviceMaterial = it }, label = "Service conductors", optionLabel = { it.label })
    SegmentedChoice(materials, gecMaterial, { gecMaterial = it }, label = "Grounding conductor", optionLabel = { it.label })
    ChoiceField("Electrode", GroundingElectrodeType.entries, electrode, { electrode = it }, optionLabel = { it.label })
    val parallel = count(sets).coerceAtLeast(1)
    val r = remember(size, parallel, serviceMaterial, gecMaterial, electrode) {
        GecBonding.size(size, serviceMaterial, gecMaterial, parallel, electrode)
    }
    ResultCard(
        listOfNotNull(
            ResultValue.text("gec", "Grounding electrode conductor", sizeName(settings, r.gecSize)),
            r.bondingJumperSize?.let { ResultValue.text("bonding_jumper", "Main or supply-side bonding jumper", sizeName(settings, it)) },
            ResultValue.text("service_area", "Service conductor area", "${Decimals.trim(r.totalServiceCmil / 1000.0, 1)} kcmil"),
        ),
        notices = buildList {
            if (r.gecCapped) add(Notice("Limited by the electrode: the table alone would ask for more (${CodeRule.GEC_GROUND_ROD_MAX.cite}, ${CodeRule.GEC_CONCRETE_ENCASED_MAX.cite})."))
            if (r.bondingJumperCmilRequired != null) {
                add(Notice("Bonding jumper: ${Decimals.trim(r.bondingJumperCmilRequired!! / 1000.0, 1)} kcmil needed, ${CodeRule.SUPPLY_SIDE_BONDING.cite}. With different materials, adjust for the equivalent area."))
            }
            if (gecMaterial == ConductorMaterial.ALUMINUM) add(Notice("Aluminum grounding conductors have installation limits near earth and masonry; check the code.", NoticeLevel.WARNING))
        },
    )
}

@Composable
private fun MotorCalc(settings: AppSettings) {
    var phases by rememberSaveable { mutableStateOf(AcPhases.THREE) }
    val voltages = MotorFullLoadCurrent.voltages(phases)
    var voltsPick by rememberSaveable { mutableStateOf(460) }
    val volts = voltsPick.takeIf { it in voltages } ?: voltages.last()
    val hps = MotorFullLoadCurrent.horsepowers(phases, volts)
    var hpPick by rememberSaveable { mutableStateOf(10.0) }
    val hp = hps.firstOrNull { kotlin.math.abs(it - hpPick) < 1e-6 } ?: hps.minByOrNull { kotlin.math.abs(it - hpPick) } ?: hps.first()
    val types = if (phases == AcPhases.SINGLE) listOf(MotorType.SINGLE_PHASE) else MotorType.entries.filter { it != MotorType.SINGLE_PHASE }
    var typePick by rememberSaveable { mutableStateOf(MotorType.DESIGN_B_EFFICIENT) }
    val type = typePick.takeIf { it in types } ?: types.first()
    var device by rememberSaveable { mutableStateOf(MotorProtectiveDevice.INVERSE_TIME_BREAKER) }
    var material by rememberSaveable { mutableStateOf(ConductorMaterial.COPPER) }
    var fla by rememberSaveable { mutableStateOf("") }
    var highSf by rememberSaveable { mutableStateOf(true) }

    SegmentedChoice(AcPhases.entries, phases, { phases = it }, optionLabel = { it.label })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceField("Horsepower", hps, hp, { hpPick = it }, Modifier.weight(0.5f), optionLabel = { MotorFullLoadCurrent.label(it) })
        ChoiceField("Volts", voltages, volts, { voltsPick = it }, Modifier.weight(0.5f), optionLabel = { "$it V" })
    }
    if (types.size > 1) ChoiceField("Motor type", types, type, { typePick = it }, optionLabel = { it.label })
    ChoiceField("Short-circuit device", MotorProtectiveDevice.entries, device, { device = it }, optionLabel = { it.label })
    SegmentedChoice(listOf(ConductorMaterial.COPPER, ConductorMaterial.ALUMINUM), material, { material = it }, label = "Conductors", optionLabel = { it.label })
    NumberField("Nameplate current (optional)", fla, { fla = it.filter { c -> c.isDigit() || c == '.' }.take(6) }, unit = "A")
    ToggleRow("Service factor 1.15 or more, or 40 °C rise", highSf, { highSf = it })

    val request = MotorCircuitRequest(
        phases = phases, hp = hp, volts = volts, motorType = type, protectiveDevice = device, material = material,
        nameplateFla = Decimals.parse(fla),
        serviceFactor = if (highSf) 1.15 else 1.0,
        tempRiseC = if (highSf) 40.0 else 90.0,
    )
    val r = remember(request) { MotorCircuit.size(request) }
    fun a(x: Double) = "${Decimals.trim(x, 1)} A"
    val results = r?.let {
        listOfNotNull(
            ResultValue.text("motor_flc", "Table full-load current", a(it.flcAmps)),
            ResultValue.text("motor_ampacity", "Minimum conductor ampacity", a(it.minConductorAmpacity)),
            ResultValue.text("motor_wire", "Conductor (${it.ampacityColumn.label})", it.conductorSize?.let { s -> sizeName(settings, s) } ?: "Larger than the table"),
            ResultValue.text("motor_ocpd", "Largest ${device.label.lowercase()}", it.maxStandardOcpdAmps?.let { o -> "$o A (${it.maxOcpdPercent}% → ${a(it.calculatedMaxOcpdAmps)})" } ?: a(it.calculatedMaxOcpdAmps)),
            ResultValue.text("motor_overload", "Overload, at most", "${a(it.maxOverloadAmps)} (${Decimals.trim(it.overloadFactor * 100, 0)}% of ${if (it.usedNameplateForOverload) "nameplate" else "table current"})"),
            ResultValue.text("motor_disconnect", "Disconnect, at least", a(it.minDisconnectAmps)),
            it.egcSize?.let { e -> ResultValue.text("motor_egc", "Equipment ground", sizeName(settings, e)) },
        )
    }.orEmpty()
    ResultCard(
        results,
        notices = buildList {
            if (r != null && !r.usedNameplateForOverload) add(Notice("Size overloads from the nameplate current; the table current is shown until you enter it.", NoticeLevel.WARNING))
            add(Notice("One motor, across the line, 75 °C terminations, no derating. For a drive use VFD Sizing."))
        },
        emptyText = "That horsepower isn't in the table at this voltage.",
    )
}

@Composable
private fun SupportCalc(settings: AppSettings) {
    val types = ConduitType.entries.filter { RacewayInstall.supportRule(it) != null }
    var type by rememberSaveable { mutableStateOf(ConduitType.EMT) }
    var size by rememberSaveable { mutableStateOf(TradeSize.S_3_4) }
    var threaded by rememberSaveable { mutableStateOf(false) }
    val chosen = type.takeIf { it in types } ?: types.first()
    ChoiceField("Raceway", types, chosen, { type = it }, optionLabel = { it.displayName })
    ChoiceField("Trade size", TradeSize.entries.filter { it >= TradeSize.S_1_2 }, size, { size = it }, optionLabel = { settings.tradeSizeLabel(it) })
    val metal = chosen == ConduitType.RMC || chosen == ConduitType.IMC
    if (metal) ToggleRow("Straight run with threaded couplings", threaded, { threaded = it })
    val rule = RacewayInstall.supportRule(chosen)
    val spacing = RacewayInstall.maxSupportSpacingFt(chosen, size, threaded && metal)
    ResultCard(
        listOfNotNull(
            rule?.let { ResultValue.text("support_box", "Secure within", settings.show(Measure.RUN_LENGTH, it.withinBoxFt)) },
            spacing?.let { ResultValue.text("support_spacing", "Support at least every", settings.show(Measure.RUN_LENGTH, it)) },
        ),
        notices = listOfNotNull(rule?.let { Notice("${it.section}. ${it.note.orEmpty()}".trim()) }),
    )
}

@Composable
private fun PvcCalc(settings: AppSettings) {
    var length by rememberSaveable { mutableStateOf("100") }
    var low by rememberSaveable { mutableStateOf("-18") }
    var high by rememberSaveable { mutableStateOf("49") }
    UnitField("Run length", Measure.RUN_LENGTH, length, { length = it })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        UnitField("Coldest", Measure.TEMPERATURE, low, { low = it }, Modifier.weight(1f))
        UnitField("Hottest", Measure.TEMPERATURE, high, { high = it }, Modifier.weight(1f))
    }
    val ft = Decimals.parse(length)
    val lo = Decimals.parse(low)
    val hi = Decimals.parse(high)
    val r = if (ft != null && lo != null && hi != null) RacewayInstall.pvcExpansionCelsius(ft, kotlin.math.abs(hi - lo)) else null
    ResultCard(
        r?.let {
            listOf(
                ResultValue.text("pvc_change", "Length change", settings.show(Measure.SMALL_LENGTH, it.expansionIn, if (settings.isMetric) 0 else 2)),
                ResultValue.text("pvc_fitting", "Expansion fitting", if (it.expansionFittingRequired) "Needed" else "Not needed"),
            )
        }.orEmpty(),
        notices = listOf(Notice("Use the full range the conduit will see, including sun on exposed runs (${CodeRule.PVC_EXPANSION.cite}).")),
        emptyText = "Enter the run length and the temperature range.",
    )
}

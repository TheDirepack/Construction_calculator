package com.thedirepack.constructioncalc.feature.vfdsizing

import com.thedirepack.constructioncalc.core.electrical.MotorFullLoadCurrent
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.ui.components.Notice
import com.thedirepack.constructioncalc.core.ui.components.NoticeLevel
import com.thedirepack.constructioncalc.core.ui.formatDecimal
import com.thedirepack.constructioncalc.feature.vfdsizing.domain.LoadType
import com.thedirepack.constructioncalc.feature.vfdsizing.domain.OutputBasis
import com.thedirepack.constructioncalc.feature.vfdsizing.domain.RatingUnit
import com.thedirepack.constructioncalc.feature.vfdsizing.domain.SupplyPhase
import com.thedirepack.constructioncalc.feature.vfdsizing.domain.VfdRequest
import com.thedirepack.constructioncalc.feature.vfdsizing.domain.VfdResult
import com.thedirepack.constructioncalc.feature.vfdsizing.domain.VfdSizing
import java.math.BigDecimal
import java.math.RoundingMode

/** Everything the screen shows for one set of inputs. */
internal data class VfdSizingOutput(
    val results: List<ResultValue>,
    val notices: List<Notice>,
    /** One-line description for a saved item; blank when there are no results. */
    val summary: String,
)

private fun amps(value: Double) = formatDecimal(value, 1) + " A"

private fun decimalResult(key: String, label: String, kind: ValueKind, value: Double, text: String) =
    ResultValue(key, label, kind, Rational.of(BigDecimal(value).setScale(4, RoundingMode.HALF_UP)).toString(), text)

private fun currentResult(key: String, label: String, value: Double) =
    decimalResult(key, label, ValueKind.CURRENT, value, amps(value))

private fun percentResult(key: String, label: String, value: Double) =
    decimalResult(key, label, ValueKind.PERCENT, value, formatDecimal(value, 1) + " %")

internal fun vfdSizingOutput(inputs: VfdSizingInputs): VfdSizingOutput {
    val parsed = inputs.parse()
    val errors = parsed.problems.map { Notice(it, NoticeLevel.ERROR) }
    val request = parsed.request ?: return VfdSizingOutput(emptyList(), errors, "")
    val result = VfdSizing.calculate(request)
        ?: return VfdSizingOutput(emptyList(), listOf(Notice("This motor can't be calculated.", NoticeLevel.ERROR)), "")
    val metal = request.material
    val duty = request.loadType.dutyLabel

    val results = buildList {
        add(currentResult("min_drive_output", "Min. drive output current ($duty)", result.minDriveOutputAmps))
        add(currentResult("table_flc", "Motor FLC, Table 430.250", result.tableFlc))
        result.nameplatePercentOfTable?.let { add(percentResult("nameplate_pct_of_table", "Nameplate FLA vs. table FLC", it)) }
        result.overloadMaxAmps?.let { add(currentResult("overload_max", "Overload setting, max (125 % of FLA)", it)) }
        result.singlePhaseDriveGuidanceAmps?.let {
            add(currentResult("single_phase_drive_guidance", "Drive output rating for 1-phase input (guidance)", it))
            add(percentResult("single_phase_derate", "1-phase input derate (guidance)", 100.0 / VfdSizing.SINGLE_PHASE_DRIVE_MULTIPLIER))
        }
        add(
            currentResult(
                "input_current_basis",
                when {
                    !result.inputBasisIsEstimate -> "Input current used (drive rating)"
                    request.supplyPhase == SupplyPhase.SINGLE -> "Input current used (estimate = 2 × motor FLC, 1-phase)"
                    else -> "Input current used (estimate = motor FLC)"
                },
                result.inputBasisAmps,
            ),
        )
        add(currentResult("input_ampacity_required", "Input conductor ampacity, min (125 %)", result.inputAmpacityRequired))
        add(ResultValue.text("input_conductor", "Input conductors (${request.termination.label})", result.inputConductor?.text(metal) ?: BEYOND_TABLE))
        add(currentResult("output_ampacity_required", "Motor conductor ampacity, min (125 % of FLC)", result.outputAmpacityRequired))
        add(ResultValue.text("output_conductor", "Motor output conductors (${request.termination.label})", result.outputConductor?.text(metal) ?: BEYOND_TABLE))
        add(currentResult("disconnect_min", "Disconnect rating, min (115 % of input)", result.disconnectMinAmps))
        add(ResultValue.text("breaker_max", "Max inverse-time breaker (250 %)", result.breakerMax?.let { "$it A" } ?: BEYOND_OCPD))
        add(ResultValue.text("fuse_max", "Max dual-element fuse (175 %)", result.fuseMax?.let { "$it A" } ?: BEYOND_OCPD))
    }

    return VfdSizingOutput(results, notices(inputs, parsed.convertedHp, request, result, errors), summary(request, result))
}

private const val BEYOND_TABLE = "Larger than Table 310.16 allows"
private const val BEYOND_OCPD = "Above 6000 A"

private fun notices(
    inputs: VfdSizingInputs,
    convertedHp: Double?,
    request: VfdRequest,
    result: VfdResult,
    errors: List<Notice>,
): List<Notice> = buildList {
    addAll(errors)
    val hpText = MotorFullLoadCurrent.label(request.hp)
    if (inputs.unit == RatingUnit.KW && convertedHp != null) {
        add(Notice("${inputs.kw.trim()} kW treated as $hpText HP (0.7457 kW per HP, next standard rating). Check the motor's nameplate.", NoticeLevel.INFO))
    }

    // Code vs. guidance.
    add(
        Notice(
            "Code: motor full-load current is $hpText HP at ${request.volts} V from NEC 2023 Table 430.250 (three-phase induction motor). " +
                "Conductors and short-circuit protection use this table value, not the nameplate (430.6(A)(1)).",
        ),
    )
    if (result.outputBasis == OutputBasis.TABLE_FLC) {
        add(Notice("No nameplate FLA entered, so the drive rating uses the table FLC. Motors vary: enter the nameplate FLA to size the drive and overload.", NoticeLevel.WARNING))
    } else if (result.minDriveOutputAmps > result.tableFlc) {
        add(Notice("Nameplate FLA is above the table FLC. Check the motor's rating; the drive is sized on the nameplate.", NoticeLevel.WARNING))
    }
    add(
        Notice(
            "Guidance: choose a drive whose continuous output current in ${request.loadType.dutyLabel.lowercase()} " +
                "(${if (request.loadType == LoadType.VARIABLE_TORQUE) "variable torque" else "constant torque"}) mode is at least " +
                "${amps(result.minDriveOutputAmps)}. Drive ratings, overload capacity, altitude and ambient derating come from the manufacturer.",
        ),
    )
    if (request.loadType == LoadType.CONSTANT_TORQUE) {
        add(Notice("Constant-torque loads at low speed can overheat a self-cooled motor; see 430.126 motor overtemperature protection and consider an inverter-duty motor.", NoticeLevel.INFO))
    }
    if (request.supplyPhase == SupplyPhase.SINGLE) {
        add(
            Notice(
                "Guidance, not a code requirement: a three-phase drive on single-phase input is commonly sized about 2× the motor current " +
                    "(or per the manufacturer's single-phase derate table). Confirm the drive is rated for single-phase input; that is usually only offered for small drives at 200-240 V.",
                NoticeLevel.WARNING,
            ),
        )
        if (request.volts >= 460) {
            add(Notice("Single-phase input drives for ${request.volts} V motors are unusual. Check the drive's input rating.", NoticeLevel.WARNING))
        }
    }

    // Conductors.
    if (result.inputBasisIsEstimate) {
        add(
            Notice(
                "Drive input current not entered: 125 % of the motor's table FLC is used as an approximation. " +
                    "430.122(A) requires 125 % of the drive's rated input current, so enter it from the drive's nameplate or manual for a code-based size.",
                NoticeLevel.WARNING,
            ),
        )
        if (request.supplyPhase == SupplyPhase.SINGLE) {
            add(Notice("Single-phase input draws more line current than three-phase for the same load (√3× or more), so the estimate uses 2 × the motor FLC. Enter the drive's rated input current for a code-based size.", NoticeLevel.WARNING))
        }
    }
    add(
        Notice(
            "Output conductors (drive to motor) are 125 % of the table FLC per 430.122(B). If the drive is listed and marked " +
                "“Suitable for Output Motor Conductor Protection,” the larger of that and the drive's marked minimum conductor size applies instead.",
        ),
    )
    listOfNotNull(result.inputConductor?.takeIf { it.sets > 1 }, result.outputConductor?.takeIf { it.sets > 1 }).firstOrNull()?.let {
        add(Notice("Parallel sets assume equal current sharing, each 1/0 AWG or larger (310.10(G)).", NoticeLevel.INFO))
    }
    if (result.inputConductor == null || result.outputConductor == null) {
        add(Notice("The required ampacity is beyond ${VfdSizing.MAX_SETS} parallel 2000 kcmil conductors. Split the load or use a larger system.", NoticeLevel.ERROR))
    }
    add(
        Notice(
            "Ampacity from NEC 2023 Table 310.16 (${request.material.label.lowercase()}, ${request.termination.label} column, 30 °C ambient, not more than three " +
                "current-carrying conductors). Ambient and conductor-count adjustment factors are not applied, and 110.14(C) termination limits are applied only through the column you chose.",
        ),
    )

    // Protection.
    add(
        Notice(
            "Short-circuit/ground-fault maximums are Table 430.52(C)(1): 250 % of FLC for an inverse-time breaker and 175 % for a time-delay fuse, " +
                "rounded up to the next standard rating in 240.6(A) (430.52(C)(1)(a)). Where the drive's manufacturer marks or lists a maximum device size or type, " +
                "that takes precedence and must not be exceeded (430.130(A)(2)). The device must also be at least the drive's input current.",
            NoticeLevel.WARNING,
        ),
    )
    val known = request.driveInputAmps
    if (known != null && result.breakerMax != null && result.breakerMax < known) {
        add(Notice("The breaker maximum is below the drive's input current. Check the drive's required protection.", NoticeLevel.WARNING))
    }
    add(
        Notice(
            "Overload: the drive's electronic overload usually serves (430.124(A)). When set from the nameplate, 125 % of FLA applies to motors with service factor 1.15 or more " +
                "or marked temperature rise of 40 °C or less, otherwise 115 % (430.32(A)(1)). Edition: NEC 2023 (NFPA 70); your jurisdiction may use another.",
        ),
    )
}

private fun summary(request: VfdRequest, result: VfdResult): String = buildString {
    append(MotorFullLoadCurrent.label(request.hp)).append(" HP ").append(request.volts).append(" V, ")
    append(if (request.supplyPhase == SupplyPhase.SINGLE) "1-phase" else "3-phase").append(" input: drive ")
    append(amps(result.minDriveOutputAmps)).append(", ")
    append(result.inputConductor?.text(request.material) ?: "oversize").append(" in, ")
    append(result.outputConductor?.text(request.material) ?: "oversize").append(" out")
}

package com.thedirepack.constructioncalc.core.electrical

import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.electrical.code.DemandTier
import com.thedirepack.constructioncalc.core.electrical.code.codeNumber
import com.thedirepack.constructioncalc.core.electrical.code.codePercent
import kotlin.math.floor
import kotlin.math.max

/**
 * Occupancies with their own row in the lighting demand table ([CodeRule.LIGHTING_DEMAND]).
 * [OTHER] is "all others" (100 %).
 */
enum class LightingOccupancy(val label: String) {
    DWELLING("Dwelling unit"),
    HOTEL_MOTEL("Hotel / motel"),
    WAREHOUSE("Warehouse (storage)"),
    OTHER("All others (100%)"),
}

/** One itemized line in an Article 220 load calculation; [rule] is the citation as text. */
data class LoadLineItem(
    val label: String,
    val connectedVa: Double,
    val demandVa: Double,
    val rule: String,
)

/** Inputs for a standard Article 220 Part III feeder/service load calculation. */
data class StandardLoadInput(
    val occupancy: LightingOccupancy = LightingOccupancy.DWELLING,
    /** General lighting connected load, VA (floor area × the unit load). */
    val generalLightingVa: Double = 0.0,
    /** Dwelling small-appliance branch circuits ([CodeRule.SMALL_APPLIANCE_LAUNDRY]). */
    val smallApplianceCircuits: Int = 0,
    /** Dwelling laundry branch circuits ([CodeRule.SMALL_APPLIANCE_LAUNDRY]). */
    val laundryCircuits: Int = 0,
    /** Non-dwelling receptacle load, VA ([CodeRule.RECEPTACLE_DEMAND]). */
    val receptacleVa: Double = 0.0,
    /** Fastened-in-place appliance nameplate total, VA, excluding cooking, dryers, space heat, A/C and EVSE. */
    val fixedApplianceVa: Double = 0.0,
    /** Number of those appliances ([CodeRule.DWELLING_APPLIANCE_DEMAND]). */
    val fixedApplianceCount: Int = 0,
    /** Household electric clothes dryers ([CodeRule.DRYER_DEMAND]). */
    val dryerCount: Int = 0,
    /** Nameplate VA per dryer (the code's minimum applies). */
    val dryerEachVa: Double = 0.0,
    /** Household ranges and cooking appliances ([CodeRule.RANGE_DEMAND]), all of the same rating. */
    val rangeCount: Int = 0,
    /** Nameplate kW per range. */
    val rangeEachKw: Double = 12.0,
    /** Heating load, VA ([CodeRule.NONCOINCIDENT_LOADS]: the larger of heating and cooling). */
    val heatingVa: Double = 0.0,
    /** Cooling load, VA. */
    val coolingVa: Double = 0.0,
    /** Largest motor, VA ([CodeRule.FEEDER_MOTOR_LOAD]). */
    val largestMotorVa: Double = 0.0,
    /** Other continuous loads, VA ([CodeRule.FEEDER_CONDUCTOR_CONTINUOUS]). */
    val otherContinuousVa: Double = 0.0,
    /** Other noncontinuous loads, VA (100 %). */
    val otherNonContinuousVa: Double = 0.0,
    /** Line-to-line system voltage. */
    val lineVoltage: Int = 240,
    val phases: AcPhases = AcPhases.SINGLE,
)

/** Inputs for the optional dwelling unit calculation ([CodeRule.OPTIONAL_DWELLING]). */
data class OptionalDwellingInput(
    /** Floor area, ft², from the outside dimensions; ignored when [generalLightingVaOverride] is set. */
    val floorAreaSqFt: Double = 0.0,
    val generalLightingVaOverride: Double? = null,
    val smallApplianceCircuits: Int = 2,
    val laundryCircuits: Int = 1,
    /** Nameplate VA of fastened-in-place appliances, ranges, ovens, cooktops, dryers, water heaters and motors. */
    val appliancesVa: Double = 0.0,
    /** Air conditioning and cooling nameplate VA. */
    val coolingVa: Double = 0.0,
    /** Central electric space-heating (supplemental heat) nameplate VA. */
    val centralHeatingVa: Double = 0.0,
    /** Separately controlled electric space-heating units, total VA. */
    val spaceHeatingUnitsVa: Double = 0.0,
    val spaceHeatingUnitCount: Int = 0,
    /** Line-to-line voltage of the 3-wire service (120/240 V or 208Y/120 V). */
    val lineVoltage: Int = 240,
)

/** Result of an Article 220 feeder or service load calculation. */
data class LoadCalculationResult(
    val method: String,
    val connectedVa: Double,
    val demandVa: Double,
    val demandAmps: Double,
    val recommendedOcpdAmps: Int?,
    val breakdown: List<LoadLineItem>,
    val notes: List<String>,
)

/**
 * Article 220 feeder and service load calculations: the standard method (Part III) and the
 * optional dwelling calculation (Part IV). Math only; every factor comes from
 * [Code.book]`.loadCalculation`.
 */
object Article220 {
    private val code get() = Code.book.loadCalculation

    /** Applies cumulative [tiers] to [va]. */
    private fun applyTiers(va: Double, tiers: List<DemandTier>): Double {
        var remaining = max(0.0, va)
        var lower = 0.0
        var total = 0.0
        for (tier in tiers) {
            if (remaining <= 0.0) break
            val span = tier.upToVa?.let { it - lower } ?: remaining
            val portion = minOf(remaining, span)
            total += portion * tier.factor
            remaining -= portion
            lower = tier.upToVa ?: lower
        }
        return total + remaining
    }

    /** Lighting demand VA for [connectedVa] in [occupancy] ([CodeRule.LIGHTING_DEMAND]). */
    fun lightingDemandVa(connectedVa: Double, occupancy: LightingOccupancy): Double =
        code.lightingDemand[occupancy]?.let { applyTiers(connectedVa, it) } ?: max(0.0, connectedVa)

    /** Non-dwelling receptacle demand VA ([CodeRule.RECEPTACLE_DEMAND]). */
    fun receptacleDemandVa(connectedVa: Double): Double = applyTiers(connectedVa, code.receptacleDemand)

    /** Demand factor (fraction) for [count] dryers ([CodeRule.DRYER_DEMAND]). */
    fun dryerDemandFactor(count: Int): Double {
        if (count <= 0) return 0.0
        val row = code.dryerDemand.first { count >= it.from && (it.to == null || count <= it.to) }
        return (row.percent - row.minusPercentPerDryer * (count - (row.from - 1))) / 100.0
    }

    /** Dryer demand VA; each dryer is taken at the larger of [eachVa] and the code's minimum. */
    fun dryerDemandVa(count: Int, eachVa: Double = 0.0): Double {
        if (count <= 0) return 0.0
        return count * max(eachVa, code.dryerMinVa) * dryerDemandFactor(count)
    }

    /**
     * Demand VA for [count] household ranges each rated [eachKw] ([CodeRule.RANGE_DEMAND]):
     * Column A or B where Note 3 permits it (below [LoadCalculationCode.rangeColumnBMaxKw]),
     * otherwise Column C, increased per Note 1 above 12 kW. Null when the rating is outside the
     * table (at or below its minimum, or above the Note 1 limit).
     */
    fun rangeDemandVa(count: Int, eachKw: Double): Double? {
        if (count <= 0) return 0.0
        val c = code
        if (eachKw <= c.rangeMinKw || eachKw > c.rangeNote1MaxKw) return null
        val row = c.rangeDemand.first { count >= it.from && (it.to == null || count <= it.to) }
        val kw = when {
            eachKw < c.rangeColumnAMaxKw -> count * eachKw * row.columnAPercent / 100.0
            eachKw <= c.rangeColumnBMaxKw -> count * eachKw * row.columnBPercent / 100.0
            else -> {
                val columnC = row.columnCKw + row.columnCKwPerRange * count
                val excess = eachKw - c.rangeColumnCMaxKw
                // Note 1: each additional kW, or major fraction (more than half) of one.
                val steps = if (excess <= 0.0) 0 else floor(excess).toInt() + if (excess - floor(excess) > 0.5) 1 else 0
                columnC * (1.0 + c.rangeNote1IncreasePerKw * steps)
            }
        }
        return kw * 1_000.0
    }

    /** Standard calculation, Article 220 Part III. */
    fun standardCalculation(input: StandardLoadInput): LoadCalculationResult {
        require(input.lineVoltage > 0) { "Line voltage must be positive" }
        val c = code
        val lines = mutableListOf<LoadLineItem>()
        val notes = mutableListOf<String>()
        val dwelling = input.occupancy == LightingOccupancy.DWELLING

        // General lighting, with dwelling small-appliance and laundry circuits in the same pool.
        val smallAppVa = if (dwelling) max(0, input.smallApplianceCircuits) * c.smallApplianceCircuitVa else 0.0
        val laundryVa = if (dwelling) max(0, input.laundryCircuits) * c.laundryCircuitVa else 0.0
        val lightingPool = max(0.0, input.generalLightingVa) + smallAppVa + laundryVa
        if (lightingPool > 0.0) {
            val label = if (smallAppVa > 0 || laundryVa > 0) {
                "General lighting, small appliance & laundry"
            } else {
                "General lighting (${input.occupancy.label})"
            }
            val rule = if (smallAppVa > 0 || laundryVa > 0) {
                "${CodeRule.SMALL_APPLIANCE_LAUNDRY.cite}, ${CodeRule.LIGHTING_DEMAND.cite}"
            } else {
                CodeRule.LIGHTING_DEMAND.cite
            }
            lines += LoadLineItem(label, lightingPool, lightingDemandVa(lightingPool, input.occupancy), rule)
        }

        if (!dwelling && input.receptacleVa > 0.0) {
            lines += LoadLineItem("Receptacles", input.receptacleVa, receptacleDemandVa(input.receptacleVa), CodeRule.RECEPTACLE_DEMAND.cite)
        }

        if (input.fixedApplianceVa > 0.0) {
            val reduced = dwelling && input.fixedApplianceCount >= c.dwellingApplianceMinCount
            val demand = if (reduced) input.fixedApplianceVa * c.dwellingApplianceFactor else input.fixedApplianceVa
            val label = if (reduced) "Fixed appliances (${codePercent(c.dwellingApplianceFactor, false)})" else "Fixed appliances"
            lines += LoadLineItem(label, input.fixedApplianceVa, demand, CodeRule.DWELLING_APPLIANCE_DEMAND.cite)
        }

        if (input.dryerCount > 0) {
            val connected = input.dryerCount * max(input.dryerEachVa, c.dryerMinVa)
            lines += LoadLineItem(
                "Electric dryers (${input.dryerCount})",
                connected,
                dryerDemandVa(input.dryerCount, input.dryerEachVa),
                CodeRule.DRYER_DEMAND.cite,
            )
        }

        if (input.rangeCount > 0 && input.rangeEachKw > 0.0) {
            val connected = input.rangeCount * input.rangeEachKw * 1_000.0
            val demand = rangeDemandVa(input.rangeCount, input.rangeEachKw)
            if (demand == null) {
                notes += "Ranges rated ${codeNumber(input.rangeEachKw)} kW are outside ${CodeRule.RANGE_DEMAND.cite}; taken at nameplate."
            }
            lines += LoadLineItem("Electric ranges (${input.rangeCount})", connected, demand ?: connected, CodeRule.RANGE_DEMAND.cite)
        }

        val heat = max(0.0, input.heatingVa)
        val cool = max(0.0, input.coolingVa)
        if (heat > 0.0 || cool > 0.0) {
            val label = if (heat >= cool) "HVAC (heating governs)" else "HVAC (cooling governs)"
            lines += LoadLineItem(label, heat + cool, max(heat, cool), CodeRule.NONCOINCIDENT_LOADS.cite)
        }

        if (input.largestMotorVa > 0.0) {
            lines += LoadLineItem(
                "Largest motor ${codePercent(c.largestMotorAdder, false)} adder",
                0.0,
                input.largestMotorVa * c.largestMotorAdder,
                CodeRule.FEEDER_MOTOR_LOAD.cite,
            )
        }

        if (input.otherContinuousVa > 0.0) {
            val factor = Code.book.values.continuousLoadFactor
            lines += LoadLineItem(
                "Other continuous loads (${codePercent(factor, false)})",
                input.otherContinuousVa,
                input.otherContinuousVa * factor,
                CodeRule.FEEDER_CONDUCTOR_CONTINUOUS.cite,
            )
        }
        if (input.otherNonContinuousVa > 0.0) {
            lines += LoadLineItem("Other loads (100%)", input.otherNonContinuousVa, input.otherNonContinuousVa, "")
        }

        val connected = lines.sumOf { it.connectedVa }
        val demand = lines.sumOf { it.demandVa }
        val amps = demand / (input.phases.powerFactorK * input.lineVoltage)
        notes += "Standard calculation, ${Code.label} Article 220 Part III (${input.phases.label}, ${input.lineVoltage} V)."
        return LoadCalculationResult(
            method = "${Code.label} Article 220 standard",
            connectedVa = connected,
            demandVa = demand,
            demandAmps = amps,
            recommendedOcpdAmps = if (amps > 0.0) StandardOcpd.nextUp(amps) else null,
            breakdown = lines,
            notes = notes,
        )
    }

    /** Optional dwelling unit calculation ([CodeRule.OPTIONAL_DWELLING]). */
    fun optionalDwellingCalculation(input: OptionalDwellingInput): LoadCalculationResult {
        require(input.lineVoltage > 0) { "Line voltage must be positive" }
        val c = code
        val lines = mutableListOf<LoadLineItem>()
        val notes = mutableListOf<String>()

        val lightingVa = input.generalLightingVaOverride?.let { max(0.0, it) }
            ?: (max(0.0, input.floorAreaSqFt) * c.optionalVaPerSqFt)
        val general = lightingVa +
            max(0, input.smallApplianceCircuits) * c.smallApplianceCircuitVa +
            max(0, input.laundryCircuits) * c.laundryCircuitVa +
            max(0.0, input.appliancesVa)
        if (general > 0.0) {
            val demand = minOf(general, c.optionalFirstVa) + max(general - c.optionalFirstVa, 0.0) * c.optionalRemainderFactor
            lines += LoadLineItem(
                "General loads (100% of first ${codeNumber(c.optionalFirstVa / 1_000.0)} kVA, ${codePercent(c.optionalRemainderFactor, false)} of remainder)",
                general,
                demand,
                CodeRule.OPTIONAL_DWELLING_GENERAL.cite,
            )
        }

        val cooling = max(0.0, input.coolingVa) * c.optionalCoolingFactor
        val centralHeat = max(0.0, input.centralHeatingVa) * c.optionalCentralHeatFactor
        val manyUnits = input.spaceHeatingUnitCount >= c.optionalSpaceHeatManyCount
        val unitFactor = if (manyUnits) c.optionalSpaceHeatManyFactor else c.optionalSpaceHeatFewFactor
        val unitHeat = max(0.0, input.spaceHeatingUnitsVa) * unitFactor
        val hvacConnected = max(0.0, input.coolingVa) + max(0.0, input.centralHeatingVa) + max(0.0, input.spaceHeatingUnitsVa)
        if (hvacConnected > 0.0) {
            val largest = maxOf(cooling, centralHeat, unitHeat)
            val label = when (largest) {
                cooling -> "HVAC: cooling (${codePercent(c.optionalCoolingFactor, false)})"
                centralHeat -> "HVAC: central electric heat (${codePercent(c.optionalCentralHeatFactor, false)})"
                else -> "HVAC: space-heating units (${codePercent(unitFactor, false)})"
            }
            lines += LoadLineItem(label, hvacConnected, largest, CodeRule.OPTIONAL_DWELLING_HVAC.cite)
        }

        val connected = lines.sumOf { it.connectedVa }
        val demand = lines.sumOf { it.demandVa }
        val amps = demand / input.lineVoltage
        notes += "Optional dwelling calculation, ${Code.label} ${CodeRule.OPTIONAL_DWELLING.cite} (single-phase, ${input.lineVoltage} V)."
        if (amps > 0.0 && amps < c.optionalMinAmpacity) {
            notes += "${CodeRule.OPTIONAL_DWELLING.cite} applies to service or feeder conductors with an ampacity of ${c.optionalMinAmpacity} A or more."
        }
        return LoadCalculationResult(
            method = "${Code.label} ${CodeRule.OPTIONAL_DWELLING.cite} optional dwelling",
            connectedVa = connected,
            demandVa = demand,
            demandAmps = amps,
            recommendedOcpdAmps = if (amps > 0.0) StandardOcpd.nextUp(amps) else null,
            breakdown = lines,
            notes = notes,
        )
    }
}

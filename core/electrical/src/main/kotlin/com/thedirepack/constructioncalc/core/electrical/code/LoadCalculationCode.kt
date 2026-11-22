package com.thedirepack.constructioncalc.core.electrical.code

import com.thedirepack.constructioncalc.core.electrical.LightingOccupancy

/**
 * One demand-factor tier: the portion of the load up to [upToVa] (cumulative; null for "remainder")
 * is taken at [factor].
 */
data class DemandTier(val upToVa: Double?, val factor: Double)

/**
 * One clothes-dryer demand row ([CodeRule.DRYER_DEMAND]): [from] to [to] dryers ([to] null: "and
 * over") at [percent], less [minusPercentPerDryer] for each dryer exceeding `from - 1`.
 */
data class DryerDemandRow(val from: Int, val to: Int?, val percent: Double, val minusPercentPerDryer: Double = 0.0)

/**
 * One household cooking appliance row ([CodeRule.RANGE_DEMAND]) for [from] to [to] appliances
 * ([to] null: "and over"). Column A and B are percent demand factors; Column C is the maximum
 * demand [columnCKw] plus [columnCKwPerRange] for each range.
 */
data class RangeDemandRow(
    val from: Int,
    val to: Int?,
    val columnAPercent: Double,
    val columnBPercent: Double,
    val columnCKw: Double,
    val columnCKwPerRange: Double = 0.0,
)

/** Article 220 feeder and service load calculation values for one edition. */
data class LoadCalculationCode(
    /** [CodeRule.LIGHTING_DEMAND]: tiers per occupancy. An occupancy with no row is taken at 100 %. */
    val lightingDemand: Map<LightingOccupancy, List<DemandTier>>,
    /** [CodeRule.RECEPTACLE_DEMAND]: non-dwelling receptacle tiers. */
    val receptacleDemand: List<DemandTier>,
    /** [CodeRule.SMALL_APPLIANCE_LAUNDRY]: VA per small-appliance branch circuit. */
    val smallApplianceCircuitVa: Double,
    /** [CodeRule.SMALL_APPLIANCE_LAUNDRY]: VA per laundry branch circuit. */
    val laundryCircuitVa: Double,
    /** [CodeRule.DWELLING_APPLIANCE_DEMAND]: fewest fastened-in-place appliances the factor applies to. */
    val dwellingApplianceMinCount: Int,
    /** [CodeRule.DWELLING_APPLIANCE_DEMAND]: demand factor. */
    val dwellingApplianceFactor: Double,
    /** [CodeRule.DRYER_DEMAND]: minimum VA per dryer. */
    val dryerMinVa: Double,
    /** [CodeRule.DRYER_DEMAND] rows, fewest dryers first. */
    val dryerDemand: List<DryerDemandRow>,
    /** [CodeRule.RANGE_DEMAND] rows, fewest appliances first. */
    val rangeDemand: List<RangeDemandRow>,
    /** [CodeRule.RANGE_DEMAND]: smallest appliance rating the table covers, kW (exclusive). */
    val rangeMinKw: Double,
    /** [CodeRule.RANGE_DEMAND]: Column A covers ratings below this, kW. */
    val rangeColumnAMaxKw: Double,
    /** [CodeRule.RANGE_DEMAND]: Column B covers ratings up to this, kW (Note 3 permits A/B up to here). */
    val rangeColumnBMaxKw: Double,
    /** [CodeRule.RANGE_DEMAND]: Column C applies to ratings up to this, kW. */
    val rangeColumnCMaxKw: Double,
    /** [CodeRule.RANGE_DEMAND] Note 1: the increase applies up to this rating, kW. */
    val rangeNote1MaxKw: Double,
    /** [CodeRule.RANGE_DEMAND] Note 1: increase per kW (or major fraction) over [rangeColumnCMaxKw]. */
    val rangeNote1IncreasePerKw: Double,
    /** [CodeRule.FEEDER_MOTOR_LOAD]: fraction of the largest motor added to the feeder load. */
    val largestMotorAdder: Double,

    /** [CodeRule.OPTIONAL_DWELLING]: minimum service/feeder ampacity the method applies to. */
    val optionalMinAmpacity: Int,
    /** [CodeRule.OPTIONAL_DWELLING_GENERAL]: general lighting and general-use receptacles, VA per ft². */
    val optionalVaPerSqFt: Double,
    /** [CodeRule.OPTIONAL_DWELLING_GENERAL]: general loads taken at 100 % up to this VA. */
    val optionalFirstVa: Double,
    /** [CodeRule.OPTIONAL_DWELLING_GENERAL]: factor for the remainder. */
    val optionalRemainderFactor: Double,
    /** [CodeRule.OPTIONAL_DWELLING_HVAC] (1): air conditioning and cooling factor. */
    val optionalCoolingFactor: Double,
    /** [CodeRule.OPTIONAL_DWELLING_HVAC] (3): central electric space-heating (supplemental) factor. */
    val optionalCentralHeatFactor: Double,
    /** [CodeRule.OPTIONAL_DWELLING_HVAC] (4): fewer than [optionalSpaceHeatManyCount] separately controlled units. */
    val optionalSpaceHeatFewFactor: Double,
    /** [CodeRule.OPTIONAL_DWELLING_HVAC] (5): [optionalSpaceHeatManyCount] or more separately controlled units. */
    val optionalSpaceHeatManyFactor: Double,
    /** [CodeRule.OPTIONAL_DWELLING_HVAC]: unit count at which [optionalSpaceHeatManyFactor] applies. */
    val optionalSpaceHeatManyCount: Int,
)

package com.thedirepack.constructioncalc.core.electrical.code

import com.thedirepack.constructioncalc.core.electrical.AcPhases
import com.thedirepack.constructioncalc.core.electrical.BendRadiusColumn
import com.thedirepack.constructioncalc.core.electrical.ConductorDimensions
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.ConduitSize
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.ImpedanceRow
import com.thedirepack.constructioncalc.core.electrical.InsulationType
import com.thedirepack.constructioncalc.core.electrical.MotorProtectiveDevice
import com.thedirepack.constructioncalc.core.electrical.MotorType
import com.thedirepack.constructioncalc.core.electrical.Stranding
import com.thedirepack.constructioncalc.core.electrical.TemperatureRating
import com.thedirepack.constructioncalc.core.electrical.TradeSize

/**
 * One edition of the electrical code: everything the code defines, in one place.
 *
 * The app's calculations (`Ampacity`, `ConduitFill`, `ConductorSizing`…) contain only math and read
 * every code-defined number from [Code.book]. The math stays the same from edition to edition; the
 * values and the section numbers are what change, and they live only in an implementation of this
 * interface (e.g. `code/nec2023/Nec2023`). See docs/CODE_UPDATES.md.
 *
 * Tables are keyed by the app's vocabulary (`ConductorSize`, `ConduitType`, `InsulationType`…), which
 * is the same in every edition. Where a table has one column per conductor metal, the key is the
 * [ConductorMaterial.tableBasis] (copper or aluminum).
 */
interface ElectricalCode {
    val edition: CodeEdition

    /** The section or table number for [rule], e.g. "310.15(B)(2)" or "Table 310.16". */
    fun cite(rule: CodeRule): String

    /** Single values (percentages, factors, limits), each naming the rule it comes from. */
    val values: CodeValues

    // ---- Conductors ----

    /** [CodeRule.AMPACITY_TABLE] by table-basis material (copper, aluminum). */
    val ampacity: Map<ConductorMaterial, Map<ConductorSize, AmpacityRow>>

    /** [CodeRule.AMBIENT_CORRECTION], coolest band first. */
    val ambientCorrection: List<AmbientBand>

    /** [CodeRule.CONDUCTOR_COUNT_ADJUSTMENT], fewest conductors first. Counts below the first band need no adjustment. */
    val conductorCountAdjustment: List<CountAdjustmentBand>

    /** [CodeRule.CONDUCTOR_INSULATION]: the Table 310.16 column of each insulation. Absent: not rated in 310.16 (fixture wire). */
    val insulationRatings: Map<InsulationType, TemperatureRating>

    /** [CodeRule.CONDUCTOR_DIMENSIONS]: area and diameter of insulated conductors, every [InsulationType]. */
    val insulatedConductorDimensions: Map<InsulationType, Map<ConductorSize, ConductorDimensions>>

    /** [CodeRule.CONDUCTOR_PROPERTIES]: overall area and diameter of bare conductors. */
    val bareConductorDimensions: Map<Stranding, Map<ConductorSize, ConductorDimensions>>

    /** [CodeRule.CONDUCTOR_PROPERTIES]: DC resistance at [CodeValues.resistanceReferenceC]. */
    val conductorResistance: Map<ConductorSize, ResistanceRow>

    /** [CodeRule.AC_IMPEDANCE] by table-basis material (copper, aluminum). */
    val acImpedance: Map<ConductorMaterial, Map<ConductorSize, ImpedanceRow>>

    // ---- Overcurrent protection and grounding ----

    /** [CodeRule.STANDARD_OCPD]: standard ratings of fuses and inverse-time breakers, ascending. */
    val standardOcpdRatings: List<Int>

    /** [CodeRule.STANDARD_OCPD]: additional standard ratings for fuses only, ascending. */
    val fuseOnlyRatings: List<Int>

    /**
     * [CodeRule.SMALL_CONDUCTORS], keyed by [ConductorMaterial.COPPER], [ConductorMaterial.ALUMINUM]
     * and [ConductorMaterial.COPPER_CLAD_ALUMINUM] (coated copper uses copper).
     */
    val smallConductorLimits: Map<ConductorMaterial, Map<ConductorSize, SmallConductorLimitRow>>

    /** [CodeRule.EGC_SIZE], ascending by rating. */
    val equipmentGroundingConductors: List<EgcRow>

    // ---- Raceways ----

    /** [CodeRule.CONDUIT_DIMENSIONS]: every [ConduitType], each ascending by trade size. */
    val conduitDimensions: Map<ConduitType, List<ConduitSize>>

    /** [CodeRule.BEND_RADIUS], by trade size. */
    val bendRadius: Map<TradeSize, BendRadiusRow>

    /** The article for every [ConduitType]: its number, the bend-radius column it names, and its bend-limit section. */
    val raceways: Map<ConduitType, RacewayArticle>

    // ---- Motors ----

    /** [CodeRule.MOTOR_FLC_SINGLE_PHASE] and [CodeRule.MOTOR_FLC_THREE_PHASE] rows, smallest horsepower first. */
    val motorFullLoadCurrent: Map<AcPhases, List<MotorFlcRow>>

    /** The voltage columns of each full-load current table. */
    val motorFullLoadVoltages: Map<AcPhases, List<Int>>

    /** [CodeRule.MOTOR_BRANCH_PROTECTION]: maximum rating, percent of full-load current. */
    val motorBranchProtection: Map<MotorType, Map<MotorProtectiveDevice, Int>>

    // ---- General ----

    /** [CodeRule.WORKING_SPACE] rows, lowest voltage first. */
    val workingSpace: List<WorkingSpaceRow>
}

/** One row of the ampacity table, amperes; null where the table cell is blank. */
data class AmpacityRow(val a60: Int?, val a75: Int?, val a90: Int?) {
    fun of(rating: TemperatureRating): Int? = when (rating) {
        TemperatureRating.C60 -> a60
        TemperatureRating.C75 -> a75
        TemperatureRating.C90 -> a90
    }
}

/** One ambient-correction row: ambients up to [maxC] (whole degrees). Null: too hot for that insulation. */
data class AmbientBand(val maxC: Double, val c60: Double?, val c75: Double?, val c90: Double?) {
    fun of(rating: TemperatureRating): Double? = when (rating) {
        TemperatureRating.C60 -> c60
        TemperatureRating.C75 -> c75
        TemperatureRating.C90 -> c90
    }
}

/** One adjustment row: [from] to [to] current-carrying conductors ([to] null: "and above"). */
data class CountAdjustmentBand(val from: Int, val to: Int?, val factor: Double)

/**
 * A small-conductor limit: the largest overcurrent device, and for sizes that carry extra
 * conditions, the continuous-load limit and the subsection that states them.
 */
data class SmallConductorLimitRow(
    val maxOcpdAmps: Int,
    val maxContinuousAmps: Double? = null,
    val conditionsRule: CodeRule? = null,
)

/** One equipment grounding conductor row: OCPD rating or setting not exceeding [maxOcpdAmps]. */
data class EgcRow(val maxOcpdAmps: Int, val copper: ConductorSize, val aluminum: ConductorSize)

/** One DC-resistance row, Ω/1000 ft; the solid values exist only for the sizes the table lists solid. */
data class ResistanceRow(
    val uncoated: Double,
    val coated: Double,
    val aluminum: Double,
    val uncoatedSolid: Double? = null,
    val coatedSolid: Double? = null,
    val aluminumSolid: Double? = null,
)

/** One bend-radius row, inches to the conduit centerline. */
data class BendRadiusRow(val oneShotIn: Double, val otherIn: Double)

/**
 * A raceway type's article.
 *
 * @property article e.g. "Article 358"
 * @property bendColumn the [CodeRule.BEND_RADIUS] column its bends section points to
 * @property bendLimitSection the section limiting the degrees of bends between pull points, e.g. "358.24(B)"
 */
data class RacewayArticle(val article: String, val bendColumn: BendRadiusColumn, val bendLimitSection: String)

/** One motor full-load current row. [amps] is keyed by volts; a voltage with no entry is a blank cell. */
data class MotorFlcRow(val hp: Double, val label: String, val amps: Map<Int, Double>)

/** One working-space voltage row. Depths per condition (1, 2, 3), inches and the code's millimetres. */
data class WorkingSpaceRow(val voltsToGround: String, val maxVolts: Int, val depthIn: List<Int>, val depthMm: List<Int>)

/**
 * Single code values. Each is the number one rule states; the property's KDoc names the [CodeRule].
 * Calculations read these instead of writing the number themselves.
 */
data class CodeValues(
    /** Table basis ambient, °C, of [CodeRule.AMPACITY_TABLE] and [CodeRule.AMBIENT_CORRECTION]. */
    val ambientBasisC: Double,
    /** [CodeRule.ROOFTOP_ADDER]: °C added for raceways in sunlight less than 3/4 in. above a roof. */
    val rooftopAdderC: Double,
    /** [CodeRule.BRANCH_CONDUCTOR_CONTINUOUS], [CodeRule.FEEDER_CONDUCTOR_CONTINUOUS]: continuous load multiplier (1.25). */
    val continuousLoadFactor: Double,
    /** [CodeRule.NEXT_SIZE_UP]: the next higher standard rating is allowed up to this many amperes. */
    val nextSizeUpMaxAmps: Int,
    /** [CodeRule.MINIMUM_CONDUCTOR_SIZE]: smallest copper conductor. */
    val minimumSizeCopper: ConductorSize,
    /** [CodeRule.MINIMUM_CONDUCTOR_SIZE]: smallest aluminum or copper-clad aluminum conductor. */
    val minimumSizeAluminum: ConductorSize,
    /** [CodeRule.PARALLEL_CONDUCTORS]: smallest conductor allowed in parallel. */
    val parallelMinimumSize: ConductorSize,
    /** [CodeRule.TERMINATION_TEMPERATURE]: circuits up to this rating use the 60 °C column unless marked otherwise. */
    val terminationLowColumnMaxAmps: Int,
    /** [CodeRule.TERMINATION_TEMPERATURE]: conductors up to this size use the 60 °C column unless marked otherwise. */
    val terminationLowColumnMaxSize: ConductorSize,

    /** [CodeRule.FILL_PERCENT]: one conductor, percent of the raceway's area. */
    val fillOneConductorPercent: Int,
    /** [CodeRule.FILL_PERCENT]: two conductors. */
    val fillTwoConductorsPercent: Int,
    /** [CodeRule.FILL_PERCENT]: more than two conductors. */
    val fillOverTwoConductorsPercent: Int,
    /** [CodeRule.FILL_NIPPLES]: nipples, any number of conductors. */
    val fillNipplePercent: Int,
    /** [CodeRule.FILL_NIPPLES], [CodeRule.SHORT_RACEWAY_ADJUSTMENT]: longest raceway counted as a nipple, inches. */
    val nippleMaxLengthIn: Double,
    /** [CodeRule.FILL_ROUNDING]: a count's decimal part at or above this rounds up. */
    val note7RoundUpFraction: Double,
    /** [CodeRule.JAM_RATIO]: lower end of the jam range (raceway ID / conductor OD). */
    val jamRatioLow: Double,
    /** [CodeRule.JAM_RATIO]: upper end of the jam range. */
    val jamRatioHigh: Double,
    /** The raceway articles' "Bends — Number in One Run" ([RacewayArticle.bendLimitSection]): degrees between pull points. */
    val maxBendDegreesBetweenPullPoints: Double,

    /** [CodeRule.CONDUCTOR_PROPERTIES]: temperature of the DC resistance values, °C. */
    val resistanceReferenceC: Double,
    /** [CodeRule.CONDUCTOR_PROPERTIES] notes: temperature coefficient of copper, per °C. */
    val resistanceCoefficientCopper: Double,
    /** [CodeRule.CONDUCTOR_PROPERTIES] notes: temperature coefficient of aluminum, per °C. */
    val resistanceCoefficientAluminum: Double,
    /** [CodeRule.AC_IMPEDANCE]: the power factor of the table's "Effective Z" columns. */
    val acImpedanceTablePowerFactor: Double,

    /** [CodeRule.VOLTAGE_DROP_BRANCH]: recommended maximum drop on a branch circuit, percent. */
    val voltageDropBranchPercent: Double,
    /** [CodeRule.VOLTAGE_DROP_FEEDER]: recommended maximum drop on a feeder, percent. */
    val voltageDropFeederPercent: Double,
    /** [CodeRule.VOLTAGE_DROP_BRANCH]: recommended maximum drop, feeder and branch circuit together, percent. */
    val voltageDropTotalPercent: Double,

    /** [CodeRule.MOTOR_CONDUCTORS]: single-motor conductors, multiple of full-load current. */
    val motorConductorFactor: Double,
    /** [CodeRule.MOTOR_DISCONNECT_RATING]: motor disconnect, multiple of full-load current. */
    val motorDisconnectFactor: Double,
    /** [CodeRule.MOTOR_OVERLOAD]: overload, service factor at least [motorOverloadServiceFactor] or rise at most [motorOverloadMaxRiseC]. */
    val motorOverloadFactor: Double,
    /** [CodeRule.MOTOR_OVERLOAD]: overload for all other motors. */
    val motorOverloadFactorOther: Double,
    /** [CodeRule.MOTOR_OVERLOAD]: marked service factor that allows [motorOverloadFactor]. */
    val motorOverloadServiceFactor: Double,
    /** [CodeRule.MOTOR_OVERLOAD]: marked temperature rise, °C, at or below which [motorOverloadFactor] applies. */
    val motorOverloadMaxRiseC: Double,
    /** [CodeRule.DRIVE_INPUT_CONDUCTORS]: drive input conductors, multiple of the drive's rated input current. */
    val driveInputConductorFactor: Double,
    /** [CodeRule.DRIVE_OUTPUT_CONDUCTORS]: drive-to-motor conductors, multiple of motor full-load current. */
    val driveOutputConductorFactor: Double,
    /** [CodeRule.DRIVE_DISCONNECT_RATING]: drive disconnect, multiple of the drive's rated input current. */
    val driveDisconnectFactor: Double,

    /** [CodeRule.WORKING_SPACE_WIDTH]: minimum width, inches (or the equipment's width). */
    val workingSpaceMinWidthIn: Int,
    /** [CodeRule.WORKING_SPACE_HEIGHT]: minimum height, inches (or the equipment's height). */
    val workingSpaceMinHeightIn: Int,
)

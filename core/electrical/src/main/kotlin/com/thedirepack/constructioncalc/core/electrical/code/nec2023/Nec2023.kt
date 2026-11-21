package com.thedirepack.constructioncalc.core.electrical.code.nec2023

import com.thedirepack.constructioncalc.core.electrical.code.CodeEdition
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule.*
import com.thedirepack.constructioncalc.core.electrical.code.ElectricalCode

/**
 * NFPA 70, National Electrical Code, 2023 edition: where each [CodeRule] is in the book, and every
 * value the app takes from it. Each number was checked against the printed edition. Only numbers
 * live here, never the code's text.
 *
 * The values are data only, one file per part of the book:
 * - [Nec2023Values]: single values (percentages, factors, limits);
 * - [Nec2023Conductors]: Tables 310.16, 310.15(B)(1)(1), 310.15(C)(1), 310.4(1) ratings, Chapter 9 Tables 5, 8, 9;
 * - [Nec2023Protection]: 240.6(A), 240.4(D), Table 250.122;
 * - [Nec2023Raceways]: Chapter 9 Tables 2 and 4, and each raceway's article;
 * - [Nec2023Motors]: Tables 430.248, 430.250, 430.52(C)(1);
 * - [Nec2023General]: Table 110.26(A)(1).
 *
 * Moving to a new edition: copy this package (e.g. `nec2026`), check every line against the new
 * book and point [com.thedirepack.constructioncalc.core.electrical.code.Code.book] at it.
 * docs/CODE_UPDATES.md has the checklist.
 */
object Nec2023 : ElectricalCode {
    override val edition = CodeEdition.NEC_2023

    private val citations: Map<CodeRule, String> = mapOf(
        TERMINATION_TEMPERATURE to "110.14(C)",
        WORKING_SPACE to "Table 110.26(A)(1)",
        WORKING_SPACE_WIDTH to "110.26(A)(2)",
        WORKING_SPACE_HEIGHT to "110.26(A)(3)",
        GROUNDED_IDENTIFICATION to "200.6",
        UNGROUNDED_IDENTIFICATION to "210.5(C)",
        FEEDER_IDENTIFICATION to "215.12(C)",

        // 210.19(A)(1) and (A)(2), checked on printed pages 70-83 and 70-84.
        BRANCH_CONDUCTOR_SIZE to "210.19",
        BRANCH_CONDUCTOR_CONTINUOUS to "210.19(A)(1)",
        BRANCH_CONDUCTOR_ADJUSTED to "210.19(A)(2)",
        FEEDER_CONDUCTOR_CONTINUOUS to "215.2(A)(1)",
        VOLTAGE_DROP_BRANCH to "210.19 Informational Note",
        VOLTAGE_DROP_FEEDER to "215.2(A)(2) Informational Note No. 2",
        CONDUCTOR_PROTECTION to "240.4",
        NEXT_SIZE_UP to "240.4(B)",
        SMALL_CONDUCTORS to "240.4(D)",
        SMALL_CONDUCTOR_18_CU to "240.4(D)(1)",
        SMALL_CONDUCTOR_16_CU to "240.4(D)(2)",
        SMALL_CONDUCTOR_14_CCA to "240.4(D)(3)",
        STANDARD_OCPD to "240.6(A)",

        EGC_SIZE to "Table 250.122",
        EGC_INCREASED to "250.122(B)",
        EGC_IDENTIFICATION to "250.119",
        EGC_MULTIPLE_CIRCUITS to "250.122(C)",

        MINIMUM_CONDUCTOR_SIZE to "310.3(A)",
        CONDUCTOR_INSULATION to "310.4",
        PARALLEL_CONDUCTORS to "310.10(G)",
        AMPACITY to "310.15",
        AMPACITY_TABLE to "Table 310.16",
        AMBIENT_CORRECTION to "Table 310.15(B)(1)(1)",
        ROOFTOP_ADDER to "310.15(B)(2)",
        CONDUCTOR_COUNT_ADJUSTMENT to "Table 310.15(C)(1)",
        SHORT_RACEWAY_ADJUSTMENT to "310.15(C)(1)(b)",
        NEUTRAL_COUNTING to "310.15(E)",
        GROUNDING_COUNTING to "310.15(F)",

        MOTOR_TABLE_FLC to "430.6(A)(1)",
        MOTOR_CONDUCTORS to "430.22",
        MOTOR_DISCONNECT_RATING to "430.110(A)",
        MOTOR_OVERLOAD to "430.32(A)(1)",
        MOTOR_BRANCH_PROTECTION to "Table 430.52(C)(1)",
        MOTOR_NEXT_HIGHER_RATING to "430.52(C)(1)(a)",
        MOTOR_OVERTEMPERATURE to "430.126",
        MOTOR_FLC_SINGLE_PHASE to "Table 430.248",
        MOTOR_FLC_THREE_PHASE to "Table 430.250",
        DRIVE_DISCONNECT_RATING to "430.128",
        DRIVE_INPUT_CONDUCTORS to "430.122(A)",
        DRIVE_OUTPUT_CONDUCTORS to "430.122(B)",
        DRIVE_OVERLOAD to "430.124(A)",
        DRIVE_SHORT_CIRCUIT to "430.130(A)(2)",

        FILL_PERCENT to "Chapter 9 Table 1",
        FILL_ROUNDING to "Chapter 9 Note (7)",
        FILL_NIPPLES to "Chapter 9 Note (4)",
        FILL_GROUNDING_CONDUCTORS to "Chapter 9 Note (3)",
        JAM_RATIO to "Chapter 9 Table 1 Informational Note No. 2",
        BEND_RADIUS to "Chapter 9 Table 2",
        CONDUIT_DIMENSIONS to "Chapter 9 Table 4",
        CONDUCTOR_DIMENSIONS to "Chapter 9 Table 5",
        COMPACT_CONDUCTOR_DIMENSIONS to "Chapter 9 Table 5A",
        CONDUCTOR_PROPERTIES to "Chapter 9 Table 8",
        AC_IMPEDANCE to "Chapter 9 Table 9",
        CONDUCTORS_IN_CONDUIT to "Annex C",
    )

    override fun cite(rule: CodeRule): String = citations.getValue(rule)

    /** For tests: every rule has a number in this edition. */
    internal val missing: List<CodeRule> get() = CodeRule.entries.filter { it !in citations }

    override val values = Nec2023Values.values

    override val ampacity = Nec2023Conductors.ampacity
    override val ambientCorrection = Nec2023Conductors.ambientCorrection
    override val conductorCountAdjustment = Nec2023Conductors.conductorCountAdjustment
    override val insulationRatings = Nec2023Conductors.insulationRatings
    override val insulatedConductorDimensions = Nec2023Conductors.insulatedConductorDimensions
    override val bareConductorDimensions = Nec2023Conductors.bareConductorDimensions
    override val conductorResistance = Nec2023Conductors.conductorResistance
    override val acImpedance = Nec2023Conductors.acImpedance

    override val standardOcpdRatings = Nec2023Protection.standardOcpdRatings
    override val fuseOnlyRatings = Nec2023Protection.fuseOnlyRatings
    override val smallConductorLimits = Nec2023Protection.smallConductorLimits
    override val equipmentGroundingConductors = Nec2023Protection.equipmentGroundingConductors

    override val conduitDimensions = Nec2023Raceways.conduitDimensions
    override val bendRadius = Nec2023Raceways.bendRadius
    override val raceways = Nec2023Raceways.raceways

    override val motorFullLoadCurrent = Nec2023Motors.fullLoadCurrent
    override val motorFullLoadVoltages = Nec2023Motors.fullLoadVoltages
    override val motorBranchProtection = Nec2023Motors.branchProtection

    override val workingSpace = Nec2023General.workingSpace
}

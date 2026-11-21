package com.thedirepack.constructioncalc.core.electrical.code

import com.thedirepack.constructioncalc.core.electrical.code.CodeRule.*

/**
 * NFPA 70, National Electrical Code, 2023 edition: where each [CodeRule] is in the book. Each
 * number was checked against the printed edition. Only the numbers live here, never the code's text.
 *
 * Moving to a new edition: copy this object (e.g. `Nec2026`), check every line against the new book
 * and point [Code.book] at it. docs/CODE_UPDATES.md lists the tables that also need checking.
 */
object Nec2023 : CodeBook {
    override val edition = CodeEdition.NEC_2023

    private val citations: Map<CodeRule, String> = mapOf(
        TERMINATION_TEMPERATURE to "110.14(C)",
        WORKING_SPACE to "Table 110.26(A)(1)",

        BRANCH_CONDUCTOR_SIZE to "210.19",
        BRANCH_CONDUCTOR_CONTINUOUS to "210.19(A)(1)(a)",
        BRANCH_CONDUCTOR_ADJUSTED to "210.19(A)(1)(b)",
        CONDUCTOR_PROTECTION to "240.4",
        NEXT_SIZE_UP to "240.4(B)",
        SMALL_CONDUCTORS to "240.4(D)",
        SMALL_CONDUCTOR_18_CU to "240.4(D)(1)",
        SMALL_CONDUCTOR_16_CU to "240.4(D)(2)",
        SMALL_CONDUCTOR_14_CCA to "240.4(D)(3)",
        STANDARD_OCPD to "240.6(A)",

        EGC_SIZE to "Table 250.122",
        EGC_INCREASED to "250.122(B)",
        EGC_MULTIPLE_CIRCUITS to "250.122(C)",

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
        MOTOR_OVERLOAD to "430.32(A)(1)",
        MOTOR_BRANCH_PROTECTION to "Table 430.52(C)(1)",
        MOTOR_NEXT_HIGHER_RATING to "430.52(C)(1)(a)",
        MOTOR_OVERTEMPERATURE to "430.126",
        MOTOR_FLC_SINGLE_PHASE to "Table 430.248",
        MOTOR_FLC_THREE_PHASE to "Table 430.250",
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
}

package com.thedirepack.constructioncalc.core.electrical.code

/** A published code edition that the app's tables and rules come from. */
enum class CodeEdition(val label: String, val year: Int) {
    NEC_2023("NEC 2023", 2023),
}

/**
 * Every code provision the app relies on, by a stable id that stays the same from edition to
 * edition. The section or table number for an id lives in the [CodeBook] for the edition
 * (e.g. [Nec2023]), so a renumbered rule in a new edition is a one-line change there instead of a
 * hunt through every tool's notes. See docs/CODE_UPDATES.md.
 *
 * Write a citation into user-facing text with [cite]: `"… (${CodeRule.AMPACITY_TABLE.cite})"`.
 *
 * @property title what the provision covers, in the app's own words (never the code's text)
 * @property isTable true for a numbered table, false for a section
 */
enum class CodeRule(val title: String, val isTable: Boolean = false) {
    // General
    TERMINATION_TEMPERATURE("Temperature limits of terminations"),
    WORKING_SPACE("Working space depth in front of equipment", isTable = true),

    // Branch circuits and overcurrent protection
    BRANCH_CONDUCTOR_SIZE("Branch-circuit conductor minimum ampacity and size"),
    BRANCH_CONDUCTOR_CONTINUOUS("Branch-circuit conductors: 125% of the continuous load"),
    BRANCH_CONDUCTOR_ADJUSTED("Branch-circuit conductors: load after correction and adjustment"),
    CONDUCTOR_PROTECTION("Protection of conductors"),
    NEXT_SIZE_UP("Overcurrent devices rated 800 A or less: next higher standard rating"),
    SMALL_CONDUCTORS("Small conductors: maximum overcurrent protection"),
    SMALL_CONDUCTOR_18_CU("18 AWG copper conditions"),
    SMALL_CONDUCTOR_16_CU("16 AWG copper conditions"),
    SMALL_CONDUCTOR_14_CCA("14 AWG copper-clad aluminum conditions"),
    STANDARD_OCPD("Standard ampere ratings of fuses and inverse-time breakers"),

    // Grounding
    EGC_SIZE("Minimum size of equipment grounding conductors", isTable = true),
    EGC_INCREASED("Equipment grounding conductor increased with the ungrounded conductors"),
    EGC_MULTIPLE_CIRCUITS("One equipment grounding conductor for several circuits in a raceway"),

    // Conductors
    PARALLEL_CONDUCTORS("Conductors in parallel"),
    AMPACITY("Ampacities for conductors rated 0–2000 volts"),
    AMPACITY_TABLE("Ampacities of insulated conductors: up to three current-carrying, 30 °C (86 °F) ambient", isTable = true),
    AMBIENT_CORRECTION("Ambient temperature correction factors, 30 °C (86 °F) basis", isTable = true),
    ROOFTOP_ADDER("Raceways and cables exposed to sunlight on rooftops"),
    CONDUCTOR_COUNT_ADJUSTMENT("Adjustment factors for more than three current-carrying conductors", isTable = true),
    SHORT_RACEWAY_ADJUSTMENT("No count adjustment for raceways 24 in. (600 mm) or shorter"),
    NEUTRAL_COUNTING("When a neutral counts as a current-carrying conductor"),
    GROUNDING_COUNTING("Grounding and bonding conductors don't count"),

    // Motors and drives
    MOTOR_TABLE_FLC("Use the table full-load current, not the nameplate"),
    MOTOR_OVERLOAD("Motor overload protection, continuous-duty motors over 1 hp"),
    MOTOR_BRANCH_PROTECTION("Maximum rating of motor branch-circuit short-circuit and ground-fault devices", isTable = true),
    MOTOR_NEXT_HIGHER_RATING("Motor short-circuit device: next higher standard rating allowed"),
    MOTOR_OVERTEMPERATURE("Motor overtemperature protection"),
    MOTOR_FLC_SINGLE_PHASE("Full-load current, single-phase AC motors", isTable = true),
    MOTOR_FLC_THREE_PHASE("Full-load current, three-phase AC induction motors", isTable = true),
    DRIVE_INPUT_CONDUCTORS("Power conversion equipment: input conductors at 125% of rated input"),
    DRIVE_OUTPUT_CONDUCTORS("Power conversion equipment: output conductors"),
    DRIVE_OVERLOAD("Power conversion equipment: motor overload protection"),
    DRIVE_SHORT_CIRCUIT("Power conversion equipment: branch-circuit short-circuit protection"),

    // Raceways (Chapter 9 and annexes)
    FILL_PERCENT("Percent of cross section of conduit and tubing for conductors and cables", isTable = true),
    FILL_ROUNDING("Fill: rounding up when all conductors are the same size"),
    FILL_NIPPLES("Fill: nipples 24 in. (600 mm) or shorter"),
    FILL_GROUNDING_CONDUCTORS("Fill: equipment grounding conductors are counted"),
    JAM_RATIO("Fill: jamming of three conductors in a raceway"),
    BEND_RADIUS("Radius of conduit and tubing bends", isTable = true),
    CONDUIT_DIMENSIONS("Dimensions and percent area of conduit and tubing", isTable = true),
    CONDUCTOR_DIMENSIONS("Dimensions of insulated conductors and fixture wires", isTable = true),
    COMPACT_CONDUCTOR_DIMENSIONS("Compact conductor dimensions", isTable = true),
    CONDUCTOR_PROPERTIES("Conductor properties", isTable = true),
    AC_IMPEDANCE("AC resistance and reactance, 600 V cables, 3-phase, 60 Hz, 75 °C", isTable = true),
    CONDUCTORS_IN_CONDUIT("Maximum number of conductors in conduit or tubing (informative annex)", isTable = true),
    ;

    /** This rule's section or table number in the edition the app uses, e.g. "Table 310.16". */
    val cite: String get() = Code.book.cite(this)
}

/** One edition's numbering for every [CodeRule]. */
interface CodeBook {
    val edition: CodeEdition

    /** The section or table number, e.g. "310.15(B)(2)" or "Table 310.16". */
    fun cite(rule: CodeRule): String
}

/** The code book the app's tables and rules follow. Change it here when moving to a new edition. */
object Code {
    val book: CodeBook = Nec2023

    val edition: CodeEdition get() = book.edition

    /** "NEC 2023", for notes and reports. */
    val label: String get() = edition.label
}

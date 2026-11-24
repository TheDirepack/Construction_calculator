package com.thedirepack.constructioncalc.core.electrical.code

import com.thedirepack.constructioncalc.core.electrical.code.nec2023.Nec2023

/**
 * A published code edition that the app's tables and rules come from.
 *
 * [NEC_2023] is the active implemented base edition. [NEC_2026] is planned and pending
 * finalization and verification against the printed edition; it is not active until verified.
 */
enum class CodeEdition(
    val label: String,
    val year: Int,
    /** True only once all tables, citations, and single values are verified against the printed book. */
    val isImplemented: Boolean = true,
    val statusNote: String = "Active implemented base edition.",
) {
    NEC_2023(
        label = "NEC 2023",
        year = 2023,
        isImplemented = true,
        statusNote = "Active implemented base edition.",
    ),

    /** Planned edition; pending finalization and line-by-line verification before activation. */
    NEC_2026(
        label = "NEC 2026",
        year = 2026,
        isImplemented = false,
        statusNote = "Planned / pending finalization; not active until verified against the printed edition.",
    ),
    ;

    /** Alias for [isImplemented]: whether this edition can currently be used as an active book. */
    val isActive: Boolean get() = isImplemented

    companion object {
        /** Editions backed by a verified [ElectricalCode] implementation. */
        val activeEntries: List<CodeEdition> get() = entries.filter { it.isImplemented }
    }
}

/**
 * Every code provision the app relies on, by a stable id that stays the same from edition to
 * edition. The section or table number for an id lives in the [ElectricalCode] for the edition
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
    WORKING_SPACE_WIDTH("Working space width"),
    WORKING_SPACE_HEIGHT("Working space height"),
    GROUNDED_IDENTIFICATION("Identifying grounded (neutral) conductors"),
    UNGROUNDED_IDENTIFICATION("Identifying ungrounded branch-circuit conductors"),
    FEEDER_IDENTIFICATION("Identifying ungrounded feeder conductors"),

    // Branch circuits, feeders and overcurrent protection
    BRANCH_CONDUCTOR_SIZE("Branch-circuit conductor minimum ampacity and size"),
    BRANCH_CONDUCTOR_CONTINUOUS("Branch-circuit conductors: 125% of the continuous load"),
    BRANCH_CONDUCTOR_ADJUSTED("Branch-circuit conductors: load after correction and adjustment"),
    FEEDER_CONDUCTOR_CONTINUOUS("Feeder conductors: 125% of the continuous load"),
    VOLTAGE_DROP_BRANCH("Voltage drop recommendation: branch circuits, and feeders plus branch circuits"),
    VOLTAGE_DROP_FEEDER("Voltage drop recommendation: feeders"),
    CONDUCTOR_PROTECTION("Protection of conductors"),
    NEXT_SIZE_UP("Overcurrent devices rated 800 A or less: next higher standard rating"),
    SMALL_CONDUCTORS("Small conductors: maximum overcurrent protection"),
    SMALL_CONDUCTOR_18_CU("18 AWG copper conditions"),
    SMALL_CONDUCTOR_16_CU("16 AWG copper conditions"),
    SMALL_CONDUCTOR_14_CCA("14 AWG copper-clad aluminum conditions"),
    STANDARD_OCPD("Standard ampere ratings of fuses and inverse-time breakers"),

    // Feeder and service load calculations (Article 220)
    LIGHTING_DEMAND("Lighting load demand factors", isTable = true),
    RECEPTACLE_DEMAND("Demand factors for non-dwelling receptacle loads", isTable = true),
    SMALL_APPLIANCE_LAUNDRY("Small-appliance and laundry loads, dwelling unit"),
    DWELLING_APPLIANCE_DEMAND("Fastened-in-place appliance load, dwelling units"),
    DRYER_DEMAND("Demand factors for household electric clothes dryers", isTable = true),
    RANGE_DEMAND("Demand loads for household electric ranges and cooking appliances", isTable = true),
    FEEDER_MOTOR_LOAD("Motors on a feeder: 125% of the largest motor"),
    NONCOINCIDENT_LOADS("Noncoincident loads"),
    OPTIONAL_DWELLING("Optional feeder and service calculation, dwelling unit"),
    OPTIONAL_DWELLING_GENERAL("Optional dwelling calculation: general loads"),
    OPTIONAL_DWELLING_HVAC("Optional dwelling calculation: heating and air-conditioning load"),

    // Grounding
    EGC_SIZE("Minimum size of equipment grounding conductors", isTable = true),
    EGC_INCREASED("Equipment grounding conductor increased with the ungrounded conductors"),
    EGC_IDENTIFICATION("Identifying wire-type equipment grounding conductors"),
    EGC_MULTIPLE_CIRCUITS("One equipment grounding conductor for several circuits in a raceway"),
    GEC_SIZE("Grounding electrode conductor for alternating-current systems", isTable = true),
    GEC_GROUND_ROD_MAX("Grounding electrode conductor to rod, pipe, or plate electrodes"),
    GEC_CONCRETE_ENCASED_MAX("Grounding electrode conductor to concrete-encased electrodes"),
    SUPPLY_SIDE_BONDING("Supply-side and main bonding jumper sizing", isTable = true),

    // Boxes and enclosures
    BOX_FILL("Box fill calculations"),
    METAL_BOX_VOLUMES("Standard metal box volumes", isTable = true),
    BOX_VOLUME_ALLOWANCE("Volume allowance required per conductor", isTable = true),
    PULL_BOX_STRAIGHT("Pull and junction boxes: straight pulls"),
    PULL_BOX_ANGLE("Pull and junction boxes: angle or U pulls"),

    // Wiring methods and raceway installation
    BURIAL_DEPTH("Minimum cover requirements, 0 to 1000 volts ac, 1500 volts dc", isTable = true),
    RACEWAY_SUPPORT("Securing and supporting of raceways"),
    PVC_EXPANSION("Expansion characteristics of PVC rigid nonmetallic conduit", isTable = true),

    // Conductors
    MINIMUM_CONDUCTOR_SIZE("Minimum size of conductors"),
    CONDUCTOR_INSULATION("Conductor applications and insulation temperature ratings"),
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
    MOTOR_CONDUCTORS("Conductors for a single motor: 125% of full-load current"),
    MOTOR_DISCONNECT_RATING("Motor disconnecting means: at least 115% of full-load current"),
    MOTOR_OVERLOAD("Motor overload protection, continuous-duty motors over 1 hp"),
    MOTOR_BRANCH_PROTECTION("Maximum rating of motor branch-circuit short-circuit and ground-fault devices", isTable = true),
    MOTOR_NEXT_HIGHER_RATING("Motor short-circuit device: next higher standard rating allowed"),
    MOTOR_OVERTEMPERATURE("Motor overtemperature protection"),
    MOTOR_FLC_SINGLE_PHASE("Full-load current, single-phase AC motors", isTable = true),
    MOTOR_FLC_THREE_PHASE("Full-load current, three-phase AC induction motors", isTable = true),
    DRIVE_DISCONNECT_RATING("Power conversion equipment: disconnect at least 115% of rated input current"),
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

/**
 * The code edition and optional regional amendment profile the app's tables and rules follow.
 *
 * Every code value and citation is read from [book], which checks a thread-local [withProfile]
 * override first and otherwise falls back to [globalProfile]. By default [globalProfile] uses
 * [CodeEdition.NEC_2023] with [CodeRegions.STANDARD_ID], resolving directly to [Nec2023].
 */
object Code {
    private data class GlobalState(val profile: CodeProfile, val book: ElectricalCode)

    @Volatile
    private var globalState = GlobalState(CodeProfile(), Nec2023)

    @PublishedApi
    internal val threadProfile = ThreadLocal<CodeProfile?>()

    val globalProfile: CodeProfile get() = globalState.profile

    /** Effective profile on the current thread (either [withProfile] override or [globalProfile]). */
    val currentProfile: CodeProfile get() = threadProfile.get() ?: globalState.profile

    @Synchronized
    fun setGlobalProfile(profile: CodeProfile) {
        globalState = GlobalState(profile, resolve(profile))
    }

    /**
     * Resolves [profile] to an [ElectricalCode].
     *
     * Unimplemented editions (such as planned [CodeEdition.NEC_2026]) safely fall back to the
     * active verified base edition ([Nec2023]) so callers never read unverified numbers.
     */
    fun resolve(profile: CodeProfile): ElectricalCode {
        val base: ElectricalCode = when (profile.edition) {
            CodeEdition.NEC_2023 -> Nec2023
            CodeEdition.NEC_2026 -> Nec2023
        }
        val regional = CodeRegions.find(profile.regionId)?.amendment
        val custom = profile.customAmendment
        var resolved = base
        if (regional != null && !regional.isEmpty) {
            resolved = AmendedElectricalCode(resolved, regional)
        }
        if (custom != null && !custom.isEmpty) {
            resolved = AmendedElectricalCode(resolved, custom)
        }
        return resolved
    }

    /**
     * Temporarily overrides [Code.book] on the calling thread for the duration of [block],
     * restoring the prior thread-local state in `finally`.
     */
    inline fun <T> withProfile(profile: CodeProfile, block: () -> T): T {
        val previous = threadProfile.get()
        threadProfile.set(profile)
        return try {
            block()
        } finally {
            if (previous == null) threadProfile.remove() else threadProfile.set(previous)
        }
    }

    val book: ElectricalCode
        get() = threadProfile.get()?.let(::resolve) ?: globalState.book

    val edition: CodeEdition get() = book.edition

    /** "NEC 2023", for notes and reports. */
    val label: String get() = edition.label

    val region: CodeRegion get() = CodeRegions.getOrDefault(currentProfile.regionId)
}

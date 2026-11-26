package com.thedirepack.constructioncalc.core.electrical.code

import com.thedirepack.constructioncalc.core.electrical.code.nec2023.Nec2023

/**
 * A published NEC edition. Only editions with [isImplemented] have data in the app (an
 * [ElectricalCode] checked line by line against the printed book). An edition without data can
 * still be picked (a jurisdiction may have adopted it), but its calculations use [DATA_FALLBACK]
 * values and every result says so: see [CodeResolution.notices]. Never silently.
 */
enum class CodeEdition(
    val label: String,
    val year: Int,
    /** True only once all tables, citations, and single values are verified against the printed book. */
    val isImplemented: Boolean,
) {
    NEC_2020(label = "NEC 2020", year = 2020, isImplemented = false),
    NEC_2023(label = "NEC 2023", year = 2023, isImplemented = true),
    NEC_2026(label = "NEC 2026", year = 2026, isImplemented = false),
    ;

    /** Plain-language status for pickers. */
    val statusNote: String
        get() = if (isImplemented) {
            "In the app. Every value checked against the printed book."
        } else {
            "Not in the app yet. Calculations use ${DATA_FALLBACK.label} values and say so."
        }

    companion object {
        /** The edition whose values stand in for an edition without data. */
        val DATA_FALLBACK = NEC_2023

        /** The edition named [name] (`"NEC_2023"`), or null. */
        fun parse(name: String?): CodeEdition? = entries.firstOrNull { it.name == name }
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
 * The code edition and jurisdiction the app's tables and rules follow.
 *
 * Every code value and citation is read from [book], which checks a thread-local [withProfile]
 * override first and otherwise uses [globalProfile]. [resolve] builds the book: the selected
 * edition's data (or [CodeEdition.DATA_FALLBACK], flagged, when that edition isn't in the app),
 * then the jurisdiction's amendments to that edition, then a custom amendment. Each lookup answers
 * from the amendments first and falls through to the base edition.
 */
object Code {
    /** Edition data in the app. An edition missing here resolves to [CodeEdition.DATA_FALLBACK], flagged. */
    private val books: Map<CodeEdition, ElectricalCode> = mapOf(CodeEdition.NEC_2023 to Nec2023)

    @Volatile
    private var globalResolution = resolution(CodeProfile())

    @PublishedApi
    internal val threadProfile = ThreadLocal<CodeProfile?>()

    val globalProfile: CodeProfile get() = globalResolution.profile

    /** Effective profile on the current thread (either [withProfile] override or [globalProfile]). */
    val currentProfile: CodeProfile get() = threadProfile.get() ?: globalResolution.profile

    @Synchronized
    fun setGlobalProfile(profile: CodeProfile) {
        globalResolution = resolution(profile)
    }

    /** How [profile] resolves: data edition, jurisdiction, amendments, and the user-facing text. */
    fun resolution(profile: CodeProfile): CodeResolution {
        val base = books[profile.edition] ?: books.getValue(CodeEdition.DATA_FALLBACK)
        val jurisdiction = CodeRegions.getOrDefault(profile.regionId)
        val amendments = listOfNotNull(
            jurisdiction.amendmentFor(profile.edition),
            profile.customAmendment?.takeUnless { it.isEmpty },
        )
        val book = amendments.fold(base) { code, amendment -> AmendedElectricalCode(code, amendment) }
        return CodeResolution(profile, jurisdiction, base, amendments, book)
    }

    /** The [ElectricalCode] for [profile]: see [resolution]. */
    fun resolve(profile: CodeProfile): ElectricalCode = resolution(profile).book

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

    /** The effective resolution on this thread. */
    val current: CodeResolution
        get() = threadProfile.get()?.let(::resolution) ?: globalResolution

    val book: ElectricalCode get() = current.book

    /** The edition whose data the numbers come from. */
    val edition: CodeEdition get() = book.edition

    /** Short source for notes and reports: "NEC 2023", "NEC 2023 · MA amended"… See [CodeResolution.label]. */
    val label: String get() = current.label

    val region: CodeRegion get() = current.jurisdiction
}

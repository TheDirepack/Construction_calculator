package com.thedirepack.constructioncalc.core.electrical.code

/**
 * A code jurisdiction (a state, a city, or the plain national code): the NEC edition it adopted
 * ([baseEdition]) plus its local amendments to that edition ([amendments]).
 *
 * Everything a jurisdiction doesn't amend comes from its base edition: an amendment lists only the
 * rules, values and citations that differ. Amendments are keyed by the edition they amend, because
 * a jurisdiction re-issues its amendments when it adopts a new edition.
 *
 * Amendment contents are entered only from the jurisdiction's published text, never guessed. A
 * jurisdiction with no entered amendments resolves to the unamended base edition and says so
 * ("NEC 2023, no local amendments entered").
 */
data class CodeRegion(
    val id: String,
    val name: String,
    val shortLabel: String,
    val description: String = "",
    /** The NEC edition this jurisdiction adopted; picking the jurisdiction selects this edition. */
    val baseEdition: CodeEdition = CodeEdition.NEC_2023,
    /** Local amendments, by the edition they amend. Empty until verified against the published text. */
    val amendments: Map<CodeEdition, RegionalAmendment> = emptyMap(),
) {
    /** The entered amendments to [edition], or null when none are entered. */
    fun amendmentFor(edition: CodeEdition): RegionalAmendment? = amendments[edition]?.takeUnless { it.isEmpty }

    /** True when this jurisdiction has any entered amendments (for any edition). */
    val hasOverrides: Boolean get() = amendments.values.any { !it.isEmpty }

    val isNational: Boolean get() = id == CodeRegions.STANDARD_ID
}

/**
 * A jurisdiction's (or a project's custom) changes to a base edition. Only what differs is listed;
 * every other lookup falls through to the base edition.
 *
 * - [citationOverrides]: the amended section number or local rule reference for a [CodeRule].
 * - [valuesOverride] / [valuesTransform]: changed single values. Name each changed rule in
 *   [valueRules] so results can say an amended value was used.
 * - [loadCalculationTransform]: changed Article 220 values (name the rules in [valueRules] too).
 */
data class RegionalAmendment(
    val regionId: String = CodeRegions.CUSTOM_ID,
    /** Shown where an amended value is used, e.g. "Massachusetts amendment" or "527 CMR 12.00". */
    val label: String = "Custom",
    val citationOverrides: Map<CodeRule, String> = emptyMap(),
    val valuesOverride: CodeValues? = null,
    val valuesTransform: ((CodeValues) -> CodeValues)? = null,
    val loadCalculationTransform: ((LoadCalculationCode) -> LoadCalculationCode)? = null,
    /** Rules whose values (not only citations) this amendment changes. */
    val valueRules: Set<CodeRule> = emptySet(),
    val notes: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = citationOverrides.isEmpty() &&
            valuesOverride == null &&
            valuesTransform == null &&
            loadCalculationTransform == null &&
            valueRules.isEmpty() &&
            notes.isEmpty()

    /** Every rule this amendment changes, by citation or by value. */
    val amendedRules: Set<CodeRule> get() = citationOverrides.keys + valueRules
}

/**
 * What the user picked: an [edition] and a jurisdiction ([regionId]), plus an optional
 * project-supplied [customAmendment] applied after the jurisdiction's own amendments.
 */
data class CodeProfile(
    val edition: CodeEdition = CodeEdition.NEC_2023,
    val regionId: String = CodeRegions.STANDARD_ID,
    val customAmendment: RegionalAmendment? = null,
)

/**
 * An [ElectricalCode] that answers from [amendment] first and falls through to [base] for
 * everything the amendment doesn't change (all tables, unamended citations and values).
 */
open class AmendedElectricalCode(
    val base: ElectricalCode,
    val amendment: RegionalAmendment,
) : ElectricalCode by base {

    override fun cite(rule: CodeRule): String =
        amendment.citationOverrides[rule] ?: base.cite(rule)

    override val values: CodeValues = run {
        val replaced = amendment.valuesOverride ?: base.values
        amendment.valuesTransform?.invoke(replaced) ?: replaced
    }

    override val loadCalculation: LoadCalculationCode =
        amendment.loadCalculationTransform?.invoke(base.loadCalculation) ?: base.loadCalculation
}

/**
 * The jurisdictions the app offers. Each names its adopted base edition; no amendments are entered
 * yet, so each resolves to its unamended base edition and says so. Adopted editions should be
 * confirmed with the local inspector (AHJ); amendment contents are added only from published text.
 */
object CodeRegions {
    const val STANDARD_ID = "standard"
    const val CALIFORNIA_ID = "us-ca"
    const val MASSACHUSETTS_ID = "us-ma"
    const val WASHINGTON_ID = "us-wa"
    const val NEW_YORK_ID = "us-ny"

    /** Not a jurisdiction: the slot for a project-supplied [CodeProfile.customAmendment]. Not offered in pickers. */
    const val CUSTOM_ID = "custom"

    val STANDARD = CodeRegion(
        id = STANDARD_ID,
        name = "National (no local amendments)",
        shortLabel = "NEC",
        description = "The NFPA 70 National Electrical Code as published.",
    )

    val CALIFORNIA = CodeRegion(
        id = CALIFORNIA_ID,
        name = "California (CEC)",
        shortLabel = "CA",
        description = "California Electrical Code, Title 24 Part 3.",
    )

    val MASSACHUSETTS = CodeRegion(
        id = MASSACHUSETTS_ID,
        name = "Massachusetts (527 CMR 12.00)",
        shortLabel = "MA",
        description = "Massachusetts Electrical Code, 527 CMR 12.00.",
    )

    val WASHINGTON = CodeRegion(
        id = WASHINGTON_ID,
        name = "Washington (WAC 296-46B)",
        shortLabel = "WA",
        description = "Washington State electrical rules, WAC 296-46B.",
    )

    val NEW_YORK = CodeRegion(
        id = NEW_YORK_ID,
        name = "New York State",
        shortLabel = "NY",
        description = "New York State Uniform Code. New York City has its own electrical code, not covered here.",
    )

    val CUSTOM = CodeRegion(
        id = CUSTOM_ID,
        name = "Custom",
        shortLabel = "Custom",
        description = "A project-supplied set of amendments.",
    )

    /** The jurisdictions a user can pick, national first. */
    val entries: List<CodeRegion> = listOf(STANDARD, CALIFORNIA, MASSACHUSETTS, WASHINGTON, NEW_YORK)

    private val byId: Map<String, CodeRegion> = (entries + CUSTOM).associateBy { it.id }

    fun find(regionId: String): CodeRegion? = byId[regionId]

    fun getOrDefault(regionId: String): CodeRegion = byId[regionId] ?: STANDARD
}

/**
 * How a [CodeProfile] resolved: which edition's data the numbers come from, which jurisdiction and
 * amendments apply, and plain-language text saying so. Every lookup in [book] answers from the
 * amendments first, then the base edition.
 */
class CodeResolution internal constructor(
    val profile: CodeProfile,
    val jurisdiction: CodeRegion,
    /** The edition book the unamended values come from. */
    val base: ElectricalCode,
    /** Amendments applied over [base], in order (jurisdiction first, then a custom one). */
    val amendments: List<RegionalAmendment>,
    val book: ElectricalCode,
) {
    /** The edition the user picked. */
    val selectedEdition: CodeEdition get() = profile.edition

    /** The edition whose data is used; differs from [selectedEdition] when that one isn't in the app. */
    val dataEdition: CodeEdition get() = base.edition

    /** True when the selected edition has no data in the app and another edition's values stand in. */
    val usesFallbackEdition: Boolean get() = selectedEdition != dataEdition

    val isAmended: Boolean get() = amendments.isNotEmpty()

    /** True when [rule] comes from an amendment rather than the base edition. */
    fun isAmended(rule: CodeRule): Boolean = amendments.any { rule in it.amendedRules }

    /** Where [rule] comes from: "Massachusetts amendment" or "NEC 2023". */
    fun sourceOf(rule: CodeRule): String =
        amendments.lastOrNull { rule in it.amendedRules }?.label ?: dataEdition.label

    /** [rule]'s citation, marked when an amendment supplies it: "Table 310.16" or "310.3(A) (MA amendment)". */
    fun citeWithSource(rule: CodeRule): String {
        val cite = book.cite(rule)
        val amendment = amendments.lastOrNull { rule in it.amendedRules } ?: return cite
        return "$cite (${amendment.label})"
    }

    /**
     * Short source for notes and reports: "NEC 2023", "NEC 2023 · CA", "NEC 2023 · MA amended",
     * or "NEC 2023 values (NEC 2026 not in app)".
     */
    val label: String
        get() = buildString {
            append(dataEdition.label)
            if (usesFallbackEdition) append(" values (${selectedEdition.label} not in app)")
            if (!jurisdiction.isNational && jurisdiction.id != CodeRegions.CUSTOM_ID) append(" · ${jurisdiction.shortLabel}")
            if (isAmended) append(" amended")
        }

    /** One line for banners and Settings: "California · NEC 2023, no local amendments entered". */
    val summary: String
        get() {
            val edition = selectedEdition.label
            val where = if (jurisdiction.isNational || jurisdiction.id == CodeRegions.CUSTOM_ID) null else jurisdiction.name
            val amendmentText = when {
                isAmended -> "with ${amendments.joinToString(" and ") { it.label }} (${amendments.flatMap { it.amendedRules }.toSet().size} rules)"
                jurisdiction.isNational -> "as published, no local amendments"
                else -> "no local amendments entered"
            }
            return listOfNotNull(where, "$edition, $amendmentText").joinToString(" · ")
        }

    /** Warnings the user must see with results: a missing edition, amendments for another edition. */
    val notices: List<String>
        get() = buildList {
            if (usesFallbackEdition) {
                add("${selectedEdition.label} isn't in the app yet. Calculations use ${dataEdition.label} values; check them against your code book.")
            }
            if (jurisdiction.amendmentFor(selectedEdition) == null) {
                val other = jurisdiction.amendments.filterValues { !it.isEmpty }.keys
                if (other.isNotEmpty()) {
                    add("${jurisdiction.name} amendments are entered for ${other.joinToString { it.label }} only, not ${selectedEdition.label}.")
                }
            }
            if (selectedEdition != jurisdiction.baseEdition && !jurisdiction.isNational && jurisdiction.id != CodeRegions.CUSTOM_ID) {
                add("${jurisdiction.name} adopted ${jurisdiction.baseEdition.label}; you picked ${selectedEdition.label}.")
            }
        }
}

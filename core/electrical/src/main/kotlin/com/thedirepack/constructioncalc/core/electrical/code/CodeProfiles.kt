package com.thedirepack.constructioncalc.core.electrical.code

import com.thedirepack.constructioncalc.core.electrical.code.nec2023.Nec2023

/**
 * Jurisdiction/regional amendment slot on top of a base [CodeEdition].
 *
 * [amendment] is `null` when the region adopts the unamended base NEC or awaits verified
 * jurisdiction-specific overrides (never guess or fabricate state table/rule changes).
 */
data class CodeRegion(
    val id: String,
    val name: String,
    val shortLabel: String,
    val description: String = "",
    val baseEdition: CodeEdition = CodeEdition.NEC_2023,
    val amendment: RegionalAmendment? = null,
) {
    /** True when this region carries verified code/citation overrides over [baseEdition]. */
    val hasOverrides: Boolean get() = amendment != null && !amendment.isEmpty
}

/**
 * Regional or state amendment overlay applied on top of a base [ElectricalCode].
 *
 * Allows overriding individual rule citations ([citationOverrides]), scalar code values
 * ([valuesOverride] / [valuesTransform]), and Article 220 load calculation rules
 * ([loadCalculationTransform]).
 */
data class RegionalAmendment(
    val regionId: String = CodeRegions.CUSTOM_ID,
    val label: String = "Custom",
    val citationOverrides: Map<CodeRule, String> = emptyMap(),
    val valuesOverride: CodeValues? = null,
    val valuesTransform: ((CodeValues) -> CodeValues)? = null,
    val loadCalculationTransform: ((LoadCalculationCode) -> LoadCalculationCode)? = null,
    val notes: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = citationOverrides.isEmpty() &&
            valuesOverride == null &&
            valuesTransform == null &&
            loadCalculationTransform == null &&
            notes.isEmpty()
}

/**
 * Identifies a code configuration combining a base [edition] with a [regionId] (and optional
 * [customAmendment] overlay).
 */
data class CodeProfile(
    val edition: CodeEdition = CodeEdition.NEC_2023,
    val regionId: String = CodeRegions.STANDARD_ID,
    val customAmendment: RegionalAmendment? = null,
)

/**
 * An [ElectricalCode] wrapping [base] with a [RegionalAmendment] for jurisdiction-specific
 * citations and values. All non-overridden tables and rules delegate directly to [base].
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
 * Registry of standard and structured regional code profiles.
 *
 * Regional state slots default to unamended [Nec2023] until specific state amendments are
 * individually verified against published state codes.
 */
object CodeRegions {
    const val STANDARD_ID = "standard"
    const val CALIFORNIA_ID = "us-ca"
    const val MASSACHUSETTS_ID = "us-ma"
    const val WASHINGTON_ID = "us-wa"
    const val NEW_YORK_ID = "us-ny"
    const val CUSTOM_ID = "custom"

    val STANDARD = CodeRegion(
        id = STANDARD_ID,
        name = "Standard NEC",
        shortLabel = "NEC",
        description = "Unamended NFPA 70 National Electrical Code.",
    )

    val CALIFORNIA = CodeRegion(
        id = CALIFORNIA_ID,
        name = "California (CEC)",
        shortLabel = "CA CEC",
        description = "California Electrical Code (Title 24, Part 3) regional slot.",
    )

    val MASSACHUSETTS = CodeRegion(
        id = MASSACHUSETTS_ID,
        name = "Massachusetts (MEC)",
        shortLabel = "MA MEC",
        description = "Massachusetts Electrical Code (527 CMR 12.00) regional slot.",
    )

    val WASHINGTON = CodeRegion(
        id = WASHINGTON_ID,
        name = "Washington (WAC)",
        shortLabel = "WA NEC",
        description = "Washington State (WAC 296-46B) regional slot.",
    )

    val NEW_YORK = CodeRegion(
        id = NEW_YORK_ID,
        name = "New York",
        shortLabel = "NY",
        description = "New York State / NYC regional slot.",
    )

    val CUSTOM = CodeRegion(
        id = CUSTOM_ID,
        name = "Custom Profile",
        shortLabel = "Custom",
        description = "User- or project-supplied custom regional amendment slot.",
    )

    val entries: List<CodeRegion> = listOf(
        STANDARD,
        CALIFORNIA,
        MASSACHUSETTS,
        WASHINGTON,
        NEW_YORK,
        CUSTOM,
    )

    private val byId: Map<String, CodeRegion> = entries.associateBy { it.id }

    fun find(regionId: String): CodeRegion? = byId[regionId]

    fun getOrDefault(regionId: String): CodeRegion = byId[regionId] ?: STANDARD
}

package com.thedirepack.constructioncalc.feature.conduitfill

import com.thedirepack.constructioncalc.core.electrical.BareConductorArea
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.ConduitTable
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.InsulatedConductorArea
import com.thedirepack.constructioncalc.core.electrical.InsulationType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.feature.conduitfill.domain.ConductorGroup
import com.thedirepack.constructioncalc.feature.conduitfill.domain.ConductorSpec
import com.thedirepack.constructioncalc.feature.conduitfill.domain.FillRequest
import kotlinx.serialization.Serializable

/** Insulation code for a bare conductor (Table 8); every other code is an [InsulationType.code]. */
const val BARE_CODE = "BARE"

/** One row of conductors as typed: [count] of the same wire. Stored as text so a half-typed field survives. */
@Serializable
data class ConductorGroupInput(
    val count: String = "1",
    /** An [InsulationType.code], or [BARE_CODE]. */
    val insulation: String = InsulationType.THHN.code,
    /** A [ConductorSize.label], e.g. "12 AWG". */
    val size: String = ConductorSize.AWG_12.label,
)

/** What a saved Conduit Fill item stores. Every field has a default so a partly filled item still decodes. */
@Serializable
data class ConduitFillInputs(
    /** A [ConduitType.code]. */
    val conduitType: String = ConduitType.EMT.code,
    /** A [TradeSize.label], e.g. "1-1/4". */
    val tradeSize: String = TradeSize.S_3_4.label,
    /** Conduit or tubing nipple 24 in. or shorter (60% fill). */
    val nipple: Boolean = false,
    val groups: List<ConductorGroupInput> = listOf(
        ConductorGroupInput(count = "3"),
        ConductorGroupInput(count = "1", insulation = BARE_CODE),
    ),
    /** The wire used for the "how many fit" count. */
    val fitInsulation: String = InsulationType.THHN.code,
    val fitSize: String = ConductorSize.AWG_12.label,
)

/** [inputs] turned into a calculable request, or the reasons it can't be. */
data class ParsedFill(val request: FillRequest?, val problems: List<String>)

/** The wire for an insulation [code] and size [sizeLabel], or null when the NEC tables have no such row. */
fun conductorSpec(code: String, sizeLabel: String): ConductorSpec? {
    val size = ConductorSize.fromLabel(sizeLabel) ?: return null
    val spec = if (code == BARE_CODE) {
        ConductorSpec.Bare(size)
    } else {
        ConductorSpec.Insulated(InsulationType.fromCode(code) ?: return null, size)
    }
    return spec.takeIf { it.dimensions != null }
}

/** Sizes the tables list for an insulation [code] (all sizes for a bare conductor). */
fun sizesFor(code: String): List<ConductorSize> =
    if (code == BARE_CODE) BareConductorArea.sizes else InsulationType.fromCode(code)?.let(InsulatedConductorArea::sizes).orEmpty()

/** [size] if the tables list it for [code], otherwise the closest size they do list. */
fun sizeFor(code: String, size: ConductorSize): ConductorSize {
    if (code == BARE_CODE) return size
    val type = InsulationType.fromCode(code) ?: return size
    return InsulatedConductorArea.nearestSize(type, size)
}

fun ConduitFillInputs.toRequest(): ParsedFill {
    val problems = mutableListOf<String>()

    val type = ConduitType.fromCode(conduitType)
    val trade = TradeSize.fromLabel(tradeSize)
    val conduit = if (type == null) {
        problems += "Unknown conduit type \"$conduitType\"."
        null
    } else {
        trade?.let { ConduitTable.find(type, it) }.also {
            if (it == null) problems += "${type.displayName} has no ${tradeSize}\" trade size in NEC Table 4."
        }
    }

    val groups = mutableListOf<ConductorGroup>()
    this.groups.forEachIndexed { index, g ->
        val text = g.count.trim()
        if (text.isEmpty()) return@forEachIndexed
        val count = text.toIntOrNull()
        if (count == null || count < 0) {
            problems += "Group ${index + 1}: \"${g.count}\" isn't a whole number."
            return@forEachIndexed
        }
        if (count == 0) return@forEachIndexed
        val spec = conductorSpec(g.insulation, g.size)
        if (spec == null) {
            problems += "Group ${index + 1}: ${g.size} isn't listed for ${InsulationType.fromCode(g.insulation)?.label ?: "that conductor"} in the NEC tables."
        } else {
            groups += ConductorGroup(count, spec)
        }
    }

    if (problems.isNotEmpty() || conduit == null) return ParsedFill(null, problems)
    return ParsedFill(FillRequest(conduit, nipple, groups, conductorSpec(fitInsulation, fitSize)), emptyList())
}

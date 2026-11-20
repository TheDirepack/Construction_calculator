package com.thedirepack.constructioncalc.feature.conversions

import com.thedirepack.constructioncalc.core.electrical.AcPhases
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.model.StoredQuantity
import com.thedirepack.constructioncalc.feature.conversions.domain.ConversionCategory
import com.thedirepack.constructioncalc.feature.conversions.domain.PowerTerm
import com.thedirepack.constructioncalc.feature.conversions.domain.UnitTable
import kotlinx.serialization.Serializable

/** What is typed into the wire-size converter. */
@Serializable
enum class WireFrom(val label: String) {
    SIZE("AWG / kcmil"),
    CMIL("Circular mils"),
    MM2("mm²"),
}

/** What a saved Unit Conversions item stores in `ProjectItem.inputs`. */
@Serializable
data class ConversionsInputs(
    val category: ConversionCategory = ConversionCategory.LENGTH,
    /** Id of the unit being entered ([UnitTable.CALCULATOR_ID] for Length's feet-inch calculator). */
    val fromUnit: String = UnitTable.defaultUnitId(ConversionCategory.LENGTH),
    /** The number typed for table categories, wire area, or the current/power amount. */
    val value: String = "",
    /** Length entered through the feet-inch calculator. */
    val length: StoredQuantity? = null,
    val wireFrom: WireFrom = WireFrom.SIZE,
    /** A [com.thedirepack.constructioncalc.core.electrical.ConductorSize] label. */
    val wireSize: String = "12 AWG",
    val phase: AcPhases = AcPhases.SINGLE,
    val powerFrom: PowerTerm = PowerTerm.AMPS,
    val voltage: String = "240",
    val powerFactor: String = "1",
) {
    /** True when Length is entered with the feet-inch calculator rather than a number. */
    val usesCalculator: Boolean
        get() = category == ConversionCategory.LENGTH && fromUnit == UnitTable.CALCULATOR_ID
}

/** Exact value of what a user typed; accepts `,` or `.` and `−`. Null if blank or not a number. */
internal fun parseRational(text: String): Rational? {
    val clean = text.trim().replace(',', '.').replace('−', '-')
    if (clean.isEmpty() || '/' in clean) return null
    return runCatching { Rational.parse(clean) }.getOrNull()
}

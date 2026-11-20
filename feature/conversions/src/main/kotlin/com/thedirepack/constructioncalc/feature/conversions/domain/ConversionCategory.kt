package com.thedirepack.constructioncalc.feature.conversions.domain

import com.thedirepack.constructioncalc.core.model.ValueKind
import kotlinx.serialization.Serializable

/**
 * What the Unit Conversions tool can convert. Saved in project files by name, so never rename one.
 *
 * @property kind how converted values are published. For a kind with a base unit
 *   ([ValueKind.baseUnit]) the published `exact` value is in that base unit; [ValueKind.NUMBER]
 *   rows publish the number in their own unit (the unit is in the row's text).
 */
@Serializable
enum class ConversionCategory(val label: String, val kind: ValueKind) {
    LENGTH("Length", ValueKind.LENGTH),
    AREA("Area", ValueKind.AREA),
    VOLUME("Volume", ValueKind.VOLUME),
    WEIGHT("Weight", ValueKind.WEIGHT),
    TEMPERATURE("Temperature", ValueKind.TEMPERATURE),
    PRESSURE("Pressure", ValueKind.PRESSURE),
    POWER("Power", ValueKind.POWER),
    ENERGY("Energy", ValueKind.NUMBER),
    SPEED("Speed", ValueKind.NUMBER),
    FLOW("Flow", ValueKind.FLOW),
    ANGLE("Angle", ValueKind.ANGLE),
    WIRE_SIZE("Wire size (AWG, cmil, mm²)", ValueKind.TEXT),
    CURRENT_POWER("Current ↔ power (A, kW, kVA)", ValueKind.CURRENT),
    RESISTANCE_LENGTH("Resistance per length", ValueKind.NUMBER);

    /** True for categories driven by the [UnitTable] (everything except the two electrical calculators). */
    val isTable: Boolean get() = this != WIRE_SIZE && this != CURRENT_POWER
}

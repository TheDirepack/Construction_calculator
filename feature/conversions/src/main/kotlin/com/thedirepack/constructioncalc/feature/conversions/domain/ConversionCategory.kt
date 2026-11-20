package com.thedirepack.constructioncalc.feature.conversions.domain

import com.thedirepack.constructioncalc.core.model.ValueKind

/**
 * The conversion categories supported by the Unit Conversions tool.
 */
enum class ConversionCategory(
    val label: String,
    val valueKind: ValueKind,
) {
    LENGTH("Length", ValueKind.LENGTH),
    AREA("Area", ValueKind.AREA),
    VOLUME("Volume", ValueKind.VOLUME),
    WEIGHT("Weight / Mass", ValueKind.WEIGHT),
    TEMPERATURE("Temperature", ValueKind.TEMPERATURE),
    PRESSURE("Pressure", ValueKind.PRESSURE),
    POWER("Power", ValueKind.POWER),
    ENERGY("Energy", ValueKind.NUMBER),
    SPEED("Speed", ValueKind.NUMBER),
    FLOW("Flow", ValueKind.FLOW),
    ANGLE("Angle", ValueKind.ANGLE),
    WIRE_SIZE("Wire size (AWG / cmil / mm²)", ValueKind.TEXT),
    CURRENT_POWER("Current ↔ Power", ValueKind.CURRENT),
    RESISTANCE_LENGTH("Resistance per length", ValueKind.NUMBER);

    val isElectrical: Boolean
        get() = this == WIRE_SIZE || this == CURRENT_POWER || this == RESISTANCE_LENGTH
}

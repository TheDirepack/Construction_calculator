package com.thedirepack.constructioncalc.core.measure

/**
 * One unit of a [Measure]: `shown = base × factor + offset`. Only temperature has an offset.
 */
data class MeasureUnit(val symbol: String, val factor: Double, val offset: Double = 0.0, val decimals: Int = 2) {
    fun fromBase(base: Double): Double = base * factor + offset
    fun toBase(shown: Double): Double = (shown - offset) / factor
}

/**
 * Quantities other than calculator lengths that tools show or ask for. Each keeps one **base**
 * unit, the one engines and saved files use (it never changes with the setting), and has an
 * imperial and a metric unit for display and typing. Factors are the exact definitions
 * (1 ft = 0.3048 m, 1 lb = 0.45359237 kg, 1 lbf = 4.4482216152605 N, 1 gal = 3.785411784 L).
 */
enum class Measure(val label: String, val base: MeasureUnit, val imperial: MeasureUnit, val metric: MeasureUnit) {
    /** Base °C, as the NEC ampacity tables are. */
    TEMPERATURE(
        "Temperature",
        MeasureUnit("°C", 1.0, decimals = 1),
        MeasureUnit("°F", 9.0 / 5.0, 32.0, decimals = 0),
        MeasureUnit("°C", 1.0, decimals = 1),
    ),

    /** Base feet: run, pull and reel lengths. */
    RUN_LENGTH("Length", MeasureUnit("ft", 1.0, decimals = 1), MeasureUnit("ft", 1.0, decimals = 1), MeasureUnit("m", 0.3048, decimals = 2)),

    /** Base inches: diameters, radii, reel dimensions. */
    SMALL_LENGTH("Length", MeasureUnit("in", 1.0, decimals = 3), MeasureUnit("in", 1.0, decimals = 3), MeasureUnit("mm", 25.4, decimals = 1)),

    /** Base in²: conduit and conductor cross-sections. */
    SMALL_AREA("Area", MeasureUnit("in²", 1.0, decimals = 4), MeasureUnit("in²", 1.0, decimals = 4), MeasureUnit("mm²", 645.16, decimals = 1)),

    /** Base lb. */
    WEIGHT("Weight", MeasureUnit("lb", 1.0, decimals = 1), MeasureUnit("lb", 1.0, decimals = 1), MeasureUnit("kg", 0.45359237, decimals = 1)),

    /** Base lb/ft: cable weight. */
    LINEAR_WEIGHT("Weight per length", MeasureUnit("lb/ft", 1.0, decimals = 3), MeasureUnit("lb/ft", 1.0, decimals = 3), MeasureUnit("kg/m", 0.45359237 / 0.3048, decimals = 3)),

    /** Base lbf: pulling tension. */
    FORCE("Tension", MeasureUnit("lb", 1.0, decimals = 0), MeasureUnit("lb", 1.0, decimals = 0), MeasureUnit("N", 4.4482216152605, decimals = 0)),

    /** Base lbf/ft: sidewall bearing pressure. */
    SIDEWALL_PRESSURE("Sidewall pressure", MeasureUnit("lb/ft", 1.0, decimals = 0), MeasureUnit("lb/ft", 1.0, decimals = 0), MeasureUnit("kN/m", 4.4482216152605 / 0.3048 / 1000, decimals = 2)),

    /** Base psi. */
    PRESSURE("Pressure", MeasureUnit("psi", 1.0, decimals = 1), MeasureUnit("psi", 1.0, decimals = 1), MeasureUnit("kPa", 6.894757293168361, decimals = 0)),

    /** Base US gallons per minute. */
    FLOW("Flow", MeasureUnit("gpm", 1.0, decimals = 1), MeasureUnit("gpm", 1.0, decimals = 1), MeasureUnit("L/min", 3.785411784, decimals = 1)),

    /** Base US gallons. */
    LIQUID_VOLUME("Volume", MeasureUnit("gal", 1.0, decimals = 1), MeasureUnit("gal", 1.0, decimals = 1), MeasureUnit("L", 3.785411784, decimals = 1));

    fun unit(system: UnitSystem): MeasureUnit = if (system == UnitSystem.METRIC) metric else imperial

    /** A base value in [system]'s unit. */
    fun fromBase(base: Double, system: UnitSystem): Double = unit(system).fromBase(base)

    /** A value typed in [system]'s unit, back to the base unit. */
    fun toBase(shown: Double, system: UnitSystem): Double = unit(system).toBase(shown)

    /** `86 °F`, `30 °C`, `152.4 m`: the base value in [system], at the unit's usual precision or [decimals]. */
    fun format(base: Double, system: UnitSystem, decimals: Int? = null): String {
        val u = unit(system)
        val shown = Decimals.trim(u.fromBase(base), decimals ?: u.decimals).replace('-', '−')
        return "$shown ${u.symbol}"
    }

    /** [format] in [system], with the other system in parentheses: `86 °F (30 °C)`. */
    fun formatBoth(base: Double, system: UnitSystem, decimals: Int? = null): String {
        val other = if (system == UnitSystem.METRIC) UnitSystem.IMPERIAL else UnitSystem.METRIC
        val main = format(base, system, decimals)
        return if (unit(system).symbol == unit(other).symbol) main else "$main (${format(base, other)})"
    }
}

package com.thedirepack.constructioncalc.feature.conduitbending.domain

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.electrical.ConduitType as RacewayType

/** Conduit types the bender table covers. */
enum class ConduitType(val raceway: RacewayType, val sizes: List<String>) {
    EMT(RacewayType.EMT, listOf("1/2", "3/4", "1", "1-1/4")),
    IMC(RacewayType.IMC, listOf("1/2", "3/4", "1")),
    RIGID(RacewayType.RMC, listOf("1/2", "3/4", "1"));

    val label get() = raceway.displayName

    companion object {
        fun fromName(name: String) = entries.firstOrNull { it.name == name } ?: EMT
    }
}

/** Trade-size label for a size such as "1-1/4" → `1-1/4"`. */
fun sizeLabel(size: String) = "$size\""

/**
 * What a hand bender does to one size of conduit, in inches.
 *
 * @property takeUp the "deduct": how far from the end of a 90° stub's height the bender's arrow
 *   mark goes, so the stub measures [height] to the back of the bend.
 * @property gain how much shorter the conduit is around a 90° than the two straight legs measured
 *   to the back of the bend (used for back-to-back cut lengths).
 */
data class BenderSpec(val takeUp: Rational, val gain: Rational)

/** Bender values for every type/size, editable by the user. */
class BenderTable(private val specs: Map<String, BenderSpec>) {
    fun spec(type: ConduitType, size: String): BenderSpec? = specs[key(type, size)] ?: BenderDefaults.spec(type, size)

    companion object {
        fun key(type: ConduitType, size: String) = "${type.name}:$size"
        val DEFAULT = BenderTable(emptyMap())
    }
}

/**
 * Typical hand-bender values, a starting point only. Take-ups are the commonly published
 * hand-bender "deduct" figures for EMT (5, 6, 8 and 11 inches for 1/2 to 1-1/4) and for
 * IMC/rigid (6, 8 and 9 inches for 1/2 to 1). Gain is computed from the take-up and the
 * conduit's outside diameter (see [gainFor]) and rounded to 1/8". Benders differ by maker and
 * wear: the user should check these against their own bender and edit them in Settings.
 */
object BenderDefaults {
    private data class Row(val type: ConduitType, val size: String, val takeUp: Rational, val od: Double)

    private val rows = listOf(
        Row(ConduitType.EMT, "1/2", Rational.of(5), 0.706),
        Row(ConduitType.EMT, "3/4", Rational.of(6), 0.922),
        Row(ConduitType.EMT, "1", Rational.of(8), 1.163),
        Row(ConduitType.EMT, "1-1/4", Rational.of(11), 1.510),
        Row(ConduitType.IMC, "1/2", Rational.of(6), 0.815),
        Row(ConduitType.IMC, "3/4", Rational.of(8), 1.029),
        Row(ConduitType.IMC, "1", Rational.of(9), 1.290),
        Row(ConduitType.RIGID, "1/2", Rational.of(6), 0.840),
        Row(ConduitType.RIGID, "3/4", Rational.of(8), 1.050),
        Row(ConduitType.RIGID, "1", Rational.of(9), 1.315),
    )

    /**
     * Gain of a 90° from its take-up T and outside diameter OD. The back of the bend sits T from
     * the arrow, so the centerline radius is T − OD/2 and the arc is (π/2)·(T − OD/2); the two
     * straight legs measured to the back total 2T. Gain = 2T − (π/2)(T − OD/2), to the nearest 1/8".
     */
    fun gainFor(takeUp: Rational, od: Double): Rational {
        val t = takeUp.toDouble()
        val gain = 2 * t - (Math.PI / 2) * (t - od / 2)
        return Rational.of(Math.round(gain * 8), 8)
    }

    fun spec(type: ConduitType, size: String): BenderSpec? =
        rows.firstOrNull { it.type == type && it.size == size }?.let { BenderSpec(it.takeUp, gainFor(it.takeUp, it.od)) }
}

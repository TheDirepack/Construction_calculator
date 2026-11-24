package com.thedirepack.constructioncalc.feature.conduitbending.domain

import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.math.Rational

/**
 * The conduit a hand bender handles: the app's own conduit types (the same ones a project run
 * uses) and the trade sizes that have hand-bender values.
 */
object Bendable {
    private val bySize: Map<ConduitType, List<TradeSize>> = linkedMapOf(
        ConduitType.EMT to listOf(TradeSize.S_1_2, TradeSize.S_3_4, TradeSize.S_1, TradeSize.S_1_1_4),
        ConduitType.IMC to listOf(TradeSize.S_1_2, TradeSize.S_3_4, TradeSize.S_1),
        ConduitType.RMC to listOf(TradeSize.S_1_2, TradeSize.S_3_4, TradeSize.S_1),
    )

    val types: List<ConduitType> get() = bySize.keys.toList()

    fun sizes(type: ConduitType): List<TradeSize> = bySize[type].orEmpty()

    /** [type] if a hand bender handles it, else EMT. */
    fun typeOrDefault(type: ConduitType?): ConduitType = type?.takeIf { it in bySize } ?: ConduitType.EMT

    /** [size] if [type] has bender values for it, else the nearest larger one (or the largest). */
    fun sizeOrNearest(type: ConduitType, size: TradeSize?): TradeSize {
        val sizes = sizes(typeOrDefault(type))
        if (size == null) return sizes.first()
        return sizes.firstOrNull { it.inches >= size.inches } ?: sizes.last()
    }
}

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
    fun spec(type: ConduitType, size: TradeSize): BenderSpec? = specs[key(type, size)] ?: BenderDefaults.spec(type, size)

    companion object {
        fun key(type: ConduitType, size: TradeSize) = "${type.code}:${size.label}"
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
    private data class Row(val type: ConduitType, val size: TradeSize, val takeUp: Rational, val od: Double)

    private val rows = listOf(
        Row(ConduitType.EMT, TradeSize.S_1_2, Rational.of(5), 0.706),
        Row(ConduitType.EMT, TradeSize.S_3_4, Rational.of(6), 0.922),
        Row(ConduitType.EMT, TradeSize.S_1, Rational.of(8), 1.163),
        Row(ConduitType.EMT, TradeSize.S_1_1_4, Rational.of(11), 1.510),
        Row(ConduitType.IMC, TradeSize.S_1_2, Rational.of(6), 0.815),
        Row(ConduitType.IMC, TradeSize.S_3_4, Rational.of(8), 1.029),
        Row(ConduitType.IMC, TradeSize.S_1, Rational.of(9), 1.290),
        Row(ConduitType.RMC, TradeSize.S_1_2, Rational.of(6), 0.840),
        Row(ConduitType.RMC, TradeSize.S_3_4, Rational.of(8), 1.050),
        Row(ConduitType.RMC, TradeSize.S_1, Rational.of(9), 1.315),
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

    fun spec(type: ConduitType, size: TradeSize): BenderSpec? =
        rows.firstOrNull { it.type == type && it.size == size }?.let { BenderSpec(it.takeUp, gainFor(it.takeUp, it.od)) }

    /** Conduit outside diameter in inches for supported hand-bender sizes. */
    fun outsideDiameterIn(type: ConduitType, size: TradeSize): Double? =
        rows.firstOrNull { it.type == type && it.size == size }?.od

    /** Centerline bend radius in inches derived from [spec].takeUp and conduit outside diameter. */
    fun centerlineRadiusIn(type: ConduitType, size: TradeSize, spec: BenderSpec): Double {
        val od = outsideDiameterIn(type, size) ?: 0.0
        return (spec.takeUp.toDouble() - od / 2.0).coerceAtLeast(1.0)
    }
}

package com.thedirepack.constructioncalc.feature.conduitbending

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.LengthFormat
import com.thedirepack.constructioncalc.core.model.StoredQuantity
import com.thedirepack.constructioncalc.core.ui.toDecimalOrNull
import com.thedirepack.constructioncalc.feature.conduitbending.domain.BendRequest
import com.thedirepack.constructioncalc.feature.conduitbending.domain.BendType
import com.thedirepack.constructioncalc.feature.conduitbending.domain.BenderTable
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.feature.conduitbending.domain.Bendable
import kotlinx.serialization.Serializable

/**
 * What goes in a saved item. Every field has a default so older and newer files still decode;
 * never rename or repurpose a field once released.
 */
@Serializable
data class ConduitBendingInputs(
    val bend: String = BendType.OFFSET.name,
    /** One of [ANGLE_CHOICES]; "custom" uses [customAngle]. Used by offsets, rolling offsets, 4-point saddles and kicks. */
    val angle: String = "30",
    val customAngle: String = "",
    /** The 3-point saddle's center angle: "45" (22.5° outer) or "60" (30° outer). */
    val saddleAngle: String = "45",
    val offsetDepth: StoredQuantity? = null,
    val rise: StoredQuantity? = null,
    val roll: StoredQuantity? = null,
    val saddleHeight: StoredQuantity? = null,
    val saddleWidth: StoredQuantity? = null,
    val stubHeight: StoredQuantity? = null,
    val kickHeight: StoredQuantity? = null,
    /** Distance from the end of the stick to the obstruction (or the point the kick must reach). */
    val distance: StoredQuantity? = null,
    /** Space available for an offset or rolling offset (optional check). */
    val space: StoredQuantity? = null,
    /** A core [ConduitType.code]: the same conduit types a project run uses. */
    val conduitType: String = ConduitType.EMT.code,
    /** A [TradeSize.label], e.g. "3/4". */
    val conduitSize: String = TradeSize.S_1_2.label,
    val backToBack: Boolean = false,
    val between: StoredQuantity? = null,
    val secondStub: StoredQuantity? = null,
) {
    val bendType get() = BendType.fromName(bend)

    /** The conduit, limited to the types a hand bender handles. */
    val conduit: ConduitType get() = Bendable.typeOrDefault(ConduitType.fromCode(conduitType))

    /** The trade size, limited to the sizes with bender values for [conduit]. */
    val tradeSize: TradeSize get() = Bendable.sizeOrNearest(conduit, TradeSize.fromLabel(conduitSize))

    /** The angle in degrees for the current bend, or null when a custom angle isn't a number. */
    fun angleDegrees(): Double? = when {
        bendType == BendType.SADDLE_3 -> saddleAngle.toDoubleOrNull()
        angle == CUSTOM -> customAngle.toDecimalOrNull()
        else -> angle.toDoubleOrNull()
    }

    fun toRequest(table: BenderTable, format: LengthFormat = LengthFormat()) = BendRequest(
        type = bendType,
        size = when (bendType) {
            BendType.OFFSET -> offsetDepth
            BendType.SADDLE_3, BendType.SADDLE_4 -> saddleHeight
            BendType.STUB_UP -> stubHeight
            BendType.KICK -> kickHeight
            BendType.ROLLING_OFFSET -> null
        }.rational(),
        rise = rise.rational(),
        roll = roll.rational(),
        width = saddleWidth.rational(),
        angleDeg = angleDegrees(),
        distance = distance.rational(),
        space = space.rational(),
        conduit = conduit,
        conduitSize = tradeSize,
        backToBack = backToBack,
        between = between.rational(),
        secondStub = secondStub.rational(),
        bender = table,
        format = format,
    )

    companion object {
        const val CUSTOM = "custom"
        val ANGLE_CHOICES = listOf("10", "22.5", "30", "45", "60", CUSTOM)
        val SADDLE_ANGLES = listOf("45", "60")
    }
}

private fun StoredQuantity?.rational(): Rational? = this?.let { runCatching { Rational.parse(it.exact) }.getOrNull() }

package com.thedirepack.constructioncalc.feature.conduitbending

import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.LengthFormat
import com.thedirepack.constructioncalc.core.model.StoredQuantity
import com.thedirepack.constructioncalc.core.ui.toDecimalOrNull
import com.thedirepack.constructioncalc.feature.conduitbending.domain.BendRequest
import com.thedirepack.constructioncalc.feature.conduitbending.domain.BendType
import com.thedirepack.constructioncalc.feature.conduitbending.domain.Bendable
import com.thedirepack.constructioncalc.feature.conduitbending.domain.BenderTable
import com.thedirepack.constructioncalc.feature.conduitbending.domain.ConduitStick
import com.thedirepack.constructioncalc.feature.conduitbending.domain.DistanceAnchor
import com.thedirepack.constructioncalc.feature.conduitbending.domain.HookDirection
import com.thedirepack.constructioncalc.feature.conduitbending.domain.StickBendItem
import com.thedirepack.constructioncalc.feature.conduitbending.domain.normalizeRotationDeg
import kotlinx.serialization.Serializable

/**
 * What goes in a saved item. Every field has a default so older and newer files still decode;
 * never rename or repurpose a field once released.
 *
 * Legacy single-bend items omit [bends] (decoded as `null`); [effectiveBends] auto-migrates the
 * legacy top-level fields into a single [StickBendItem] so saved projects open identically.
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
    /** Stock stick length (null defaults to 120", i.e. 10'). */
    val stickLength: StoredQuantity? = null,
    /** Optional straight run past the last bend, included in cut length. */
    val tailLength: StoredQuantity? = null,
    /** Ordered bends on the stick. Null in legacy single-bend JSON; auto-migrated via [effectiveBends]. */
    val bends: List<StickBendItem>? = null,
) {
    val bendType get() = BendType.fromName(bend)

    /** The conduit, limited to the types a hand bender handles. */
    val conduit: ConduitType get() = Bendable.typeOrDefault(ConduitType.fromCode(conduitType))

    /** The trade size, limited to the sizes with bender values for [conduit]. */
    val tradeSize: TradeSize get() = Bendable.sizeOrNearest(conduit, TradeSize.fromLabel(conduitSize))

    /** Effective stock stick length in inches (defaults to 120" / 10'). */
    val effectiveStickLength: Rational
        get() = stickLength.rational()?.takeIf { it.signum > 0 } ?: StickBendItem.DEFAULT_STICK_LENGTH_IN

    /** The angle in degrees for the legacy single-bend fields, or null when a custom angle isn't a number. */
    fun angleDegrees(): Double? = when {
        bendType == BendType.SADDLE_3 -> saddleAngle.toDoubleOrNull()
        angle == CUSTOM -> customAngle.toDecimalOrNull()
        else -> angle.toDoubleOrNull()
    }

    /**
     * Converts legacy top-level single-bend fields into a 1-bend [StickBendItem]. Used automatically
     * whenever [bends] is `null` (such as decoding a legacy saved item).
     */
    fun legacyBendItem(): StickBendItem = StickBendItem(
        id = StickBendItem.DEFAULT_ID,
        type = bendType,
        angle = angle,
        customAngle = customAngle,
        saddleAngle = saddleAngle,
        offsetDepth = offsetDepth,
        rise = rise,
        roll = roll,
        saddleHeight = saddleHeight,
        saddleWidth = saddleWidth,
        stubHeight = stubHeight,
        kickHeight = kickHeight,
        distance = distance,
        distanceAnchor = DistanceAnchor.FROM_END_A,
        axialRotationDeg = 0.0,
        hookDirection = HookDirection.TOWARD_END_A,
        space = space,
        backToBack = backToBack,
        between = between,
        secondStub = secondStub,
    )

    /** Returns the explicit multi-bend list, or auto-migrates legacy fields into a 1-bend stick. */
    fun effectiveBends(): List<StickBendItem> = bends ?: listOf(legacyBendItem())

    /** Builds a [ConduitStick] ready for [com.thedirepack.constructioncalc.feature.conduitbending.domain.StickPlanner]. */
    fun toStick(table: BenderTable = BenderTable.DEFAULT, format: LengthFormat = LengthFormat()): ConduitStick =
        ConduitStick(
            conduit = conduit,
            conduitSize = tradeSize,
            stickLengthIn = effectiveStickLength.toDouble(),
            tailLengthIn = tailLength.rational()?.toDouble(),
            bends = effectiveBends(),
            bender = table,
            format = format,
            stickLengthExact = effectiveStickLength,
            tailLengthExact = tailLength.rational(),
        )

    /** Legacy single-bend request builder (kept for backward-compatible callers and tests). */
    fun toRequest(table: BenderTable, format: LengthFormat = LengthFormat()): BendRequest {
        val single = bends?.firstOrNull()
        if (single != null) {
            return single.toRequest(conduit, tradeSize, table, format)
        }
        return BendRequest(
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
    }

    /** Updates the stick bends list, keeping Bend 1 mirrored into legacy fields when only 1 bend exists. */
    fun withBends(nextBends: List<StickBendItem>): ConduitBendingInputs {
        val normalized = nextBends.mapIndexed { i, b ->
            b.copy(
                axialRotationDeg = normalizeRotationDeg(b.axialRotationDeg),
                distanceAnchor = if (i == 0) DistanceAnchor.FROM_END_A else b.distanceAnchor,
            )
        }
        val first = normalized.firstOrNull()
        return if (first != null && normalized.size == 1) {
            copy(
                bends = normalized,
                bend = first.type.name,
                angle = first.angle,
                customAngle = first.customAngle,
                saddleAngle = first.saddleAngle,
                offsetDepth = first.offsetDepth,
                rise = first.rise,
                roll = first.roll,
                saddleHeight = first.saddleHeight,
                saddleWidth = first.saddleWidth,
                stubHeight = first.stubHeight,
                kickHeight = first.kickHeight,
                distance = first.distance,
                space = first.space,
                backToBack = first.backToBack,
                between = first.between,
                secondStub = first.secondStub,
            )
        } else {
            copy(bends = normalized)
        }
    }

    companion object {
        const val CUSTOM = "custom"
        val ANGLE_CHOICES = listOf("10", "22.5", "30", "45", "60", CUSTOM)
        val SADDLE_ANGLES = listOf("45", "60")
        val QUICK_ROTATIONS = listOf(0.0, 90.0, 180.0, 270.0)
    }
}

private fun StoredQuantity?.rational(): Rational? = this?.let { runCatching { Rational.parse(it.exact) }.getOrNull() }

package com.thedirepack.constructioncalc.feature.conduitbending.domain

import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Decimals
import com.thedirepack.constructioncalc.core.measure.LengthFormat
import com.thedirepack.constructioncalc.core.model.StoredQuantity
import kotlinx.serialization.Serializable

/** Where a bend's distance measurement begins. */
@Serializable
enum class DistanceAnchor(val label: String) {
    FROM_END_A("From End A"),
    FROM_PREVIOUS("From prev"),
}

/** Which end of the conduit the bender shoe's hook points toward. */
@Serializable
enum class HookDirection(val label: String) {
    TOWARD_END_A("Hook → End A"),
    TOWARD_END_B("Hook → End B"),
}

/** Which reference mark on the bender shoe lines up with the tape mark. */
@Serializable
enum class BenderAlignment(val label: String) {
    ARROW("Arrow"),
    STAR("Star (★)"),
    CENTER_NOTCH("Center notch"),
}

/**
 * Normalizes any axial rotation angle in degrees into `[0.0, 360.0)`.
 * Non-finite inputs fall back to `0.0`.
 */
fun normalizeRotationDeg(deg: Double): Double {
    if (!deg.isFinite()) return 0.0
    val wrapped = ((deg % 360.0) + 360.0) % 360.0
    return if (wrapped >= 360.0 - 1e-9 || wrapped < 1e-9) 0.0 else wrapped
}

/**
 * One bend operation on a conduit stick (e.g. a 90° stub-up, a 2-bend offset, a 3-point saddle,
 * or a kick). All stored length fields use exact inches via [StoredQuantity].
 */
@Serializable
data class StickBendItem(
    val id: String = DEFAULT_ID,
    val type: BendType = BendType.OFFSET,
    /** One of `ANGLE_CHOICES` (`"10"`, `"22.5"`, `"30"`, `"45"`, `"60"`, `"custom"`). */
    val angle: String = "30",
    val customAngle: String = "",
    /** Center angle for a 3-point saddle (`"45"` or `"60"`). */
    val saddleAngle: String = "45",
    val offsetDepth: StoredQuantity? = null,
    val rise: StoredQuantity? = null,
    val roll: StoredQuantity? = null,
    val saddleHeight: StoredQuantity? = null,
    val saddleWidth: StoredQuantity? = null,
    val stubHeight: StoredQuantity? = null,
    val kickHeight: StoredQuantity? = null,
    /** Distance from [distanceAnchor] along the conduit run. */
    val distance: StoredQuantity? = null,
    val distanceAnchor: DistanceAnchor = DistanceAnchor.FROM_END_A,
    /** Axial roll around the conduit axis in degrees `[0, 360)`. */
    val axialRotationDeg: Double = 0.0,
    val hookDirection: HookDirection = HookDirection.TOWARD_END_A,
    val space: StoredQuantity? = null,
    /** Legacy/convenience back-to-back 90s inside a single stub-up card. */
    val backToBack: Boolean = false,
    val between: StoredQuantity? = null,
    val secondStub: StoredQuantity? = null,
) {
    /** Effective bend angle in degrees, or null if a custom angle is empty/invalid. */
    fun angleDegrees(): Double? = when {
        type == BendType.STUB_UP -> 90.0
        type == BendType.SADDLE_3 -> saddleAngle.toDoubleOrNull()
        angle == CUSTOM_ANGLE -> Decimals.parse(customAngle)
        else -> angle.toDoubleOrNull()
    }

    /** Primary dimension for this bend type, if entered. */
    fun primarySize(): Rational? = when (type) {
        BendType.OFFSET -> offsetDepth
        BendType.SADDLE_3, BendType.SADDLE_4 -> saddleHeight
        BendType.STUB_UP -> stubHeight
        BendType.KICK -> kickHeight
        BendType.ROLLING_OFFSET -> null
    }.rational()

    fun toRequest(
        conduit: ConduitType,
        conduitSize: TradeSize,
        table: BenderTable = BenderTable.DEFAULT,
        format: LengthFormat = LengthFormat(),
        overrideDistance: Rational? = distance.rational(),
    ) = BendRequest(
        type = type,
        size = primarySize(),
        rise = rise.rational(),
        roll = roll.rational(),
        width = saddleWidth.rational(),
        angleDeg = if (type == BendType.STUB_UP) null else angleDegrees(),
        distance = overrideDistance,
        space = space.rational(),
        conduit = conduit,
        conduitSize = conduitSize,
        backToBack = backToBack,
        between = between.rational(),
        secondStub = secondStub.rational(),
        bender = table,
        format = format,
    )

    companion object {
        const val DEFAULT_ID = "bend-1"
        const val CUSTOM_ANGLE = "custom"
        val DEFAULT_STICK_LENGTH_IN: Rational = Rational.of(120)
    }
}

/**
 * Full stick configuration fed into [StickPlanner].
 *
 * @property stickLengthIn stock conduit stick length in inches (default 120", i.e. 10').
 */
data class ConduitStick(
    val conduit: ConduitType = ConduitType.EMT,
    val conduitSize: TradeSize = TradeSize.S_1_2,
    val stickLengthIn: Double = 120.0,
    val tailLengthIn: Double? = null,
    val bends: List<StickBendItem> = emptyList(),
    val bender: BenderTable = BenderTable.DEFAULT,
    val format: LengthFormat = LengthFormat(),
    val stickLengthExact: Rational? = null,
    val tailLengthExact: Rational? = null,
) {
    val stickLength: Rational
        get() = stickLengthExact?.takeIf { it.signum > 0 }
            ?: if (stickLengthIn.isFinite() && stickLengthIn > 0.0) BendMath.tidy(stickLengthIn) else StickBendItem.DEFAULT_STICK_LENGTH_IN

    val tailLength: Rational?
        get() = tailLengthExact?.takeIf { it.signum >= 0 }
            ?: tailLengthIn?.takeIf { it.isFinite() && it >= 0.0 }?.let { BendMath.tidy(it) }
}

/**
 * One physical tape mark from End A for the continuous tape marking sheet.
 */
data class StickMark(
    val key: String,
    val bendItemIndex: Int,
    val bendItemId: String,
    val label: String,
    val positionFromEndAIn: Rational,
    val alignment: BenderAlignment,
    val hookDirection: HookDirection,
    val bendAngleDeg: Double,
    val axialRotationDeg: Double,
    val note: String? = null,
) {
    val positionIn: Double get() = positionFromEndAIn.toDouble()
}

/**
 * One physical bend event along the stick, ordered from End A to End B, used for clearance
 * checks, 3D centerline generation ([Pipe3DGeometry]), and run-path export ([PathBends]).
 */
data class PhysicalBend(
    val bendItemIndex: Int,
    val bendItemId: String,
    val markLabel: String,
    val markPosIn: Rational,
    val arcStartIn: Rational,
    val arcEndIn: Rational,
    val angleDeg: Double,
    val axialRotationDeg: Double,
    val radiusIn: Double,
    /** Layout coordinate after this bend's exit tangent (differs from [arcEndIn] by uncounted small-angle bend gain). */
    val layoutEndIn: Rational = arcEndIn,
)

/** Planned summary and marks for one [StickBendItem]. */
data class StickBendResult(
    val item: StickBendItem,
    val index: Int,
    val plan: BendPlan,
    val marks: List<StickMark>,
    val startPipeIn: Rational,
    val endPipeIn: Rational,
    val bendDegrees: Double,
    val issues: List<Issue>,
) {
    val hasResults: Boolean get() = plan.hasResults && marks.isNotEmpty()
}

/** Complete multi-bend stick layout, continuous tape marks, cut/remaining stats, and warnings. */
data class StickPlan(
    val conduit: ConduitType,
    val conduitSize: TradeSize,
    val stickLengthIn: Rational,
    val tailLengthIn: Rational?,
    val bendResults: List<StickBendResult>,
    val marks: List<StickMark>,
    val physicalBends: List<PhysicalBend>,
    val totalDevelopedLengthIn: Rational,
    val cutLengthIn: Rational,
    val remainingStraightIn: Rational,
    val totalDegrees: Double,
    val exceeds360Degrees: Boolean,
    val overflowsStick: Boolean,
    val overflowAmountIn: Rational,
    val hasShoeClearanceWarning: Boolean,
    val issues: List<Issue>,
    val hasResults: Boolean,
    val centerlineRadiusIn: Double,
) {
    val stickLengthDouble: Double get() = stickLengthIn.toDouble()
    val cutLengthDouble: Double get() = cutLengthIn.toDouble()
    val developedLengthDouble: Double get() = totalDevelopedLengthIn.toDouble()
    val remainingStraightDouble: Double get() = remainingStraightIn.toDouble()
}

private fun StoredQuantity?.rational(): Rational? =
    this?.let { runCatching { Rational.parse(it.exact) }.getOrNull() }

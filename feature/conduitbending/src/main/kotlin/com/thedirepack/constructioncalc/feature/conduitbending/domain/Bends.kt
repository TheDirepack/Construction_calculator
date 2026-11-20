package com.thedirepack.constructioncalc.feature.conduitbending.domain

import com.thedirepack.constructioncalc.core.math.Rational

/**
 * The common field constants hand-bending charts print for offsets: the multiplier is rounded
 * (e.g. 1.4 for 45°) and the shrink is a fraction of an inch per inch of offset depth.
 */
data class FieldConstant(val angleDeg: Double, val multiplier: Rational, val shrinkPerInch: Rational) {
    companion object {
        val OFFSETS = listOf(
            FieldConstant(10.0, Rational.of(6), Rational.of(1, 16)),
            FieldConstant(22.5, Rational.of(26, 10), Rational.of(3, 16)),
            FieldConstant(30.0, Rational.of(2), Rational.of(1, 4)),
            FieldConstant(45.0, Rational.of(14, 10), Rational.of(3, 8)),
            FieldConstant(60.0, Rational.of(12, 10), Rational.of(1, 2)),
        )

        fun forAngle(angleDeg: Double): FieldConstant? = OFFSETS.firstOrNull { BendMath.sameAngle(it.angleDeg, angleDeg) }
    }
}

// ---------------------------------------------------------------------------------------------
// Offset

/**
 * A two-bend offset at [angleDeg].
 *
 * @property spacing distance between the two marks = depth × csc θ.
 * @property shrink exact shrink = depth × tan(θ/2).
 * @property firstMark distance to obstruction + shrink, from the end of the stick (null without a distance).
 * @property secondMark [firstMark] + [spacing].
 */
data class OffsetResult(
    val depth: Rational,
    val angleDeg: Double,
    val multiplier: Rational,
    val spacing: Rational,
    val shrinkPerInch: Rational,
    val shrink: Rational,
    val field: FieldConstant?,
    val fieldShrink: Rational?,
    val firstMark: Rational?,
    val secondMark: Rational?,
)

object Bends {

    /** An offset of [depth] inches at [angleDeg]; null when the depth or angle is invalid. */
    fun offset(depth: Rational, angleDeg: Double, distance: Rational? = null): OffsetResult? {
        if (depth.signum <= 0 || !BendMath.validAngle(angleDeg)) return null
        val multiplier = BendMath.tidy(BendMath.csc(angleDeg))
        val perInch = BendMath.tidy(BendMath.tanHalf(angleDeg))
        val spacing = depth * multiplier
        val shrink = depth * perInch
        val field = FieldConstant.forAngle(angleDeg)
        val first = distance?.let { it + shrink }
        return OffsetResult(
            depth, angleDeg, multiplier, spacing, perInch, shrink,
            field, field?.let { depth * it.shrinkPerInch },
            first, first?.let { it + spacing },
        )
    }

    // -----------------------------------------------------------------------------------------
    // Rolling offset

    /** [trueOffset] = √(rise² + roll²), then the usual offset math on it. */
    data class RollingResult(val rise: Rational, val roll: Rational, val trueOffset: Rational, val offset: OffsetResult)

    fun rollingOffset(rise: Rational, roll: Rational, angleDeg: Double, distance: Rational? = null): RollingResult? {
        if (rise.signum < 0 || roll.signum < 0 || (rise.isZero && roll.isZero)) return null
        val trueOffset = BendMath.sqrt(rise * rise + roll * roll)
        val offset = offset(trueOffset, angleDeg, distance) ?: return null
        return RollingResult(rise, roll, trueOffset, offset)
    }

    // -----------------------------------------------------------------------------------------
    // 3-point saddle

    /**
     * A 3-point saddle: a [centerAngleDeg] bend in the middle with two bends of half that angle
     * [outerDistance] either side of it.
     *
     * @property multiplier 2.5 for 45°/22.5° and 2 for 60°/30° (the usual field values); other
     *   angles use the exact cosecant of the outer angle.
     * @property shrink the field shrink (the outer angle's offset constant, 3/16" per inch at 22.5°).
     * @property idealShrink pure geometry for two legs of depth [height]: 2·height·tan(outer/2).
     * @property centerMark distance to the obstruction's center + shrink/2 (null without a distance).
     */
    data class Saddle3Result(
        val height: Rational,
        val centerAngleDeg: Double,
        val outerAngleDeg: Double,
        val multiplier: Rational,
        val outerDistance: Rational,
        val shrinkPerInch: Rational,
        val shrink: Rational,
        val idealShrink: Rational,
        val centerMark: Rational?,
        val nearMark: Rational?,
        val farMark: Rational?,
    )

    fun saddle3(height: Rational, centerAngleDeg: Double, distanceToCenter: Rational? = null): Saddle3Result? {
        if (height.signum <= 0 || !BendMath.validAngle(centerAngleDeg)) return null
        val outer = centerAngleDeg / 2.0
        val multiplier = when {
            BendMath.sameAngle(centerAngleDeg, 45.0) -> Rational.of(5, 2)
            BendMath.sameAngle(centerAngleDeg, 60.0) -> Rational.of(2)
            else -> BendMath.tidy(BendMath.csc(outer))
        }
        val perInch = FieldConstant.forAngle(outer)?.shrinkPerInch ?: BendMath.tidy(BendMath.tanHalf(outer))
        val shrink = height * perInch
        val ideal = height * BendMath.tidy(2 * BendMath.tanHalf(outer))
        val outerDistance = height * multiplier
        val center = distanceToCenter?.let { it + shrink / Rational.of(2) }
        return Saddle3Result(
            height, centerAngleDeg, outer, multiplier, outerDistance, perInch, shrink, ideal,
            center, center?.let { it - outerDistance }, center?.let { it + outerDistance },
        )
    }

    // -----------------------------------------------------------------------------------------
    // 4-point saddle

    /**
     * A 4-point saddle: two offsets of [height] at [angleDeg], with [width] of straight conduit
     * between the 2nd and 3rd marks. Marks are from the end of the stick when a distance was
     * given (first mark = distance + the first offset's shrink), otherwise from the first mark.
     */
    data class Saddle4Result(
        val height: Rational,
        val width: Rational,
        val offset: OffsetResult,
        val totalShrink: Rational,
        val marks: List<Rational>,
        val fromEnd: Boolean,
    )

    fun saddle4(height: Rational, width: Rational, angleDeg: Double, distance: Rational? = null): Saddle4Result? {
        if (width.signum < 0) return null
        val off = offset(height, angleDeg, distance) ?: return null
        val m1 = off.firstMark ?: Rational.ZERO
        val m2 = m1 + off.spacing
        val m3 = m2 + width
        val m4 = m3 + off.spacing
        return Saddle4Result(height, width, off, off.shrink + off.shrink, listOf(m1, m2, m3, m4), distance != null)
    }

    // -----------------------------------------------------------------------------------------
    // 90° stub-up and back-to-back

    /** A single 90°: mark at height − take-up, measured from the end of the stick. */
    data class StubResult(val height: Rational, val spec: BenderSpec, val mark: Rational)

    /** Null when the stub is shorter than the bender's take-up (it can't be bent). */
    fun stubUp(height: Rational, spec: BenderSpec): StubResult? {
        if (height.signum <= 0 || height < spec.takeUp) return null
        return StubResult(height, spec, height - spec.takeUp)
    }

    /**
     * Two 90°s facing the same way (a U or a dog-leg): [between] is measured back to back, from
     * the back of the first bend to the back of the second. [cutLength] is the stick length when
     * the second stub's height is also known: stub1 + between + stub2 − 2 × gain.
     */
    data class BackToBackResult(
        val first: StubResult,
        val between: Rational,
        val secondMarkFromBack: Rational,
        val secondStub: Rational?,
        val cutLength: Rational?,
    )

    fun backToBack(height: Rational, spec: BenderSpec, between: Rational, secondStub: Rational? = null): BackToBackResult? {
        if (between.signum <= 0) return null
        val first = stubUp(height, spec) ?: return null
        val cut = secondStub?.takeIf { it.signum > 0 }?.let { height + between + it - spec.gain - spec.gain }
        return BackToBackResult(first, between, between, secondStub, cut)
    }

    // -----------------------------------------------------------------------------------------
    // Kick

    /**
     * A single bend at [angleDeg] that raises the conduit by [height]. [travel] is the conduit
     * length along the angled leg (height × csc), [run] the distance it covers along the original
     * line (height × cot), [shrink] the difference. [bendMark] = distance − run, from the end of
     * the stick, when the distance to the point where the kick must reach [height] is known.
     */
    data class KickResult(
        val height: Rational,
        val angleDeg: Double,
        val multiplier: Rational,
        val travel: Rational,
        val run: Rational,
        val shrink: Rational,
        val bendMark: Rational?,
    )

    fun kick(height: Rational, angleDeg: Double, distance: Rational? = null): KickResult? {
        if (height.signum <= 0 || !BendMath.validAngle(angleDeg)) return null
        val multiplier = BendMath.tidy(BendMath.csc(angleDeg))
        val travel = height * multiplier
        val run = height * BendMath.tidy(BendMath.cot(angleDeg))
        return KickResult(height, angleDeg, multiplier, travel, run, travel - run, distance?.let { it - run })
    }
}

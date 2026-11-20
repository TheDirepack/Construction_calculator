package com.thedirepack.constructioncalc.feature.conduitbending.domain

import com.thedirepack.constructioncalc.core.math.Rational

enum class BendType(val label: String) {
    OFFSET("Offset"),
    ROLLING_OFFSET("Rolling offset"),
    SADDLE_3("3-point saddle"),
    SADDLE_4("4-point saddle"),
    STUB_UP("90° stub-up"),
    KICK("Kick");

    companion object {
        fun fromName(name: String) = entries.firstOrNull { it.name == name } ?: OFFSET
    }
}

enum class FigureKind { LENGTH, ANGLE, RATIO }

/** One number a bend produces. [value] is inches for LENGTH, degrees for ANGLE, plain for RATIO. */
data class Figure(
    val key: String,
    val label: String,
    val kind: FigureKind,
    val value: Rational,
    val isMark: Boolean = false,
)

/** A mark to make on the conduit, [position] inches along the stick (see [BendPlan.marksFromEnd]). */
data class Mark(val key: String, val label: String, val position: Rational)

enum class Severity { INFO, WARNING, ERROR }

data class Issue(val severity: Severity, val text: String)

/**
 * Everything the screen shows for one bend: [figures] in display order (the first is the
 * headline), [marks] sorted along the stick, and [issues] (assumptions and invalid geometry).
 * [marksFromEnd] is false when no distance was given, in which case marks count from the first mark.
 */
data class BendPlan(
    val type: BendType,
    val figures: List<Figure>,
    val marks: List<Mark>,
    val marksFromEnd: Boolean,
    val issues: List<Issue>,
) {
    val hasResults get() = figures.isNotEmpty()
}

/** Everything the user can enter. Fields a bend doesn't use are ignored. */
data class BendRequest(
    val type: BendType,
    /** Offset depth, saddle height, kick height or stub height. */
    val size: Rational? = null,
    val rise: Rational? = null,
    val roll: Rational? = null,
    val width: Rational? = null,
    val angleDeg: Double? = null,
    /** Distance from the end of the stick to the obstruction (or its center, for a 3-point saddle). */
    val distance: Rational? = null,
    /** Space available for an offset, to check that it fits. */
    val space: Rational? = null,
    val conduit: ConduitType = ConduitType.EMT,
    val conduitSize: String = "1/2",
    val backToBack: Boolean = false,
    val between: Rational? = null,
    val secondStub: Rational? = null,
    val bender: BenderTable = BenderTable.DEFAULT,
)

object BendPlanner {

    fun plan(r: BendRequest): BendPlan = when (r.type) {
        BendType.OFFSET -> offset(r)
        BendType.ROLLING_OFFSET -> rolling(r)
        BendType.SADDLE_3 -> saddle3(r)
        BendType.SADDLE_4 -> saddle4(r)
        BendType.STUB_UP -> stub(r)
        BendType.KICK -> kick(r)
    }

    private fun len(key: String, label: String, v: Rational, mark: Boolean = false) = Figure(key, label, FigureKind.LENGTH, v, mark)
    private fun ratio(key: String, label: String, v: Rational) = Figure(key, label, FigureKind.RATIO, v)
    private fun angle(key: String, label: String, deg: Double) = Figure(key, label, FigureKind.ANGLE, BendMath.tidy(deg))

    private fun empty(type: BendType, vararg issues: Issue) = BendPlan(type, emptyList(), emptyList(), false, issues.toList())

    private fun badAngle(type: BendType) =
        empty(type, Issue(Severity.ERROR, "Angle must be greater than 0° and less than 90°."))

    private val SHRINK_NOTE = Issue(
        Severity.INFO,
        "Shrink is exact: depth × tan(angle ÷ 2). Printed field constants are shown for comparison and are rounded.",
    )
    private val DISTANCE_NOTE = Issue(
        Severity.INFO,
        "First mark = distance to the obstruction + shrink, then the second mark adds the spacing, all from the same end of the stick. " +
            "Use this when the distance is measured on the finished run past the offset; if it already lands on the first bend, ignore the shrink.",
    )

    // ---- offset -----------------------------------------------------------------------------

    private fun offsetFigures(o: OffsetResult, spacingLabel: String = "Distance between marks"): List<Figure> = buildList {
        add(len("spacing", spacingLabel, o.spacing))
        add(ratio("multiplier", "Multiplier (1 ÷ sin ${angleText(o.angleDeg)})", o.multiplier))
        add(len("shrink", "Shrink", o.shrink))
        o.field?.let {
            add(len("shrink_field", "Shrink, field constant (${it.shrinkPerInch}\" per inch)", o.fieldShrink!!))
        }
        add(angle("angle", "Bend angle", o.angleDeg))
    }

    private fun offsetMarks(o: OffsetResult): List<Mark> {
        val first = o.firstMark ?: Rational.ZERO
        return listOf(Mark("mark_1", "Mark 1", first), Mark("mark_2", "Mark 2", o.secondMark ?: o.spacing))
    }

    private fun spaceIssues(spacing: Rational, space: Rational?) =
        if (space != null && space.signum > 0 && spacing > space) {
            listOf(Issue(Severity.ERROR, "The marks need ${spacing.toDouble().fmt()}\" between them but only ${space.toDouble().fmt()}\" is available: use a steeper angle or a smaller offset."))
        } else emptyList()

    private fun offset(r: BendRequest): BendPlan {
        val angle = r.angleDeg ?: return empty(BendType.OFFSET)
        if (!BendMath.validAngle(angle)) return badAngle(BendType.OFFSET)
        val depth = r.size ?: return empty(BendType.OFFSET)
        val o = Bends.offset(depth, angle, r.distance) ?: return empty(BendType.OFFSET, Issue(Severity.ERROR, "Offset depth must be greater than zero."))
        val issues = buildList {
            addAll(spaceIssues(o.spacing, r.space))
            add(SHRINK_NOTE)
            if (r.distance != null) add(DISTANCE_NOTE)
        }
        return BendPlan(BendType.OFFSET, offsetFigures(o), offsetMarks(o), r.distance != null, issues)
    }

    // ---- rolling offset -------------------------------------------------------------------

    private fun rolling(r: BendRequest): BendPlan {
        val angle = r.angleDeg ?: return empty(BendType.ROLLING_OFFSET)
        if (!BendMath.validAngle(angle)) return badAngle(BendType.ROLLING_OFFSET)
        val rise = r.rise ?: return empty(BendType.ROLLING_OFFSET)
        val roll = r.roll ?: return empty(BendType.ROLLING_OFFSET)
        val res = Bends.rollingOffset(rise, roll, angle, r.distance)
            ?: return empty(BendType.ROLLING_OFFSET, Issue(Severity.ERROR, "Enter a rise and a roll (not both zero)."))
        val o = res.offset
        val figures = buildList {
            add(len("true_offset", "True offset", res.trueOffset))
            addAll(offsetFigures(o, "Travel (distance between marks)"))
        }
        val issues = buildList {
            addAll(spaceIssues(o.spacing, r.space))
            add(Issue(Severity.INFO, "True offset = √(rise² + roll²). Bend the offset at this depth, then rotate the conduit in the bender so the offset lands on the roll plane."))
            add(SHRINK_NOTE)
            if (r.distance != null) add(DISTANCE_NOTE)
        }
        return BendPlan(BendType.ROLLING_OFFSET, figures, offsetMarks(o), r.distance != null, issues)
    }

    // ---- 3-point saddle -------------------------------------------------------------------

    private fun saddle3(r: BendRequest): BendPlan {
        val center = r.angleDeg ?: return empty(BendType.SADDLE_3)
        if (!BendMath.validAngle(center)) return badAngle(BendType.SADDLE_3)
        val height = r.size ?: return empty(BendType.SADDLE_3)
        val s = Bends.saddle3(height, center, r.distance) ?: return empty(BendType.SADDLE_3, Issue(Severity.ERROR, "Obstruction height must be greater than zero."))
        val figures = listOf(
            len("outer_from_center", "Outer marks from center", s.outerDistance),
            ratio("multiplier", "Multiplier (outer marks ÷ height)", s.multiplier),
            len("shrink", "Shrink (field constant ${s.shrinkPerInch}\" per inch)", s.shrink),
            len("shrink_ideal", "Shrink, ideal geometry (reference)", s.idealShrink),
            angle("center_angle", "Center bend", s.centerAngleDeg),
            angle("outer_angle", "Outer bends", s.outerAngleDeg),
        )
        val marks = if (s.centerMark != null) {
            listOf(Mark("mark_near", "Near outer mark", s.nearMark!!), Mark("mark_center", "Center mark", s.centerMark), Mark("mark_far", "Far outer mark", s.farMark!!))
        } else {
            listOf(Mark("mark_near", "Near outer mark", Rational.ZERO), Mark("mark_center", "Center mark", s.outerDistance), Mark("mark_far", "Far outer mark", s.outerDistance + s.outerDistance))
        }
        val issues = buildList {
            if (s.nearMark != null && s.nearMark.signum < 0) {
                add(Issue(Severity.ERROR, "The obstruction is too close to the end of the stick: the near outer mark would fall ${(-s.nearMark.toDouble()).fmt()}\" before the end. Start with a longer piece or reverse the stick."))
            }
            add(Issue(Severity.INFO, "Outer marks use the usual field multiplier (2.5 for 45°/22.5°, 2 for 60°/30°), bent at the center angle and half that angle either side. Shrink uses the common field figure and the center mark moves by half of it. Pure geometry gives roughly twice that shrink, so make a test bend if length matters."))
            if (r.distance != null) add(Issue(Severity.INFO, "Center mark = distance to the obstruction's center + half the shrink."))
        }
        return BendPlan(BendType.SADDLE_3, figures, marks, r.distance != null, issues)
    }

    // ---- 4-point saddle -------------------------------------------------------------------

    private fun saddle4(r: BendRequest): BendPlan {
        val angle = r.angleDeg ?: return empty(BendType.SADDLE_4)
        if (!BendMath.validAngle(angle)) return badAngle(BendType.SADDLE_4)
        val height = r.size ?: return empty(BendType.SADDLE_4)
        val width = r.width ?: return empty(BendType.SADDLE_4)
        val s = Bends.saddle4(height, width, angle, r.distance)
            ?: return empty(BendType.SADDLE_4, Issue(Severity.ERROR, "Height must be greater than zero and width can't be negative."))
        val figures = buildList {
            add(len("total_shrink", "Total shrink", s.totalShrink))
            add(len("spacing", "Distance between marks 1–2 and 3–4", s.offset.spacing))
            add(len("width", "Between marks 2 and 3 (obstruction width)", s.width))
            add(ratio("multiplier", "Multiplier (1 ÷ sin ${angleText(angle)})", s.offset.multiplier))
            add(angle("angle", "Bend angle", angle))
        }
        val marks = s.marks.mapIndexed { i, p -> Mark("mark_${i + 1}", "Mark ${i + 1}", p) }
        val issues = buildList {
            add(Issue(Severity.INFO, "Two equal offsets with straight conduit between them. Bends 1 and 2 go one way, 3 and 4 return; shrink is exact for each offset and doubled."))
            if (r.distance != null) add(Issue(Severity.INFO, "First mark = distance to the obstruction + the first offset's shrink."))
        }
        return BendPlan(BendType.SADDLE_4, figures, marks, r.distance != null, issues)
    }

    // ---- stub-up and back-to-back --------------------------------------------------------

    private fun stub(r: BendRequest): BendPlan {
        val height = r.size ?: return empty(BendType.STUB_UP)
        val spec = r.bender.spec(r.conduit, r.conduitSize)
            ?: return empty(BendType.STUB_UP, Issue(Severity.ERROR, "No bender values for ${r.conduit.label} ${sizeLabel(r.conduitSize)}. Add them in this tool's settings."))
        val stub = Bends.stubUp(height, spec)
            ?: return empty(
                BendType.STUB_UP,
                Issue(Severity.ERROR, "A ${r.conduit.label} ${sizeLabel(r.conduitSize)} stub can't be shorter than the bender's take-up of ${spec.takeUp.toDouble().fmt()}\"."),
            )
        val figures = mutableListOf(
            len("stub_mark", "Mark from the end of the stick", stub.mark, mark = true),
            len("take_up", "Take-up (deduct)", spec.takeUp),
            len("gain", "Gain", spec.gain),
        )
        val marks = mutableListOf(Mark("mark_1", "Stub mark (set at the bender's arrow)", stub.mark))
        val issues = mutableListOf(
            Issue(Severity.INFO, "Mark = stub height − take-up. Take-up and gain are typical hand-bender values from this tool's settings: check them against your own bender."),
        )
        if (r.backToBack) {
            val between = r.between
            if (between != null) {
                val b = Bends.backToBack(height, spec, between, r.secondStub)
                if (b != null) {
                    figures.add(1, len("second_from_back", "Second mark, from the back of the first 90", b.secondMarkFromBack))
                    b.cutLength?.let { figures.add(len("cut_length", "Cut length (stub 1 + between + stub 2 − 2 × gain)", it)) }
                    issues.add(Issue(Severity.INFO, "Back-to-back: after the first 90, measure ${b.secondMarkFromBack.toDouble().fmt()}\" from the back of the bend, mark it, and line the mark up with your bender's back-to-back mark (often a star or the arrow's edge) for the second 90."))
                } else {
                    issues.add(Issue(Severity.ERROR, "Back-to-back distance must be greater than zero."))
                }
            } else {
                issues.add(Issue(Severity.WARNING, "Enter the back-to-back distance to get the second mark."))
            }
        }
        return BendPlan(BendType.STUB_UP, figures, marks, true, issues)
    }

    // ---- kick -----------------------------------------------------------------------------

    private fun kick(r: BendRequest): BendPlan {
        val angle = r.angleDeg ?: return empty(BendType.KICK)
        if (!BendMath.validAngle(angle)) return badAngle(BendType.KICK)
        val height = r.size ?: return empty(BendType.KICK)
        val k = Bends.kick(height, angle, r.distance) ?: return empty(BendType.KICK, Issue(Severity.ERROR, "Kick height must be greater than zero."))
        val figures = buildList {
            add(len("travel", "Travel along the kicked leg", k.travel))
            add(len("run", "Run along the original line", k.run))
            add(len("shrink", "Shrink", k.shrink))
            add(ratio("multiplier", "Multiplier (1 ÷ sin ${angleText(angle)})", k.multiplier))
            add(angle("angle", "Bend angle", angle))
        }
        val issues = buildList {
            val mark = k.bendMark
            if (mark != null && mark.signum < 0) {
                add(Issue(Severity.ERROR, "The kick needs ${k.run.toDouble().fmt()}\" of run but only ${r.distance!!.toDouble().fmt()}\" is available: use a steeper angle."))
            }
            add(Issue(Severity.INFO, "A kick is one bend: the conduit leaves its line at the angle and gains the height after the travel. The bend mark is the distance to where the kick must reach its height minus the run."))
        }
        val marks = k.bendMark?.takeIf { it.signum >= 0 }?.let { listOf(Mark("mark_1", "Bend mark", it)) }.orEmpty()
        return BendPlan(BendType.KICK, figures, marks, k.bendMark != null, issues)
    }

    private fun angleText(deg: Double) = deg.fmt() + "°"
}

/** A short decimal: up to 3 places, no trailing zeros. */
internal fun Double.fmt(): String {
    val text = String.format(java.util.Locale.US, "%.3f", this).trimEnd('0').trimEnd('.')
    return if (text == "-0") "0" else text
}

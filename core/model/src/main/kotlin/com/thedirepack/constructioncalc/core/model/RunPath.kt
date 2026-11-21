package com.thedirepack.constructioncalc.core.model

import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.RacewayBends
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Dimension
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.design.ProjectAnalysis
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs

/**
 * Which way a bend turns, which decides how cable weight acts in it.
 *
 * - [HORIZONTAL]: the bend lies in a level plane; weight presses the cable on the conduit floor.
 * - [VERTICAL_UP]: concave up (a sag, the bottom of a U): the path curves upward along the pull,
 *   e.g. from level into a riser going up, or from a drop into level.
 * - [VERTICAL_DOWN]: concave down (a hump, the top of an ∩): the path curves downward, e.g. from
 *   level over the top of a riser going down.
 *
 * The geometry doesn't depend on the pull direction: a sag pulled from the other end is still a sag.
 */
@Serializable
enum class BendPlane(val label: String) {
    HORIZONTAL("Horizontal"),
    VERTICAL_UP("Vertical, concave up (sag)"),
    VERTICAL_DOWN("Vertical, concave down (hump)"),
}

/**
 * One piece of a run's route, in order from the run's start to its end.
 *
 * Every field is checked on construction, so a damaged or hand-edited file with a negative length,
 * a 900° bend or NaN fails to load as "damaged" (like [Circuit]) instead of crashing the math.
 */
@Serializable
sealed interface PathSegment {
    /**
     * A straight length of conduit. [slopeDegrees] is its incline along the run's direction:
     * positive climbs, negative descends, 0 is level (−90 to 90).
     */
    @Serializable
    @SerialName("straight")
    data class Straight(val length: StoredQuantity, val slopeDegrees: Double = 0.0) : PathSegment {
        init {
            require(length.dimension == Dimension.LENGTH) { "A straight's length must be a length" }
            require(length.toQuantity().value.signum >= 0) { "A straight can't be negative" }
            require(slopeDegrees.isFinite() && slopeDegrees in -90.0..90.0) { "Invalid slope: $slopeDegrees" }
        }

        val lengthInches: Double get() = length.toQuantity().value.toDouble()
    }

    /**
     * A bend of [angleDegrees] (more than 0, at most 180).
     *
     * @property radius centerline radius; null uses the NEC Chapter 9 Table 2 minimum for the run's
     *   conduit ([RunPath.radiusInches]).
     * @property sourceItemId the saved Conduit Bending item that produced this bend, so a later
     *   edit of that item can replace its bends ([RunPath.replaceSource]).
     */
    @Serializable
    @SerialName("bend")
    data class Bend(
        val angleDegrees: Double,
        val radius: StoredQuantity? = null,
        val plane: BendPlane = BendPlane.HORIZONTAL,
        val sourceItemId: String? = null,
    ) : PathSegment {
        init {
            require(angleDegrees.isFinite() && angleDegrees > 0.0 && angleDegrees <= 180.0) { "Invalid bend angle: $angleDegrees" }
            require(radius == null || radius.dimension == Dimension.LENGTH) { "A bend radius must be a length" }
            require(radius == null || radius.toQuantity().value.signum > 0) { "A bend radius must be positive" }
        }
    }

    /** A pull box, conduit body or other access point: the pull is split here. */
    @Serializable
    @SerialName("pull_point")
    data class PullPoint(val label: String = "") : PathSegment {
        init {
            require(label.length <= 200) { "Pull point label too long" }
        }
    }
}

/** The segments of a run between two pull points (or the run's ends). [index] counts from 0. */
data class PathSection(val index: Int, val segments: List<PathSegment>) {
    val bends: List<PathSegment.Bend> get() = segments.filterIsInstance<PathSegment.Bend>()
    val degrees: Double get() = bends.sumOf { it.angleDegrees }
}

/**
 * Path math for [Run.path]. Pure; every length is in inches.
 *
 * A bend's length along the conduit is its centerline arc, R × θ (θ in radians). A path's length
 * is its straights plus its bend arcs; that's the run's routed length ([Raceway.length] follows it,
 * see [Project.withRunPath]), which the design engine uses for voltage drop.
 */
object RunPath {
    /** Largest number of segments a path may hold (a sanity cap for imported files). */
    const val MAX_SEGMENTS = 500

    /**
     * Radius of [bend] in inches: its own, else the NEC Chapter 9 Table 2 minimum for [type] in
     * [size] (see [RacewayBends]). Table 2 starts at trade size 1/2, so 3/8 uses the 1/2 row.
     * Null when there's no radius and no conduit size to look it up by.
     */
    fun radiusInches(bend: PathSegment.Bend, type: ConduitType?, size: TradeSize?): Double? {
        bend.radius?.let { return it.toQuantity().value.toDouble() }
        if (size == null) return null
        val row = if (size == TradeSize.S_3_8) TradeSize.S_1_2 else size
        return RacewayBends.minimumRadiusInches(type ?: ConduitType.EMT, row)
    }

    /** Arc length of [bend], R × θ, in inches; null without a radius. */
    fun arcInches(bend: PathSegment.Bend, type: ConduitType?, size: TradeSize?): Double? =
        radiusInches(bend, type, size)?.let { it * bend.angleDegrees * PI / 180.0 }

    /**
     * Routed length of [path] in inches: straights plus bend arcs. A bend with no radius and no
     * conduit size counts as 0 (the caller reports that the length is incomplete).
     */
    fun lengthInches(path: List<PathSegment>, type: ConduitType?, size: TradeSize?): Double =
        path.sumOf { segment ->
            when (segment) {
                is PathSegment.Straight -> segment.lengthInches
                is PathSegment.Bend -> arcInches(segment, type, size) ?: 0.0
                is PathSegment.PullPoint -> 0.0
            }
        }

    fun straightInches(path: List<PathSegment>): Double = path.filterIsInstance<PathSegment.Straight>().sumOf { it.lengthInches }

    fun bendInches(path: List<PathSegment>, type: ConduitType?, size: TradeSize?): Double =
        path.filterIsInstance<PathSegment.Bend>().sumOf { arcInches(it, type, size) ?: 0.0 }

    /** Splits [path] at its pull points. A path with no pull points is one section; empty sections are kept. */
    fun sections(path: List<PathSegment>): List<PathSection> {
        val out = mutableListOf<PathSection>()
        var current = mutableListOf<PathSegment>()
        path.forEach { segment ->
            if (segment is PathSegment.PullPoint) {
                out += PathSection(out.size, current)
                current = mutableListOf()
            } else {
                current += segment
            }
        }
        out += PathSection(out.size, current)
        return out
    }

    /** Total bend degrees in each section between pull points. */
    fun degreesPerSection(path: List<PathSegment>): List<Double> = sections(path).map { it.degrees }

    fun totalDegrees(path: List<PathSegment>): Double = path.filterIsInstance<PathSegment.Bend>().sumOf { it.angleDegrees }

    /** Sections over the 360° limit of the raceway articles' "Bends — (B) Number in One Run". */
    fun sectionsOverLimit(path: List<PathSegment>): List<PathSection> =
        sections(path).filter { it.degrees > RacewayBends.MAX_DEGREES_BETWEEN_PULL_POINTS + 1e-9 }

    /**
     * [path] changed so its routed length is [targetInches]: the difference goes into the last
     * straight (a new straight is added at the end when there's none). Null when the bends and the
     * other straights are already longer than [targetInches].
     */
    fun withLength(path: List<PathSegment>, targetInches: Rational, type: ConduitType?, size: TradeSize?): List<PathSegment>? {
        val lastStraight = path.indexOfLast { it is PathSegment.Straight }
        val others = path.filterIndexed { i, _ -> i != lastStraight }
        val fixed = Rational.of(lengthInches(others, type, size))
        val remaining = targetInches - fixed
        if (remaining.signum < 0) return null
        val straight = PathSegment.Straight(Quantity.inches(remaining).stored(), (path.getOrNull(lastStraight) as? PathSegment.Straight)?.slopeDegrees ?: 0.0)
        return if (lastStraight < 0) path + straight else path.toMutableList().also { it[lastStraight] = straight }
    }

    /**
     * [path] with the bends that came from [sourceItemId] replaced by [bends] (at the position of the
     * first old one), or [bends] appended at the end when none came from it yet.
     */
    fun replaceSource(path: List<PathSegment>, sourceItemId: String, bends: List<PathSegment.Bend>): List<PathSegment> {
        val tagged = bends.map { it.copy(sourceItemId = sourceItemId) }
        val first = path.indexOfFirst { it is PathSegment.Bend && it.sourceItemId == sourceItemId }
        if (first < 0) return path + tagged
        val kept = path.filterIndexed { i, s -> i < first && !(s is PathSegment.Bend && s.sourceItemId == sourceItemId) }
        val after = path.drop(first).filterNot { it is PathSegment.Bend && it.sourceItemId == sourceItemId }
        return kept + tagged + after
    }

    /** True when [path] holds bends from [sourceItemId]. */
    fun hasSource(path: List<PathSegment>, sourceItemId: String): Boolean =
        path.any { it is PathSegment.Bend && it.sourceItemId == sourceItemId }

    /** [path] without the bends from [sourceItemId] (e.g. when that item is deleted). */
    fun removeSource(path: List<PathSegment>, sourceItemId: String): List<PathSegment> =
        path.filterNot { it is PathSegment.Bend && it.sourceItemId == sourceItemId }

    internal fun sameLength(a: Double, b: Double) = abs(a - b) < 1e-6
}

/**
 * The project with [path] as [runId]'s route and the run's conduit length set to the routed length,
 * so voltage drop and every tool see the same length. An empty path leaves the length as it was.
 * A run without a conduit gets an EMT one (a route implies a conduit run). Bends without a radius
 * use Table 2 for the run's conduit size, or the size the design picks when it's automatic.
 */
fun Project.withRunPath(runId: String, path: List<PathSegment>, now: Long): Project {
    val run = run(runId) ?: return this
    if (path.isEmpty()) return updateRun(runId, now) { it.copy(path = emptyList()) }
    val raceway = run.raceway ?: Raceway()
    val staged = updateRun(runId, now) { it.copy(path = path, raceway = raceway) }
    return staged.syncRunLength(runId, now)
}

/** Recomputes [runId]'s conduit length from its path (after a conduit change, say). No path: unchanged. */
fun Project.syncRunLength(runId: String, now: Long): Project {
    val run = run(runId) ?: return this
    val raceway = run.raceway ?: return this
    if (run.path.isEmpty()) return this
    val size = raceway.tradeSize ?: ProjectAnalysis.analyze(this).runs[runId]?.conduit?.tradeSize
    val inches = RunPath.lengthInches(run.path, raceway.conduitType, size)
    val length = Quantity.inches(Rational.of(inches)).stored()
    if (raceway.length?.toQuantity()?.value?.toDouble()?.let { RunPath.sameLength(it, inches) } == true) return this
    return updateRun(runId, now) { it.copy(raceway = raceway.copy(length = length)) }
}

/**
 * The project with [runId]'s conduit length set to [length]. When the run has a path, the path's
 * last straight absorbs the change so the two stay equal; returns null if the bends and other
 * straights already exceed [length].
 */
fun Project.withRunLength(runId: String, length: StoredQuantity?, now: Long): Project? {
    val run = run(runId) ?: return null
    val raceway = run.raceway ?: Raceway()
    if (length == null || run.path.isEmpty()) return updateRun(runId, now) { it.copy(raceway = raceway.copy(length = length)) }
    val size = raceway.tradeSize ?: ProjectAnalysis.analyze(this).runs[runId]?.conduit?.tradeSize
    val path = RunPath.withLength(run.path, length.toQuantity().value, raceway.conduitType, size) ?: return null
    return updateRun(runId, now) { it.copy(path = path, raceway = raceway.copy(length = length)) }
}

/**
 * The project with [bends] from the saved item [sourceItemId] in [runId]'s path: they replace that
 * item's earlier bends in place, or are added at the end. A run with a typed length and no path
 * yet keeps that length: the rest of it becomes a straight after the bends.
 */
fun Project.addBendsToRunPath(runId: String, sourceItemId: String, bends: List<PathSegment.Bend>, now: Long): Project {
    val run = run(runId) ?: return this
    if (bends.isEmpty()) return this
    val path = if (run.path.isEmpty() && run.raceway?.length != null) {
        val tagged = bends.map { it.copy(sourceItemId = sourceItemId) }
        val raceway = run.raceway
        val size = raceway.tradeSize ?: ProjectAnalysis.analyze(this).runs[runId]?.conduit?.tradeSize
        RunPath.withLength(tagged, raceway.length!!.toQuantity().value, raceway.conduitType, size) ?: tagged
    } else {
        RunPath.replaceSource(run.path, sourceItemId, bends)
    }
    return withRunPath(runId, path, now)
}

/**
 * The project with [raceway] as [runId]'s conduit, keeping its length and path in step: a changed
 * length moves the path's last straight ([withRunLength]); otherwise (or when the path can't be
 * that short) the length follows the path for the new conduit size.
 */
fun Project.withRaceway(runId: String, raceway: Raceway, now: Long): Project {
    val run = run(runId) ?: return this
    val staged = updateRun(runId, now) { it.copy(raceway = raceway.copy(length = run.raceway?.length ?: raceway.length)) }
    if (run.path.isEmpty()) return updateRun(runId, now) { it.copy(raceway = raceway) }
    val lengthChanged = raceway.length != run.raceway?.length && raceway.length != null
    return (if (lengthChanged) staged.withRunLength(runId, raceway.length, now) else null) ?: staged.syncRunLength(runId, now)
}

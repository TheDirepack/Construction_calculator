package com.thedirepack.constructioncalc.feature.conduitbending.domain

import com.thedirepack.constructioncalc.core.model.PathSegment

/**
 * The individual bends a Conduit Bending result puts in a run's path, in order along the conduit:
 * - offset and rolling offset: two bends of θ
 * - 3-point saddle: θ/2, θ, θ/2 (θ is the center bend, 45° or 60°)
 * - 4-point saddle: four bends of θ
 * - kick: one bend of θ
 * - 90° stub-up: one 90° bend (two for back-to-back 90s)
 *
 * The bends have no radius of their own (a hand bender makes a one-shot bend, so the run's
 * NEC Chapter 9 Table 2 radius applies) and lie in the horizontal plane until the user says
 * otherwise in the pull planner.
 */
object PathBends {
    fun angles(type: BendType, angleDeg: Double?, backToBack: Boolean = false): List<Double> {
        if (type == BendType.STUB_UP) return if (backToBack) listOf(90.0, 90.0) else listOf(90.0)
        val a = angleDeg?.takeIf { it.isFinite() && it > 0.0 && it <= 90.0 } ?: return emptyList()
        return when (type) {
            BendType.OFFSET, BendType.ROLLING_OFFSET -> listOf(a, a)
            BendType.SADDLE_3 -> listOf(a / 2, a, a / 2)
            BendType.SADDLE_4 -> listOf(a, a, a, a)
            BendType.KICK -> listOf(a)
            BendType.STUB_UP -> listOf(90.0)
        }
    }

    /** Path bends for a finished [plan] of [request], tagged with [sourceItemId]; empty when the plan has no result. */
    fun of(request: BendRequest, plan: BendPlan, sourceItemId: String? = null): List<PathSegment.Bend> {
        if (!plan.hasResults) return emptyList()
        return angles(request.type, request.angleDeg, request.backToBack).map { PathSegment.Bend(it, sourceItemId = sourceItemId) }
    }

    /**
     * Path bends for every planned bend on a multi-bend stick ([StickPlan]), in order from End A to
     * End B, tagged with [sourceItemId]. Empty when no bend on the stick has results yet.
     */
    fun of(stickPlan: StickPlan, sourceItemId: String? = null): List<PathSegment.Bend> {
        if (!stickPlan.hasResults) return emptyList()
        return stickPlan.physicalBends
            .filter { it.angleDeg.isFinite() && it.angleDeg > 0.0 && it.angleDeg <= 180.0 }
            .map { PathSegment.Bend(it.angleDeg, sourceItemId = sourceItemId) }
    }
}

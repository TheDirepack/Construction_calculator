package com.thedirepack.constructioncalc.feature.reellength.domain

import kotlin.math.PI

/** The reel and cable, all dimensions in inches. */
data class ReelRequest(
    /** Flange (outside) diameter, F. */
    val flangeDiameter: Double,
    /** Drum / core diameter, D. */
    val drumDiameter: Double,
    /** Traverse, W: the inside width between the flanges. */
    val traverse: Double,
    /** Cable outside diameter, d. */
    val cableDiameter: Double,
    /**
     * Distance X from the flange edge down to the top of the cable (partial reel), or the
     * clearance to keep below the flange edge (capacity).
     */
    val topClearance: Double,
    /** Multiplies the length to allow for loose winding or cable that doesn't pack tightly. */
    val packingFactor: Double = 1.0,
)

data class ReelResult(
    /** Depth of cable on the drum, H = (F − 2X − D) / 2, in inches. */
    val woundDepth: Double,
    val lengthInches: Double,
    val lengthFeet: Double,
    /** Layers ≈ H / d. Not rounded: a partly filled outer layer shows as a fraction. */
    val layers: Double,
    /** Turns per layer ≈ W / d. */
    val turnsPerLayer: Double,
)

/**
 * Cable on a reel, by the standard level-wound estimate.
 *
 * The wound cable is a hollow cylinder of the drum diameter D (inner), depth H, and width W.
 * Its mean diameter is D + H, so its mean circumference is π (D + H). Packing cable of
 * diameter d into the cross-section (H × W) puts about (H / d) layers of (W / d) turns on the
 * reel, and the length is
 *
 * ```
 * H      = (F − 2X − D) / 2                  (inches)
 * length = π (D + H) · H · W / d²            (inches)
 *        = π (D + H) · H · W / (12 d²)       (feet)
 * ```
 *
 * Assumptions: every layer is wound evenly with turns touching and no cross-over gaps (a
 * square, not a hexagonal, packing), the cable is perfectly round, and there is no slack,
 * tape or tail. Real reels hold somewhat less. The [ReelRequest.packingFactor] (default 1.0)
 * scales the result for that. This is an estimate: confirm by weight when it matters.
 */
object ReelLength {
    /** Error messages for inputs that can't describe a reel; empty when the request is usable. */
    fun problems(r: ReelRequest): List<String> = buildList {
        val values = listOf(r.flangeDiameter, r.drumDiameter, r.traverse, r.cableDiameter, r.topClearance, r.packingFactor)
        if (values.any { !it.isFinite() }) {
            add("Enter real numbers for every input.")
            return@buildList
        }
        if (r.cableDiameter <= 0) add("Cable diameter must be greater than 0.")
        if (r.drumDiameter <= 0) add("Drum diameter must be greater than 0.")
        if (r.traverse <= 0) add("Traverse must be greater than 0.")
        if (r.topClearance < 0) add("Distance from the flange edge can't be negative.")
        if (r.packingFactor <= 0) add("Packing factor must be greater than 0.")
        if (r.flangeDiameter <= r.drumDiameter) {
            add("Flange diameter must be larger than the drum diameter.")
        } else if (r.drumDiameter > 0 && r.topClearance >= 0 && r.flangeDiameter - 2 * r.topClearance <= r.drumDiameter) {
            add("The distance from the flange edge is too large: there is no room for cable above the drum.")
        }
        if (r.traverse > 0 && r.cableDiameter > r.traverse) add("The cable is wider than the traverse.")
    }

    /** Wound depth H in inches; negative or zero means there's no cable above the drum. */
    fun woundDepth(r: ReelRequest): Double = (r.flangeDiameter - 2 * r.topClearance - r.drumDiameter) / 2

    /** The estimate, or null if [problems] is not empty. */
    fun calculate(r: ReelRequest): ReelResult? {
        if (problems(r).isNotEmpty()) return null
        val h = woundDepth(r)
        val d = r.cableDiameter
        val layers = h / d
        val turns = r.traverse / d
        val inches = PI * (r.drumDiameter + h) * layers * turns * r.packingFactor
        if (!inches.isFinite() || !layers.isFinite() || !turns.isFinite()) return null
        return ReelResult(
            woundDepth = h,
            lengthInches = inches,
            lengthFeet = inches / 12,
            layers = layers,
            turnsPerLayer = turns,
        )
    }
}

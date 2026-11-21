package com.thedirepack.constructioncalc.core.electrical

import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule

/**
 * Standard ampere ratings for fuses and inverse-time circuit breakers: [CodeRule.STANDARD_OCPD]
 * (240.6(A) in NEC 2023), from [Code.book], plus the ratings the code adds for fuses only.
 */
object StandardOcpd {
    /** The standard ratings (10 A to 6000 A in NEC 2023), ascending. */
    val ratings: List<Int> get() = Code.book.standardOcpdRatings

    /** Extra standard ratings for fuses only (240.6(A)). */
    val additionalFuseRatings: List<Int> get() = Code.book.fuseOnlyRatings

    /** The standard ratings, plus the fuse-only ratings when [fuse] is true, in ascending order. */
    fun ratings(fuse: Boolean = false): List<Int> =
        if (fuse) (additionalFuseRatings + ratings).sorted() else ratings

    /** The smallest standard rating at or above [amps], or null if it exceeds the largest. */
    fun nextUp(amps: Double, fuse: Boolean = false): Int? =
        ratings(fuse).firstOrNull { it >= amps - 1e-9 }

    /** The largest standard rating at or below [amps], or null if it is below the smallest. */
    fun nextDown(amps: Double, fuse: Boolean = false): Int? =
        ratings(fuse).lastOrNull { it <= amps + 1e-9 }
}

/**
 * [CodeRule.CONDUCTOR_PROTECTION] (240.4): the largest standard overcurrent device that may protect
 * conductors of a given ampacity. One rule for the whole app (wire sizing, conduit fill derating).
 */
object ConductorProtection {
    /**
     * [CodeRule.NEXT_SIZE_UP] (240.4(B)): the conductor's ampacity if that is a standard rating, otherwise
     * the standard rating just below it or (next size up) the one just above it, as long as that is no
     * more than [com.thedirepack.constructioncalc.core.electrical.code.CodeValues.nextSizeUpMaxAmps]
     * and the circuit isn't a multi-outlet branch circuit for cord-and-plug loads (240.4(B)(1)). The
     * small-conductor limits are not applied here; see [maxOcpd].
     */
    fun maxStandardOcpd(ampacity: Double, multipleCordReceptacles: Boolean = false): Int? {
        val atOrBelow = StandardOcpd.nextDown(ampacity)
        val isStandard = StandardOcpd.ratings.any { kotlin.math.abs(it - ampacity) < 1e-9 }
        val maxNextUp = Code.book.values.nextSizeUpMaxAmps
        val nextUp = if (multipleCordReceptacles || isStandard) null else StandardOcpd.nextUp(ampacity)?.takeIf { it <= maxNextUp }
        return listOfNotNull(atOrBelow, nextUp).maxOrNull()
    }

    /** [maxStandardOcpd] capped by the [CodeRule.SMALL_CONDUCTORS] limits. */
    fun maxOcpd(ampacity: Double, size: ConductorSize, material: ConductorMaterial, multipleCordReceptacles: Boolean = false): Int? {
        val candidate = maxStandardOcpd(ampacity, multipleCordReceptacles) ?: return null
        val limit = SmallConductorLimit.maxOcpd(size, material) ?: return candidate
        return minOf(candidate, limit)
    }
}

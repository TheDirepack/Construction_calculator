package com.thedirepack.constructioncalc.core.electrical

/**
 * Standard ampere ratings for fuses and inverse-time circuit breakers.
 *
 * Source: NEC (NFPA 70) 2023, Table 240.6(A) (verified against the printed table, 70-124).
 * Section 240.6(A) adds 1, 3, 6 and 601 A as standard ratings for **fuses**.
 */
object StandardOcpd {
    /** Table 240.6(A), 10 A to 6000 A. */
    val ratings: List<Int> = listOf(
        10, 15, 20, 25, 30, 35, 40, 45, 50, 60, 70, 80, 90, 100, 110, 125, 150, 175, 200, 225, 250,
        300, 350, 400, 450, 500, 600, 700, 800, 1000, 1200, 1600, 2000, 2500, 3000, 4000, 5000, 6000,
    )

    /** Extra standard ratings for fuses only (240.6(A)). */
    val additionalFuseRatings: List<Int> = listOf(1, 3, 6, 601)

    /** Table 240.6(A), plus the fuse-only ratings when [fuse] is true, in ascending order. */
    fun ratings(fuse: Boolean = false): List<Int> =
        if (fuse) (additionalFuseRatings + ratings).sorted() else ratings

    /** The smallest standard rating at or above [amps], or null if it exceeds 6000 A. */
    fun nextUp(amps: Double, fuse: Boolean = false): Int? =
        ratings(fuse).firstOrNull { it >= amps - 1e-9 }

    /** The largest standard rating at or below [amps], or null if it is below the smallest. */
    fun nextDown(amps: Double, fuse: Boolean = false): Int? =
        ratings(fuse).lastOrNull { it <= amps + 1e-9 }
}

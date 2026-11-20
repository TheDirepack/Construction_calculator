package com.thedirepack.constructioncalc.core.model

import kotlinx.serialization.Serializable

/**
 * A result recently produced by any tool. These feed the "use a value from…" picker on every
 * input, which is what lets a length from Foot-Inch flow into Voltage Drop, Pull Planner, etc.
 */
@Serializable
data class RecentResult(
    val value: ResultValue,
    val toolId: String,
    val toolTitle: String,
    /** Short description of where it came from, e.g. the expression `12' 6" × 10' 3"`. */
    val context: String = "",
    val at: Long,
)

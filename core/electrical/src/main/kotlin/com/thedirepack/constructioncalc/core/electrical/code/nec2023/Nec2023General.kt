package com.thedirepack.constructioncalc.core.electrical.code.nec2023

import com.thedirepack.constructioncalc.core.electrical.code.WorkingSpaceRow

/** NEC 2023 Article 110 (general requirements). Data only. */
internal object Nec2023General {

    /**
     * **Table 110.26(A)(1)** "Working Spaces": minimum clear depth in front of equipment 1000 V or less,
     * by nominal voltage to ground, for Conditions 1, 2 and 3, in inches and the code's millimetres.
     * Checked against the printed table (70-68).
     */
    val workingSpace: List<WorkingSpaceRow> = listOf(
        WorkingSpaceRow("0–150", 150, listOf(36, 36, 36), listOf(900, 900, 900)),
        WorkingSpaceRow("151–600", 600, listOf(36, 42, 48), listOf(900, 1000, 1200)),
        WorkingSpaceRow("601–1000", 1000, listOf(36, 48, 60), listOf(900, 1200, 1500)),
    )
}

package com.thedirepack.constructioncalc.core.electrical

/**
 * The three conditions of Table 110.26(A)(1), in short:
 * 1. exposed live parts on one side, nothing live or grounded on the other (or guarded by insulation);
 * 2. exposed live parts on one side, grounded parts on the other (concrete, brick and tile walls count as grounded);
 * 3. exposed live parts on both sides.
 */
enum class WorkingSpaceCondition(val label: String, val description: String) {
    ONE("Condition 1", "Live parts on one side; nothing live or grounded opposite (or guarded by insulation)"),
    TWO("Condition 2", "Live parts on one side; grounded parts opposite (concrete, brick and tile walls count)"),
    THREE("Condition 3", "Live parts on both sides of the space"),
}

/**
 * NEC (NFPA 70) 2023 **Table 110.26(A)(1)** "Working Spaces": minimum clear depth in front of
 * equipment 1000 V or less, by nominal voltage to ground. Checked against the printed table (70-68).
 * 110.26(A)(2) and (A)(3) set the width (30 in. or the equipment width, whichever is greater) and
 * the height (6-1/2 ft or the equipment height, whichever is greater).
 */
object WorkingSpace {
    /** One voltage row. Depths are in inches, the code's millimetre values beside them. */
    class Row(val voltsToGround: String, val maxVolts: Int, val depthIn: List<Int>, val depthMm: List<Int>)

    val rows: List<Row> = listOf(
        Row("0–150", 150, listOf(36, 36, 36), listOf(900, 900, 900)),
        Row("151–600", 600, listOf(36, 42, 48), listOf(900, 1000, 1200)),
        Row("601–1000", 1000, listOf(36, 48, 60), listOf(900, 1200, 1500)),
    )

    /** Minimum width, inches (or the equipment's width if wider). */
    const val MIN_WIDTH_IN = 30

    /** Minimum height, inches (or the equipment's height if taller). */
    const val MIN_HEIGHT_IN = 78

    /** Minimum depth in inches, or null above 1000 V to ground (110.34 applies instead). */
    fun depthInches(voltsToGround: Double, condition: WorkingSpaceCondition): Int? =
        rows.firstOrNull { voltsToGround <= it.maxVolts }?.depthIn?.get(condition.ordinal)
}

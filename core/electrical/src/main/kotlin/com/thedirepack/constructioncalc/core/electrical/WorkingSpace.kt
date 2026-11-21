package com.thedirepack.constructioncalc.core.electrical

import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.electrical.code.WorkingSpaceRow

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
 * [CodeRule.WORKING_SPACE] (Table 110.26(A)(1) "Working Spaces" in NEC 2023): minimum clear depth in
 * front of equipment 1000 V or less, by nominal voltage to ground, from [Code.book]. The width
 * ([CodeRule.WORKING_SPACE_WIDTH]) is [MIN_WIDTH_IN] or the equipment width, whichever is greater; the
 * height ([CodeRule.WORKING_SPACE_HEIGHT]) is [MIN_HEIGHT_IN] or the equipment height.
 */
object WorkingSpace {
    /** The table's rows, lowest voltage first. Depths are in inches, the code's millimetre values beside them. */
    val rows: List<WorkingSpaceRow> get() = Code.book.workingSpace

    /** Minimum width, inches (or the equipment's width if wider). */
    val MIN_WIDTH_IN: Int get() = Code.book.values.workingSpaceMinWidthIn

    /** Minimum height, inches (or the equipment's height if taller). */
    val MIN_HEIGHT_IN: Int get() = Code.book.values.workingSpaceMinHeightIn

    /** Minimum depth in inches, or null above the table's highest voltage (110.34 applies instead). */
    fun depthInches(voltsToGround: Double, condition: WorkingSpaceCondition): Int? =
        rows.firstOrNull { voltsToGround <= it.maxVolts }?.depthIn?.get(condition.ordinal)
}

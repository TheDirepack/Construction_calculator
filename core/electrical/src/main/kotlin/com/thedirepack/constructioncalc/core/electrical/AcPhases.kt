package com.thedirepack.constructioncalc.core.electrical

import kotlin.math.sqrt

/** Single- or three-phase AC: the one choice every electrical tool offers. */
enum class AcPhases(val label: String) {
    SINGLE("Single-phase"),
    THREE("Three-phase");

    /** Line-current factor in VA = V × I × k: 1 single-phase, √3 three-phase (line-to-line V). */
    val powerFactorK: Double get() = if (this == SINGLE) 1.0 else sqrt(3.0)

    /** Voltage-drop factor in VD = k × I × L × Z: 2 (out and back) single-phase, √3 three-phase. */
    val dropFactor: Double get() = if (this == SINGLE) 2.0 else sqrt(3.0)
}

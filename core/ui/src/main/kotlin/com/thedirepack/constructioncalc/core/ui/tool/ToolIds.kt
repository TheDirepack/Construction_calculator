package com.thedirepack.constructioncalc.core.ui.tool

/**
 * Ids of tools that other tools link to (features never import each other). Each tool's own `ID`
 * constant points here, so the two can't drift apart.
 */
object ToolIds {
    const val PROJECTS = "projects"
    const val DRAWINGS = "drawings"
    const val FOOT_INCH = "foot-inch"
    const val PANELS = "panels"
    const val CONDUIT_BENDING = "conduit-bending"
    const val CONDUIT_FILL = "conduit-fill"
    const val VOLTAGE_DROP = "voltage-drop"
    const val REEL_LENGTH = "reel-length"
    const val PULL_PLANNER = "pull-planner"
    const val VFD_SIZING = "vfd-sizing"
    const val FORMULAS = "formulas"
    const val REFERENCE = "reference"
}

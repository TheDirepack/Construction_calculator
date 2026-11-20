package com.thedirepack.constructioncalc.core.model

/**
 * What a tool was opened *for*, besides a saved item: a project's page, or one circuit or run of a
 * project so the tool can start from it (Voltage Drop for a circuit, Conduit Fill for a run…).
 * Travels with the app's back stack as a short string ([encode]).
 */
sealed interface ToolTarget {
    val projectId: String

    data class OfProject(override val projectId: String) : ToolTarget
    data class OfCircuit(override val projectId: String, val circuitId: String) : ToolTarget
    data class OfRun(override val projectId: String, val runId: String) : ToolTarget

    fun encode(): String = when (this) {
        is OfProject -> "project$SEP$projectId"
        is OfCircuit -> "circuit$SEP$projectId$SEP$circuitId"
        is OfRun -> "run$SEP$projectId$SEP$runId"
    }

    companion object {
        private const val SEP = '|'

        /** Null for anything [encode] didn't produce. */
        fun decode(text: String): ToolTarget? {
            val parts = text.split(SEP)
            return when {
                parts.size == 2 && parts[0] == "project" -> OfProject(parts[1])
                parts.size == 3 && parts[0] == "circuit" -> OfCircuit(parts[1], parts[2])
                parts.size == 3 && parts[0] == "run" -> OfRun(parts[1], parts[2])
                else -> null
            }.takeIf { t -> t != null && parts.drop(1).all { it.isNotEmpty() } }
        }
    }
}

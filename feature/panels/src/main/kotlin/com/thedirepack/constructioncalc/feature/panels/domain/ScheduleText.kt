package com.thedirepack.constructioncalc.feature.panels.domain

import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.model.ProjectText
import com.thedirepack.constructioncalc.core.model.design.MaterialTakeoffs
import com.thedirepack.constructioncalc.core.model.design.PullAnalysis
import com.thedirepack.constructioncalc.core.model.design.PullText
import com.thedirepack.constructioncalc.core.model.design.Wire
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import com.thedirepack.constructioncalc.core.model.Circuit
import com.thedirepack.constructioncalc.core.model.design.CircuitDesign
import com.thedirepack.constructioncalc.core.model.CircuitKind
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.design.PanelDesign
import com.thedirepack.constructioncalc.core.model.design.PanelLayout
import com.thedirepack.constructioncalc.core.model.design.Phase
import com.thedirepack.constructioncalc.core.model.design.ProjectDesign

/** Facts about a panel shown on every export. */
object PanelFacts {
    /** `120/240 V 1φ 3W · 42 spaces · 225 A bus · Main lugs only`. */
    fun summary(panel: Panel): String = listOfNotNull(
        panel.system.label,
        "${panel.spaces} spaces",
        panel.busAmps?.let { "$it A bus" },
        panel.mainAmps?.let { "$it A main" } ?: "Main lugs only",
    ).joinToString(" · ")

    /** "DP-1 #3,5,7 Feeder" for the circuit feeding [panel], if it still exists. */
    fun fedFromText(project: Project, panel: Panel): String? {
        val ref = panel.fedFrom ?: return null
        val source = project.panel(ref.panelId) ?: return null
        val circuit = source.circuits.firstOrNull { it.id == ref.circuitId } ?: return null
        return circuitLabel(source, circuit)
    }

    fun phaseText(circuit: Circuit, phases: Int): String =
        PanelLayout.phasesOf(circuit, phases).joinToString(",")

    /**
     * A circuit's load: its connected VA, or for a feeder with no VA of its own the load of the
     * panels it feeds (from the design, marked "fed"). Spare and space circuits have none.
     */
    fun loadText(circuit: Circuit, design: CircuitDesign? = null): String? {
        if (circuit.kind != CircuitKind.LOAD) return null
        val va = design?.loadVa ?: circuit.loadVa ?: return null
        return Wire.va(va) + if (design?.loadFromFedPanels == true) " (fed)" else ""
    }
}

/** The plain-text panel schedule shared by "Share as text". */
object ScheduleText {

    /**
     * @param panelIds panels to include, in order; null for every panel in the project
     * @param header company lines (`CompanyProfile.headerLines()`)
     * @param date already formatted
     * @param length formats a length the way the user chose to see lengths
     */
    fun render(
        project: Project,
        design: ProjectDesign,
        panelIds: List<String>? = null,
        header: List<String> = emptyList(),
        date: String,
        length: (Quantity) -> String = { it.value.toString() },
        /** Imperial or metric: the units of the pull and takeoff lines. */
        units: UnitSystem = UnitSystem.IMPERIAL,
    ): String {
        val panels = panelIds?.mapNotNull(project::panel) ?: project.panels
        return buildString {
            header.forEach(::appendLine)
            if (header.isNotEmpty()) appendLine()
            appendLine("Panel schedule · ${project.name}")
            appendLine(date)
            if (panels.isEmpty()) {
                appendLine()
                appendLine("No panels.")
            }
            panels.forEach { panel ->
                val pd = design.panels.firstOrNull { it.panel.id == panel.id }
                appendLine()
                append(panelBlock(project, design, panel, pd, length))
            }
            val extras = projectExtras(project, design, units)
            if (extras.isNotEmpty()) {
                appendLine()
                extras.forEach(::appendLine)
            }
            appendLine()
            append("— Construction Calc · wire sizes follow ${Code.label}; verify before installing.")
        }
    }

    /**
     * The project-wide part of the report: each run's pull (from its path) and the material takeoff
     * (wire by size, conduit by type and size). Empty when there's nothing to report.
     */
    fun projectExtras(project: Project, design: ProjectDesign, units: UnitSystem = UnitSystem.IMPERIAL): List<String> = buildList {
        val pulls = project.runs.filter { it.path.isNotEmpty() }.mapNotNull { run ->
            PullText.summary(PullAnalysis.analyze(run, design.runs[run.id], units), units)?.let { "  ${run.name}: $it" }
        }
        if (pulls.isNotEmpty()) {
            add("PULLS (manufacturer limits are guidance; see each pull report)")
            addAll(pulls)
        }
        val takeoff = MaterialTakeoffs.of(project, design)
        if (!takeoff.isEmpty) {
            if (pulls.isNotEmpty()) add("")
            addAll(ProjectText.takeoffText(takeoff, units).trimEnd().lines())
        }
    }

    internal fun panelBlock(
        project: Project,
        design: ProjectDesign,
        panel: Panel,
        pd: PanelDesign?,
        length: (Quantity) -> String,
    ): String = buildString {
        appendLine("PANEL ${panel.name}")
        if (panel.location.isNotBlank()) appendLine("Location: ${panel.location.trim()}")
        appendLine(PanelFacts.summary(panel))
        PanelFacts.fedFromText(project, panel)?.let { appendLine("Fed from: $it") }
        if (panel.note.isNotBlank()) appendLine("Note: ${panel.note.trim()}")
        appendLine()

        val groupNames = project.groups.associate { it.id to it.name }
        val runNames = project.runs.associate { it.id to it.name }
        val schedule = ScheduleLayout.build(panel)
        val owner = PanelLayout.occupancy(panel)

        var emptyRun = mutableListOf<Int>()
        fun flushEmpty() {
            when {
                emptyRun.isEmpty() -> Unit
                emptyRun.size <= 2 -> emptyRun.forEach { appendLine("$it · space") }
                else -> appendLine("${emptyRun.first()}–${emptyRun.last()} · space (${emptyRun.size})")
            }
            emptyRun = mutableListOf()
        }
        val printed = mutableSetOf<String>()
        for (slot in 1..panel.spaces) {
            val c = owner[slot]
            when {
                c == null -> {
                    if (emptyRun.isNotEmpty() && emptyRun.last() != slot - 1) flushEmpty()
                    emptyRun += slot
                }
                !printed.add(c.id) -> Unit // continuation of a multi-pole breaker
                else -> {
                    flushEmpty()
                    appendLine(circuitLine(c, panel, design, groupNames, runNames, length))
                }
            }
        }
        flushEmpty()
        schedule.unplaced.forEach { appendLine("Not on the panel: ${circuitLine(it, panel, design, groupNames, runNames, length)}") }

        if (pd != null) {
            appendLine()
            pd.phaseVa.forEach { (phase, va) ->
                appendLine("Phase ${phase.name}: ${Wire.va(va)} · ${Wire.amps(pd.phaseAmps.getValue(phase))}")
            }
            append("Total: ${Wire.va(pd.totalVa)}")
            LoadMath.imbalancePercent(pd.phaseVa.values)?.let { append(" · imbalance ${Wire.percent(it)}") }
            appendLine()
            val warnings = pd.conflicts + pd.notes.map { it.text }
            warnings.forEach { appendLine("⚠ $it") }
        }
    }

    private fun circuitLine(
        c: Circuit,
        panel: Panel,
        design: ProjectDesign,
        groupNames: Map<String, String>,
        runNames: Map<String, String>,
        length: (Quantity) -> String,
    ): String {
        val phases = PanelFacts.phaseText(c, panel.system.phases)
        val position = c.slots.joinToString(",")
        if (c.kind == CircuitKind.SPACE) {
            return "$position ($phases) · space" + c.description.takeIf { it.isNotBlank() }?.let { " · ${it.trim()}" }.orEmpty()
        }
        val d = design.circuits[c.id]
        val parts = mutableListOf("$position ($phases)", ScheduleLayout.breakerText(c))
        parts += when {
            c.kind == CircuitKind.SPARE -> "Spare" + c.description.takeIf { it.isNotBlank() }?.let { " – ${it.trim()}" }.orEmpty()
            c.description.isBlank() -> "(no description)"
            else -> c.description.trim()
        }
        PanelFacts.loadText(c, d)?.let(parts::add)
        d?.let(Wire::text)?.let(parts::add)
        d?.voltageDropPercent?.let { parts += "VD ${Wire.percent(it)}" }
        val groups = c.groupIds.mapNotNull(groupNames::get)
        if (groups.isNotEmpty()) parts += groups.joinToString(", ", prefix = "[", postfix = "]")
        val runs = c.runIds.mapNotNull(runNames::get)
        if (runs.isNotEmpty()) parts += "in " + runs.joinToString(", ")
        d?.lengthInches?.let { parts += "length " + length(Quantity.inches(com.thedirepack.constructioncalc.core.math.Rational.of(it))) }
        return parts.joinToString(" · ")
    }
}

/** Per-phase totals for display, in A-B-C order. */
fun PanelDesign.phaseRows(): List<Triple<Phase, Double, Double>> =
    phaseVa.keys.map { Triple(it, phaseVa.getValue(it), phaseAmps.getValue(it)) }

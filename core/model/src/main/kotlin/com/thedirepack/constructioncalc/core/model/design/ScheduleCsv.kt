package com.thedirepack.constructioncalc.core.model.design

import com.thedirepack.constructioncalc.core.measure.Decimals
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import com.thedirepack.constructioncalc.core.model.Circuit
import com.thedirepack.constructioncalc.core.model.CircuitKind
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.model.Project

/**
 * RFC-4180 CSV export for panel schedules and material takeoffs (pure Kotlin).
 */
object ScheduleCsv {
    private const val CRLF = "\r\n"
    private const val FEET_TO_METERS = 0.3048

    /**
     * Escapes a single CSV field per RFC 4180: fields containing a comma, double quote,
     * carriage return, or line feed are wrapped in double quotes with internal quotes doubled.
     */
    fun escape(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\r' || it == '\n' }
        return if (needsQuotes) "\"${value.replace("\"", "\"\"")}\"" else value
    }

    private fun row(vararg cells: String): String = cells.joinToString(",", transform = ::escape)

    /**
     * RFC-4180 CSV of the project's panel schedules (or [panelIds] if specified).
     */
    fun panelSchedule(
        project: Project,
        design: ProjectDesign = ProjectAnalysis.analyze(project),
        panelIds: List<String>? = null,
        units: UnitSystem = UnitSystem.IMPERIAL,
    ): String {
        val panels = panelIds?.mapNotNull(project::panel) ?: project.panels
        val groupNames = project.groups.associate { it.id to it.name }
        val runNames = project.runs.associate { it.id to it.name }
        val lengthUnit = if (units == UnitSystem.METRIC) "m" else "ft"
        val out = StringBuilder()
        out.append(
            row(
                "Panel",
                "System",
                "Slots",
                "Poles",
                "Phases",
                "Kind",
                "Breaker (A)",
                "Description",
                "Load (VA)",
                "Current (A)",
                "Continuous",
                "Neutral",
                "Nonlinear",
                "Wire",
                "Ground",
                "Length ($lengthUnit)",
                "Voltage Drop (%)",
                "Conduit / Runs",
                "Groups",
                "Warnings",
            ),
        ).append(CRLF)

        panels.forEach { panel ->
            panel.circuits.sortedBy { it.slot }.forEach { c ->
                out.append(circuitRow(panel, c, design, groupNames, runNames, units)).append(CRLF)
            }
        }
        return out.toString()
    }

    private fun circuitRow(
        panel: Panel,
        c: Circuit,
        design: ProjectDesign,
        groupNames: Map<String, String>,
        runNames: Map<String, String>,
        units: UnitSystem,
    ): String {
        val d = design.circuits[c.id]
        val phases = PanelLayout.phasesOf(c, panel.system.phases).joinToString("/") { it.name }
        val slots = c.slots.joinToString(",")
        val breaker = if (c.kind == CircuitKind.SPACE) "" else c.breakerAmps?.toString().orEmpty()
        val loadVa = if (c.kind == CircuitKind.LOAD) (d?.loadVa ?: c.loadVa)?.let { Decimals.trim(it, 1) }.orEmpty() else ""
        val amps = d?.amps?.let { Decimals.trim(it, 2) }.orEmpty()
        val wire = d?.let(Wire::text).orEmpty()
        val ground = d?.groundSize?.label.orEmpty()
        val lengthStr = d?.lengthInches?.let { inches ->
            val feet = inches / 12.0
            val shown = if (units == UnitSystem.METRIC) feet * FEET_TO_METERS else feet
            Decimals.trim(shown, 2)
        }.orEmpty()
        val vdStr = d?.voltageDropPercent?.let { Decimals.trim(it, 2) }.orEmpty()
        val conduitInfo = if (c.runIds.isNotEmpty()) {
            c.runIds.mapNotNull(runNames::get).joinToString("; ")
        } else {
            listOfNotNull(
                c.conduitTag.trim().takeIf { it.isNotEmpty() },
                listOfNotNull(c.conduitSize?.display, c.conduitType?.displayName)
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(" "),
            ).joinToString(" ")
        }
        val groups = c.groupIds.mapNotNull(groupNames::get).joinToString("; ")
        val warnings = d?.notes.orEmpty().filter { it.isWarning }.joinToString("; ") { it.text }
        return row(
            panel.name,
            panel.system.label,
            slots,
            c.poles.toString(),
            phases,
            c.kind.label,
            breaker,
            c.description.trim(),
            loadVa,
            amps,
            if (c.continuous) "Yes" else "No",
            if (c.neutral) "Yes" else "No",
            if (c.nonlinear) "Yes" else "No",
            wire,
            ground,
            lengthStr,
            vdStr,
            conduitInfo,
            groups,
            warnings,
        )
    }

    /**
     * RFC-4180 CSV of the project's material takeoff (wire and conduit quantities).
     */
    fun materialTakeoff(
        project: Project,
        design: ProjectDesign = ProjectAnalysis.analyze(project),
        units: UnitSystem = UnitSystem.IMPERIAL,
    ): String {
        val takeoff = MaterialTakeoffs.of(project, design)
        val unitLabel = if (units == UnitSystem.METRIC) "m" else "ft"
        val out = StringBuilder()
        out.append(row("Category", "Item", "Quantity", "Unit", "Runs")).append(CRLF)
        takeoff.wire.forEach { w ->
            val qty = if (units == UnitSystem.METRIC) w.feet * FEET_TO_METERS else w.feet
            out.append(row("Wire", w.label, Decimals.trim(qty, 1), unitLabel, w.runNames.joinToString("; "))).append(CRLF)
        }
        takeoff.conduit.forEach { c ->
            val qty = if (units == UnitSystem.METRIC) c.feet * FEET_TO_METERS else c.feet
            out.append(row("Conduit", c.label(units), Decimals.trim(qty, 1), unitLabel, c.runNames.joinToString("; "))).append(CRLF)
        }
        return out.toString()
    }
}

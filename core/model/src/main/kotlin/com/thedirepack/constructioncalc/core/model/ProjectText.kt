package com.thedirepack.constructioncalc.core.model

import com.thedirepack.constructioncalc.core.measure.LengthStyle
import com.thedirepack.constructioncalc.core.measure.MeasureFormat
import com.thedirepack.constructioncalc.core.measure.Measure
import com.thedirepack.constructioncalc.core.measure.Precision
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.design.MaterialTakeoff
import com.thedirepack.constructioncalc.core.model.design.MaterialTakeoffs
import com.thedirepack.constructioncalc.core.model.design.PanelDesign
import com.thedirepack.constructioncalc.core.model.design.PullAnalysis
import com.thedirepack.constructioncalc.core.model.design.PullText
import com.thedirepack.constructioncalc.core.model.design.PanelLayout
import com.thedirepack.constructioncalc.core.model.design.ProjectAnalysis
import com.thedirepack.constructioncalc.core.model.design.ProjectDesign
import com.thedirepack.constructioncalc.core.model.design.RunDesign
import com.thedirepack.constructioncalc.core.model.design.Wire
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Plain-text rendering of a project, for sharing by text message or email body. */
object ProjectText {

    /** [header]: company lines printed above the project (see CompanyProfile.headerLines). */
    fun render(
        project: Project,
        toolTitle: (String) -> String = { it },
        zone: ZoneId = ZoneId.systemDefault(),
        header: List<String> = emptyList(),
        /** How lengths read (the app passes the user's display settings). */
        length: (Quantity) -> String = ::defaultLength,
        /** Imperial or metric: the units of the design notes, pull summaries and takeoff. */
        units: UnitSystem = UnitSystem.IMPERIAL,
    ): String =
        buildString {
            val design = if (project.panels.isNotEmpty() || project.runs.any { it.raceway != null }) ProjectAnalysis.analyze(project, units) else null
            if (header.isNotEmpty()) {
                header.forEach(::appendLine)
                appendLine()
            }
            appendLine(project.name)
            if (project.note.isNotBlank()) appendLine(project.note.trim())
            project.runs.forEach { run ->
                appendLine()
                appendLine("■ ${run.name}")
                if (run.note.isNotBlank()) appendLine("  ${run.note.trim()}")
                design?.runs?.get(run.id)?.let { rd ->
                    conduitLine(rd, length)?.let { appendLine("  $it") }
                    pullLine(rd, units)?.let { appendLine("  $it") }
                }
                if (run.items.isEmpty()) appendLine("  (empty)")
                run.items.forEach { item -> append(renderItem(item, toolTitle, indent = "  ")) }
            }
            if (design != null && project.panels.isNotEmpty()) {
                appendLine()
                appendLine("PANELS")
                design.panels.forEach { pd -> append(renderPanel(project, pd, design)) }
            }
            design?.let { d ->
                val takeoff = MaterialTakeoffs.of(project, d)
                if (!takeoff.isEmpty) {
                    appendLine()
                    append(takeoffText(takeoff, units))
                }
            }
            appendLine()
            append("— Construction Calc · ").append(date(project.updatedAt, zone))
        }

    /** `Conduit: 1" EMT (auto) · 120' · 6 circuits · fill 32.1% of 40% · bends 180°`, warnings after it. */
    private fun conduitLine(rd: RunDesign, length: (Quantity) -> String): String? {
        val raceway = rd.run.raceway ?: return null
        val size = raceway.tradeSize ?: rd.conduit?.tradeSize
        val parts = buildList {
            add(listOfNotNull(size?.display, raceway.conduitType.displayName).joinToString(" ") + if (raceway.tradeSize == null) " (auto)" else "")
            raceway.length?.let { add(length(it.toQuantity())) }
            if (rd.circuits.isNotEmpty()) add("${rd.circuits.size} circuit" + if (rd.circuits.size == 1) "" else "s")
            rd.fillPercent?.let { add("fill ${Wire.percent(it)} of ${rd.fillLimitPercent}%") }
            rd.bendDegrees?.let { add("bends ${Wire.number(it, 1)}°") }
        }
        val warnings = rd.notes.filter { it.isWarning }.joinToString("") { "\n  ⚠ ${it.text}" }
        return "Conduit: " + parts.joinToString(" · ") + warnings
    }

    /** The run's pull, when it has a path: `Pull: 1,234 lb of 1,500 lb … ` plus its warnings not already on the conduit line. */
    private fun pullLine(rd: RunDesign, units: UnitSystem): String? {
        if (rd.run.path.isEmpty()) return null
        val result = PullAnalysis.analyze(rd.run, rd, units)
        val summary = PullText.summary(result, units) ?: return "Path: ${rd.run.path.size} segments (pull not calculated)"
        val shown = rd.notes.filter { it.isWarning }.map { it.text }.toSet()
        val warnings = PullText.warnings(result).filterNot { it in shown }.joinToString("") { "\n  ⚠ $it" }
        return "Pull: $summary$warnings"
    }

    /** MATERIAL TAKEOFF: wire by size and conduit by type, from every run's length. */
    fun takeoffText(takeoff: MaterialTakeoff, units: UnitSystem = UnitSystem.IMPERIAL): String = buildString {
        appendLine("MATERIAL TAKEOFF")
        takeoff.wire.forEach { appendLine("  Wire ${it.label}: ${runLength(it.feet, units)}") }
        takeoff.conduit.forEach { appendLine("  Conduit ${it.label(units)}: ${runLength(it.feet, units)}") }
        takeoff.notes.forEach { appendLine("  ⚠ ${it.text}") }
    }

    private fun renderPanel(project: Project, pd: PanelDesign, design: ProjectDesign): String = buildString {
        val p = pd.panel
        val facts = listOfNotNull(
            p.system.label,
            "${p.spaces} spaces",
            p.busAmps?.let { "$it A bus" },
            p.mainAmps?.let { "$it A main" } ?: "MLO",
            p.fedFrom?.let { ref ->
                project.panel(ref.panelId)?.let { src -> src.circuits.firstOrNull { it.id == ref.circuitId }?.let { "fed from ${src.name} ${PanelLayout.label(it)}" } }
            },
            p.location.takeIf { it.isNotBlank() },
        )
        appendLine("■ ${p.name} — ${facts.joinToString(", ")}")
        p.circuits.sortedBy { it.slot }.forEach { c ->
            val d = design.circuits[c.id]
            val parts = buildList {
                add(c.slots.joinToString(","))
                add(if (c.kind == CircuitKind.SPACE) "space" else "${c.breakerAmps ?: "—"}/${c.poles}")
                when {
                    c.kind == CircuitKind.SPARE -> add("Spare" + c.description.takeIf { it.isNotBlank() }?.let { " – ${it.trim()}" }.orEmpty())
                    c.description.isNotBlank() -> add(c.description.trim())
                }
                (d?.loadVa ?: c.loadVa)?.takeIf { c.kind == CircuitKind.LOAD }?.let { add(Wire.va(it) + if (d?.loadFromFedPanels == true) " (fed)" else "") }
                d?.let(Wire::text)?.let(::add)
                d?.voltageDropPercent?.let { add("VD ${Wire.percent(it)}") }
            }
            appendLine("  " + parts.joinToString(" · "))
        }
        appendLine("  " + pd.phaseVa.entries.joinToString(" · ") { (ph, va) -> "${ph.name} ${Wire.va(va)} (${Wire.amps(pd.phaseAmps.getValue(ph))})" } + " · total ${Wire.va(pd.totalVa)}")
        (pd.conflicts + pd.notes.filter { it.isWarning }.map { it.text }).forEach { appendLine("  ⚠ $it") }
    }

    /** A run length given in feet: `120 ft`, or `37 m`. */
    private fun runLength(feet: Double, units: UnitSystem) =
        Wire.number(Measure.RUN_LENGTH.fromBase(feet, units), 0) + " " + Measure.RUN_LENGTH.unit(units).symbol

    private fun defaultLength(q: Quantity): String = MeasureFormat.primary(q, Precision.SIXTEENTH, LengthStyle.FEET_INCHES)

    fun renderItem(item: ProjectItem, toolTitle: (String) -> String = { it }, indent: String = ""): String =
        buildString {
            append(indent).append("• ").append(item.title)
            append("  [").append(toolTitle(item.toolId)).appendLine("]")
            val body = item.summary.ifBlank {
                item.results.joinToString("\n") { "${it.label}: ${it.text}" }
            }
            body.lines().filter { it.isNotBlank() }.forEach { append(indent).append("   ").appendLine(it) }
            if (item.note.isNotBlank()) append(indent).append("   Note: ").appendLine(item.note.trim())
        }

    private fun date(epochMillis: Long, zone: ZoneId) =
        DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(zone).format(Instant.ofEpochMilli(epochMillis))
}

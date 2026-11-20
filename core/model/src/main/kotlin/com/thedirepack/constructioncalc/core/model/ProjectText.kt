package com.thedirepack.constructioncalc.core.model

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
    ): String =
        buildString {
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
                if (run.items.isEmpty()) appendLine("  (empty)")
                run.items.forEach { item -> append(renderItem(item, toolTitle, indent = "  ")) }
            }
            appendLine()
            append("— Construction Calc · ").append(date(project.updatedAt, zone))
        }

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

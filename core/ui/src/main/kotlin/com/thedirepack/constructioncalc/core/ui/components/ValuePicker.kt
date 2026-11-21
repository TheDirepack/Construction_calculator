package com.thedirepack.constructioncalc.core.ui.components

import com.thedirepack.constructioncalc.core.ui.spokenText
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.thedirepack.constructioncalc.core.ui.format
import com.thedirepack.constructioncalc.core.model.design.PublishedValues
import com.thedirepack.constructioncalc.core.model.design.ProjectAnalysis
import com.thedirepack.constructioncalc.core.data.activeTarget
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.ui.fractionText
import com.thedirepack.constructioncalc.core.ui.rememberAppSettings
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices
import com.thedirepack.constructioncalc.core.ui.tool.LocalTools
import com.thedirepack.constructioncalc.core.ui.tool.titleOf

private data class PickRow(val value: ResultValue, val source: String)

/**
 * Lists values of the given [kinds] from recent calculations in any tool and from the active
 * project, so results flow between tools without retyping.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ValuePickerSheet(
    title: String,
    kinds: Set<ValueKind>,
    onPick: (ResultValue) -> Unit,
    onDismiss: () -> Unit,
) {
    val services = LocalAppServices.current
    val tools = LocalTools.current
    val settings = rememberAppSettings()
    val recents by services.recents.recents.collectAsStateWithLifecycle()
    val projects by services.projects.projects.collectAsStateWithLifecycle()
    val project = settings.activeTarget(projects)?.project

    val drawingIds = remember(project?.drawings) {
        project?.drawings.orEmpty().flatMap { drawing -> drawing.measurements.map { it.id } }.toHashSet()
    }
    val recentRows = remember(recents, kinds, drawingIds) {
        recents.filter { it.value.kind in kinds && it.value.key !in drawingIds }.map {
            PickRow(it.value, listOf(it.toolTitle, it.context).filter(String::isNotBlank).joinToString(" · "))
        }
    }
    // What the project's electrical design works out (circuit currents, run lengths…), not only saved results.
    val designRows = remember(project, kinds, settings) {
        project?.takeIf { it.panels.isNotEmpty() || it.runs.any { r -> r.raceway != null } }
            ?.let { PublishedValues.all(ProjectAnalysis.analyze(it, settings.unitSystem), settings.unitSystem) { q -> settings.format(q) } }
            .orEmpty().filter { it.kind in kinds }.map { PickRow(it, "Design") }
    }
    val projectRows = remember(project, kinds) {
        project?.allResults().orEmpty().filter { it.value.kind in kinds }.map {
            PickRow(it.value, "${it.run.name} › ${it.item.title} · ${tools.titleOf(it.item.toolId)}")
        }
    }
    val drawingRows = remember(project?.drawings, kinds, settings) {
        if (ValueKind.LENGTH !in kinds) emptyList()
        else project?.drawings.orEmpty().flatMap { drawing ->
            drawing.measurements.mapNotNull { measurement ->
                drawing.length(measurement)?.let { length ->
                    PickRow(
                        ResultValue.of(measurement.id, "Plan distance", length, "≈ " + settings.format(length)),
                        "${drawing.name} · Page ${measurement.page + 1}",
                    )
                }
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Use a value for “$title”",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        LazyColumn(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            if (recentRows.isEmpty() && projectRows.isEmpty() && designRows.isEmpty() && drawingRows.isEmpty()) {
                item {
                    Text(
                        "No matching values yet. Results from other tools appear here after you calculate them.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            if (recentRows.isNotEmpty()) {
                item { SectionLabel("Recent") }
                items(recentRows) { PickItem(it) { onPick(it.value) } }
            }
            if (projectRows.isNotEmpty()) {
                item { SectionLabel("In ${project?.name}") }
                items(projectRows) { PickItem(it) { onPick(it.value) } }
            }
            if (drawingRows.isNotEmpty()) {
                item { SectionLabel("From drawings in ${project?.name}") }
                items(drawingRows) { PickItem(it) { onPick(it.value) } }
            }
            if (designRows.isNotEmpty()) {
                item { SectionLabel("From the design in ${project?.name}") }
                items(designRows) { PickItem(it) { onPick(it.value) } }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) = Text(
    text,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
)

@Composable
private fun PickItem(row: PickRow, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = "${spokenText(row.value.text)}, ${row.value.label}, from ${row.source}" },
    ) {
        Text(fractionText(row.value.text), style = MaterialTheme.typography.titleMedium)
        Text(
            "${row.value.label} — ${row.source}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

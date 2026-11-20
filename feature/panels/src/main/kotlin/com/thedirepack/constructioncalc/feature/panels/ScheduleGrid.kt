package com.thedirepack.constructioncalc.feature.panels

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.model.Circuit
import com.thedirepack.constructioncalc.core.model.CircuitKind
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.feature.panels.domain.PanelFacts
import com.thedirepack.constructioncalc.feature.panels.domain.ScheduleBlock
import com.thedirepack.constructioncalc.feature.panels.domain.ScheduleLayout
import com.thedirepack.constructioncalc.core.model.design.Wire

private val RowHeight = 62.dp
private val NumberWidth = 22.dp
private val PhaseWidth = 22.dp

/**
 * The classic two-column panel schedule: odd positions down the left, even down the right, one row
 * per pair, each row's phase in the middle. A multi-pole breaker is one tall cell covering its rows.
 * Tap a breaker to edit it, or a free position to add a circuit there.
 *
 * @param filterGroupId when set, circuits outside that group are dimmed (their text stays readable)
 */
@Composable
internal fun ScheduleGrid(
    ctx: PanelsContext,
    panel: Panel,
    filterGroupId: String?,
    onAddAt: (slot: Int) -> Unit,
    onOpen: (Circuit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val schedule = androidx.compose.runtime.remember(panel) { ScheduleLayout.build(panel) }
    val rows = schedule.rows.size
    Row(modifier.fillMaxWidth().height(RowHeight * rows)) {
        NumberColumn(schedule.rows.map { it.leftSlot as Int? })
        BlockColumn(ctx, panel, schedule.left, rows, filterGroupId, Modifier.weight(1f), onAddAt, onOpen)
        Column(Modifier.width(PhaseWidth)) {
            schedule.rows.forEach { r ->
                Box(Modifier.height(RowHeight).fillMaxWidth().semantics { contentDescription = "Phase ${r.phase.name}" }, contentAlignment = Alignment.Center) {
                    Text(r.phase.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
        BlockColumn(ctx, panel, schedule.right, rows, filterGroupId, Modifier.weight(1f), onAddAt, onOpen)
        NumberColumn(schedule.rows.map { it.rightSlot })
    }
}

@Composable
private fun NumberColumn(slots: List<Int?>) {
    Column(Modifier.width(NumberWidth)) {
        slots.forEach { slot ->
            Box(Modifier.height(RowHeight).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (slot != null) Text(slot.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun BlockColumn(
    ctx: PanelsContext,
    panel: Panel,
    blocks: List<ScheduleBlock>,
    rows: Int,
    filterGroupId: String?,
    modifier: Modifier,
    onAddAt: (Int) -> Unit,
    onOpen: (Circuit) -> Unit,
) {
    Column(modifier) {
        blocks.forEach { block ->
            Box(Modifier.height(RowHeight * block.rowSpan).fillMaxWidth().padding(1.dp)) {
                val c = block.circuit
                if (c == null) {
                    EmptyCell(block.slot, enabled = !ctx.readOnly, onClick = { onAddAt(block.slot) })
                } else {
                    val dim = filterGroupId != null && filterGroupId !in c.groupIds
                    CircuitCell(ctx, panel, c, block.rowSpan, dim, onClick = { onOpen(c) })
                }
            }
        }
        val missing = rows - blocks.sumOf { it.rowSpan }
        if (missing > 0) Spacer(Modifier.height(RowHeight * missing))
    }
}

@Composable
private fun EmptyCell(slot: Int, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxSize().semantics {
            contentDescription = if (enabled) "Position $slot, space. Add a circuit." else "Position $slot, space"
        },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                if (enabled) "space  +" else "space",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CircuitCell(ctx: PanelsContext, panel: Panel, c: Circuit, rowSpan: Int, dim: Boolean, onClick: () -> Unit) {
    val d = ctx.design.circuits[c.id]
    val groups = c.groupIds.mapNotNull(ctx::group)
    val isSpace = c.kind == CircuitKind.SPACE
    val wire = d?.let(Wire::text)
    val target = ctx.project.design.voltageDropTargetPercent
    val vdWarn = d?.voltageDropPercent?.takeIf { it > target + 1e-9 }
    val title = if (isSpace) "space" else ScheduleLayout.breakerText(c)
    val load = PanelFacts.loadText(c, d)
    val description = when {
        c.kind == CircuitKind.SPARE -> "Spare" + c.description.takeIf { it.isNotBlank() }?.let { " – ${it.trim()}" }.orEmpty()
        c.description.isBlank() -> if (isSpace) "" else "(no description)"
        else -> c.description.trim()
    }
    val spoken = buildString {
        append("Position ${c.slots.joinToString(" and ")}, ")
        if (isSpace) append("space") else append("${c.breakerAmps ?: "no"} amp, ${c.poles} pole breaker")
        if (description.isNotBlank()) append(", $description")
        if (load != null) append(", $load")
        if (wire != null) append(", wire $wire")
        if (groups.isNotEmpty()) append(", groups ${groups.joinToString { it.name }}")
        if (vdWarn != null) append(", voltage drop ${Wire.percent(vdWarn)} is over the target")
        append(". Tap to edit.")
    }
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraSmall,
        color = when (c.kind) {
            CircuitKind.LOAD -> MaterialTheme.colorScheme.secondaryContainer
            CircuitKind.SPARE -> MaterialTheme.colorScheme.surfaceContainerHigh
            CircuitKind.SPACE -> MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(1.dp, if (rowSpan > 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxSize().alpha(if (dim) 0.35f else 1f).semantics(mergeDescendants = true) { contentDescription = spoken },
    ) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(title, load).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                groups.take(2).forEach { GroupTag(it, Modifier.padding(start = 3.dp)) }
            }
            if (description.isNotBlank()) {
                Text(description, style = MaterialTheme.typography.bodySmall, maxLines = if (rowSpan > 1) 3 else 1, overflow = TextOverflow.Ellipsis)
            }
            if (wire != null || vdWarn != null) {
                Text(
                    listOfNotNull(wire, vdWarn?.let { "⚠ VD ${Wire.percent(it)}" }).joinToString("  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (vdWarn != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

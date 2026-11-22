package com.thedirepack.constructioncalc.feature.drawings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.plans.SnapTarget
import com.thedirepack.constructioncalc.feature.drawings.domain.CalibrationStatus
import com.thedirepack.constructioncalc.core.ui.R as CoreR

/** What the toolbar shows; the editor owns the state and handles the callbacks. */
internal data class ToolbarState(
    val page: Int,
    val pageCount: Int,
    val editable: Boolean,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val mode: DrawingMode,
    val scaleStatus: CalibrationStatus,
    /** Null when the active tool doesn't snap; otherwise whether snapping is on for it. */
    val snapEnabled: Boolean?,
    val snapReady: Boolean,
    val snapTarget: SnapTarget,
    val showCalibrationLayer: Boolean,
    val busy: Boolean,
)

internal class ToolbarActions(
    val onPage: (Int) -> Unit,
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
    val onMode: (DrawingMode) -> Unit,
    val onScale: () -> Unit,
    val onToggleSnap: () -> Unit,
    val onToggleCalibrationLayer: () -> Unit,
    val onTakeoff: () -> Unit,
    val onPageItems: () -> Unit,
    val onExportPdf: () -> Unit,
    val onExportJson: () -> Unit,
    val onSettings: () -> Unit,
)

/**
 * Two fixed rows that fit a phone: page navigation, undo/redo and the overflow menu on the first;
 * the active tool, its snapping and the scale status on the second. Nothing scrolls sideways, and
 * the less-used layer, list and export actions live in the overflow menu.
 */
@Composable
internal fun EditorToolbar(state: ToolbarState, actions: ToolbarActions) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { actions.onPage(state.page - 1) }, enabled = state.page > 0) {
                Icon(painterResource(R.drawable.ic_plan_prev), contentDescription = "Previous page")
            }
            Text(
                "${state.page + 1} / ${state.pageCount}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.semantics { contentDescription = "Page ${state.page + 1} of ${state.pageCount}" },
            )
            IconButton(onClick = { actions.onPage(state.page + 1) }, enabled = state.page + 1 < state.pageCount) {
                Icon(painterResource(R.drawable.ic_plan_next), contentDescription = "Next page")
            }
            Spacer(Modifier.weight(1f))
            if (state.editable) {
                IconButton(onClick = actions.onUndo, enabled = state.canUndo) {
                    Icon(painterResource(R.drawable.ic_plan_undo), contentDescription = "Undo")
                }
                IconButton(onClick = actions.onRedo, enabled = state.canRedo) {
                    Icon(painterResource(R.drawable.ic_plan_redo), contentDescription = "Redo")
                }
            }
            OverflowMenu(state, actions)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (state.editable) {
                ToolPicker(state.mode, actions.onMode, Modifier.weight(1f))
            } else {
                Text("View only", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            }
            state.snapEnabled?.let { enabled ->
                FilterChip(
                    selected = enabled,
                    onClick = actions.onToggleSnap,
                    label = {
                        Text(
                            when {
                                !enabled -> "Snap off"
                                !state.snapReady -> "Snap …"
                                state.snapTarget == SnapTarget.EDGE -> "Snap edge"
                                else -> "Snap center"
                            },
                            maxLines = 1,
                        )
                    },
                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                        stateDescription = if (enabled) "Snapping on" else "Snapping off"
                    },
                )
            }
            TextButton(
                onClick = actions.onScale,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Scale: ${state.scaleStatus.label}. Open calibration." },
            ) {
                Text(
                    scaleLabel(state.scaleStatus),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (state.scaleStatus == CalibrationStatus.NEEDS_REVIEW) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

internal fun scaleLabel(status: CalibrationStatus): String = when (status) {
    CalibrationStatus.UNCALIBRATED -> "Set scale"
    CalibrationStatus.PRESET -> "Scale: preset"
    CalibrationStatus.FITTED -> "Scale: fitted"
    CalibrationStatus.VERIFIED -> "Scale ✓"
    CalibrationStatus.NEEDS_REVIEW -> "Scale: review"
}

@Composable
private fun ToolPicker(mode: DrawingMode, onMode: (DrawingMode) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 120.dp).semantics {
                contentDescription = "Tool: ${mode.title}. Change tool."
            },
        ) {
            Text("Tool: ${mode.title}", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(painterResource(R.drawable.ic_plan_expand), contentDescription = null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ToolGroup.entries.forEachIndexed { index, group ->
                if (index > 0) HorizontalDivider()
                Text(
                    group.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
                DrawingMode.entries.filter { it.group == group }.forEach { tool ->
                    DropdownMenuItem(
                        text = { Text(if (tool == mode) "${tool.title} ✓" else tool.title) },
                        onClick = {
                            open = false
                            onMode(tool)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun OverflowMenu(state: ToolbarState, actions: ToolbarActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = !state.busy) {
            Icon(painterResource(CoreR.drawable.ic_more_vert), contentDescription = if (state.busy) "Saving…" else "More drawing options")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val close = { open = false }
            DropdownMenuItem(
                text = { Text("Show calibration dimensions") },
                trailingIcon = { Checkbox(checked = state.showCalibrationLayer, onCheckedChange = null) },
                onClick = {
                    open = false
                    actions.onToggleCalibrationLayer()
                },
            )
            MenuItem("Takeoff totals", close, actions.onTakeoff)
            MenuItem("Page measurements and markups", close, actions.onPageItems)
            HorizontalDivider()
            MenuItem("Export annotated PDF", close, actions.onExportPdf)
            MenuItem("Export drawing JSON", close, actions.onExportJson)
            HorizontalDivider()
            MenuItem("Snapping and symbol settings", close, actions.onSettings)
        }
    }
}

@Composable
private fun MenuItem(label: String, close: () -> Unit, action: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = {
        close()
        action()
    })
}

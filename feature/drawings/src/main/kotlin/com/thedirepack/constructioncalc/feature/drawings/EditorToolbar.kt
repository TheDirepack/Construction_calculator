package com.thedirepack.constructioncalc.feature.drawings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.plans.SnapTarget
import com.thedirepack.constructioncalc.feature.drawings.domain.CalibrationStatus
import com.thedirepack.constructioncalc.core.ui.R as CoreR

@Composable
internal fun EditorHeader(
    sheet: String,
    onSheets: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSettings: () -> Unit,
    overflow: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onSheets, modifier = Modifier.weight(1f)) {
            Text(sheet, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(painterResource(R.drawable.ic_plan_expand), "Browse sheets", Modifier.size(18.dp))
        }
        IconButton(onClick = onUndo, enabled = canUndo) {
            Icon(painterResource(R.drawable.ic_plan_undo), "Undo")
        }
        IconButton(onClick = onRedo, enabled = canRedo) {
            Icon(painterResource(R.drawable.ic_plan_redo), "Redo")
        }
        overflow()
        IconButton(onClick = onSettings) {
            Icon(painterResource(R.drawable.ic_plan_settings), "Drawing settings")
        }
    }
}

@Composable
internal fun EditorStatusBar(
    revisionLabel: String,
    superseded: Boolean,
    onRevision: () -> Unit,
    scaleLabel: String,
    scaleStatus: CalibrationStatus,
    onScale: () -> Unit,
    snapEnabled: Boolean,
    snapTarget: SnapTarget,
    onSnapSelect: (enabled: Boolean, target: SnapTarget) -> Unit,
    returnLabel: String? = null,
    onReturn: (() -> Unit)? = null,
) {
    var snapMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (returnLabel != null && onReturn != null) {
            AssistChip(
                onClick = onReturn,
                label = { Text("← $returnLabel", maxLines = 1) },
            )
        }
        AssistChip(
            onClick = onRevision,
            label = {
                Text(
                    revisionLabel,
                    maxLines = 1,
                    color = if (superseded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            },
        )
        AssistChip(
            onClick = onScale,
            label = {
                Text(
                    scaleLabel,
                    maxLines = 1,
                    color = when (scaleStatus) {
                        CalibrationStatus.VERIFIED -> MaterialTheme.colorScheme.primary
                        CalibrationStatus.NEEDS_REVIEW -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            },
        )
        Box {
            val snapText = when {
                !snapEnabled -> "Snap · Off"
                snapTarget == SnapTarget.CENTER -> "Snap · Center"
                else -> "Snap · Edge"
            }
            FilterChip(
                selected = snapEnabled,
                onClick = { snapMenu = true },
                label = { Text(snapText, maxLines = 1) },
            )
            DropdownMenu(snapMenu, onDismissRequest = { snapMenu = false }) {
                MenuItem("Snap to line center", { snapMenu = false }) { onSnapSelect(true, SnapTarget.CENTER) }
                MenuItem("Snap to nearest edge", { snapMenu = false }) { onSnapSelect(true, SnapTarget.EDGE) }
                MenuItem("Snap off", { snapMenu = false }) { onSnapSelect(false, snapTarget) }
            }
        }
    }
}

@Composable
internal fun EditorTools(
    mode: DrawingMode,
    vertical: Boolean,
    onMode: (DrawingMode) -> Unit,
) {
    val primary = listOf(DrawingMode.Adjust, DrawingMode.Measure, DrawingMode.Route, DrawingMode.Symbol)
    val markup = listOf(DrawingMode.Pen, DrawingMode.Highlight)
    var showMore by remember { mutableStateOf(false) }
    val controls: @Composable () -> Unit = {
        for (tool in primary) {
            ToolChip(tool.title, mode == tool) { onMode(tool) }
        }
        Box {
            ToolChip(if (mode in primary || mode in markup) "Markup" else mode.title, mode !in primary && mode !in markup) { showMore = true }
            DropdownMenu(showMore, onDismissRequest = { showMore = false }) {
                DrawingMode.entries.filter { it.group == ToolGroup.MARKUP && it !in primary && it !in markup }.forEach { tool ->
                    MenuItem(tool.title, { showMore = false }) { onMode(tool) }
                }
            }
        }
        for (tool in markup) {
            ToolChip(tool.title, mode == tool) { onMode(tool) }
        }
    }
    Surface(tonalElevation = 2.dp) {
        if (vertical) {
            Column(Modifier.widthIn(max = 136.dp).verticalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally) { controls() }
        } else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically) { controls() }
        }
    }
}

@Composable
private fun ToolChip(label: String, selected: Boolean, action: () -> Unit) {
    FilterChip(selected, action, label = { Text(label, maxLines = 1) }, modifier = Modifier.heightIn(min = 48.dp))
}

@Composable
internal fun EditorOptions(
    editable: Boolean,
    busy: Boolean,
    showCalibration: Boolean,
    scale: CalibrationStatus,
    onScale: () -> Unit,
    onMode: (DrawingMode) -> Unit,
    onRotate: () -> Unit,
    onToggleCalibration: () -> Unit,
    highlightsVisible: Boolean,
    onToggleHighlights: () -> Unit,
    onTakeoff: () -> Unit,
    onPageItems: () -> Unit,
    onExportSheet: () -> Unit,
    onExportPdf: () -> Unit,
    onExportJson: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = !busy) {
            Icon(painterResource(CoreR.drawable.ic_more_vert), if (busy) "Saving…" else "More drawing options")
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            val close = { open = false }
            MenuItem("Scale · ${scale.label}", close, onScale)
            if (editable) {
                MenuItem("Calibrate with known length", close) { onMode(DrawingMode.Reference) }
                MenuItem("Draw scale zone", close) { onMode(DrawingMode.Zone) }
            }
            DropdownMenuItem(
                text = { Text("Show calibration dimensions") },
                trailingIcon = { Checkbox(showCalibration, onCheckedChange = null) },
                onClick = { close(); onToggleCalibration() },
            )
            MenuItem(if (highlightsVisible) "Hide highlights" else "Show highlights", close, onToggleHighlights)
            MenuItem("Rotate view", close, onRotate)
            MenuItem("Takeoff totals", close, onTakeoff)
            MenuItem("Page measurements and markups", close, onPageItems)
            HorizontalDivider()
            MenuItem("Export or share this sheet", close, onExportSheet)
            MenuItem("Export annotated PDF", close, onExportPdf)
            MenuItem("Export drawing JSON", close, onExportJson)
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

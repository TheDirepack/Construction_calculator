package com.thedirepack.constructioncalc.feature.drawings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.plans.SnapTarget
import com.thedirepack.constructioncalc.feature.drawings.domain.CalibrationStatus
import com.thedirepack.constructioncalc.core.ui.R as CoreR

internal fun DrawingMode.iconRes(): Int = when (this) {
    DrawingMode.Navigate -> R.drawable.ic_plan_tool_pan
    DrawingMode.Measure -> R.drawable.ic_plan_tool_measure
    DrawingMode.Route -> R.drawable.ic_plan_tool_route
    DrawingMode.Adjust -> R.drawable.ic_plan_tool_select
    DrawingMode.Reference -> R.drawable.ic_plan_tool_calibrate
    DrawingMode.Zone -> R.drawable.ic_plan_tool_zone
    DrawingMode.Pen -> R.drawable.ic_plan_tool_pen
    DrawingMode.Highlight -> R.drawable.ic_plan_tool_highlight
    DrawingMode.Eraser -> R.drawable.ic_plan_tool_eraser
    DrawingMode.Arrow -> R.drawable.ic_plan_tool_arrow
    DrawingMode.Line -> R.drawable.ic_plan_tool_line
    DrawingMode.Rectangle -> R.drawable.ic_plan_tool_rect
    DrawingMode.Text -> R.drawable.ic_plan_tool_text
    DrawingMode.Symbol -> R.drawable.ic_plan_tool_symbol
    DrawingMode.Callout -> R.drawable.ic_plan_tool_callout
    DrawingMode.Dimension -> R.drawable.ic_plan_tool_dimension
    DrawingMode.Cloud -> R.drawable.ic_plan_tool_cloud
    DrawingMode.CloudCallout -> R.drawable.ic_plan_tool_cloud_callout
    DrawingMode.Photo -> R.drawable.ic_plan_tool_photo
    DrawingMode.DetailLink -> R.drawable.ic_plan_tool_detail_link
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolTooltipIconButton(
    label: String,
    iconRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    badgeDotColor: Long? = null,
    expandBadge: Boolean = false,
    tint: Color? = null,
    buttonSize: Dp = 48.dp,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        val defaultTint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        val effectiveTint = (tint ?: defaultTint).let { if (enabled) it else it.copy(alpha = 0.38f) }
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            contentColor = effectiveTint,
            modifier = modifier.size(buttonSize),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = label,
                    modifier = Modifier.size(22.dp),
                )
                if (badgeDotColor != null) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(7.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color((badgeDotColor and 0xFFFFFFL) or 0xFF000000L)),
                    )
                }
                if (expandBadge) {
                    Icon(
                        painterResource(R.drawable.ic_plan_expand),
                        contentDescription = null,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(10.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorHeader(
    sheetTitle: String,
    pageCounter: String,
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
            Text(
                sheetTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(" · $pageCounter", maxLines = 1)
            Icon(painterResource(R.drawable.ic_plan_expand), "Browse sheets", Modifier.size(18.dp))
        }
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
            tooltip = { PlainTooltip { Text("Undo") } },
            state = rememberTooltipState(),
        ) {
            IconButton(onClick = onUndo, enabled = canUndo) {
                Icon(painterResource(R.drawable.ic_plan_undo), "Undo")
            }
        }
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
            tooltip = { PlainTooltip { Text("Redo") } },
            state = rememberTooltipState(),
        ) {
            IconButton(onClick = onRedo, enabled = canRedo) {
                Icon(painterResource(R.drawable.ic_plan_redo), "Redo")
            }
        }
        overflow()
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
            tooltip = { PlainTooltip { Text("Drawing settings") } },
            state = rememberTooltipState(),
        ) {
            IconButton(onClick = onSettings) {
                Icon(painterResource(R.drawable.ic_plan_settings), "Drawing settings")
            }
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
    scaleLocked: Boolean,
    editable: Boolean,
    onToggleScaleLock: () -> Unit,
    snapEnabled: Boolean,
    snapTarget: SnapTarget,
    onSnapSelect: (enabled: Boolean, target: SnapTarget) -> Unit,
    stylusOnlyDraw: Boolean,
    stylusPressure: Boolean,
    stylusBarrelAction: StylusBarrelAction,
    twoFingerUndo: Boolean,
    onToggleStylusOnly: (Boolean) -> Unit,
    onToggleStylusPressure: (Boolean) -> Unit,
    onStylusBarrelAction: (StylusBarrelAction) -> Unit,
    onToggleTwoFingerUndo: (Boolean) -> Unit,
    returnLabel: String? = null,
    onReturn: (() -> Unit)? = null,
) {
    var snapMenu by remember { mutableStateOf(false) }
    var stylusMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 0.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (returnLabel != null && onReturn != null) {
            AssistChip(
                onClick = onReturn,
                leadingIcon = {
                    Icon(painterResource(R.drawable.ic_plan_prev), contentDescription = "Return", Modifier.size(16.dp))
                },
                label = { Text(returnLabel, maxLines = 1) },
            )
        }
        AssistChip(
            onClick = onRevision,
            leadingIcon = {
                Icon(
                    painterResource(R.drawable.ic_plan_revision),
                    contentDescription = "Sheet revision",
                    tint = if (superseded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            },
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
            leadingIcon = {
                val color = when (scaleStatus) {
                    CalibrationStatus.VERIFIED -> MaterialTheme.colorScheme.primary
                    CalibrationStatus.NEEDS_REVIEW -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Icon(
                    painterResource(R.drawable.ic_plan_tool_calibrate),
                    contentDescription = "Scale status",
                    tint = color,
                    modifier = Modifier.size(16.dp),
                )
            },
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
        FilterChip(
            selected = scaleLocked,
            onClick = onToggleScaleLock,
            enabled = editable,
            leadingIcon = {
                Icon(
                    painterResource(if (scaleLocked) R.drawable.ic_plan_lock else R.drawable.ic_plan_lock_open),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
            label = { Text(if (scaleLocked) "Locked" else "Unlocked", maxLines = 1) },
            modifier = Modifier.semantics {
                contentDescription = if (scaleLocked) "Scale locked. Tap to unlock" else "Scale unlocked. Tap to lock"
            },
        )
        Box {
            val snapShort = when {
                !snapEnabled -> "Off"
                snapTarget == SnapTarget.CENTER -> "Center"
                else -> "Edge"
            }
            FilterChip(
                selected = snapEnabled,
                onClick = { snapMenu = true },
                leadingIcon = {
                    Icon(painterResource(R.drawable.ic_plan_snap), contentDescription = null, Modifier.size(16.dp))
                },
                label = { Text(snapShort, maxLines = 1) },
                modifier = Modifier.semantics { contentDescription = "Snap mode: $snapShort" },
            )
            DropdownMenu(snapMenu, onDismissRequest = { snapMenu = false }) {
                MenuItem("Snap to line center", { snapMenu = false }) { onSnapSelect(true, SnapTarget.CENTER) }
                MenuItem("Snap to nearest edge", { snapMenu = false }) { onSnapSelect(true, SnapTarget.EDGE) }
                MenuItem("Snap off", { snapMenu = false }) { onSnapSelect(false, snapTarget) }
            }
        }
        Box {
            val pencilLabel = if (stylusOnlyDraw) "Pencil" else "Stylus"
            FilterChip(
                selected = stylusOnlyDraw,
                onClick = { stylusMenu = true },
                leadingIcon = {
                    Icon(painterResource(R.drawable.ic_plan_stylus), contentDescription = null, Modifier.size(16.dp))
                },
                label = { Text(pencilLabel, maxLines = 1) },
                modifier = Modifier.semantics {
                    contentDescription = if (stylusOnlyDraw) "Pencil mode on (finger pans)" else "Stylus and touch input"
                },
            )
            DropdownMenu(stylusMenu, onDismissRequest = { stylusMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Pencil mode (finger pans)") },
                    trailingIcon = { Checkbox(stylusOnlyDraw, onCheckedChange = null) },
                    onClick = {
                        stylusMenu = false
                        onToggleStylusOnly(!stylusOnlyDraw)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Pressure sensitivity") },
                    trailingIcon = { Checkbox(stylusPressure, onCheckedChange = null) },
                    onClick = {
                        stylusMenu = false
                        onToggleStylusPressure(!stylusPressure)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Two-finger tap undo") },
                    trailingIcon = { Checkbox(twoFingerUndo, onCheckedChange = null) },
                    onClick = {
                        stylusMenu = false
                        onToggleTwoFingerUndo(!twoFingerUndo)
                    },
                )
                HorizontalDivider()
                for (action in StylusBarrelAction.entries) {
                    DropdownMenuItem(
                        text = { Text(if (stylusBarrelAction == action) "• Barrel: ${action.label}" else "Barrel: ${action.label}") },
                        onClick = {
                            stylusMenu = false
                            onStylusBarrelAction(action)
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun EditorTools(
    mode: DrawingMode,
    vertical: Boolean,
    pinnedTools: List<DrawingMode>,
    collapsed: Boolean,
    onCollapsedChange: (Boolean) -> Unit,
    scaleLocked: Boolean = false,
    toolColors: Map<DrawingMode, Long> = emptyMap(),
    onMode: (DrawingMode) -> Unit,
    onCustomizeToolbar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val effectivePinned = pinnedTools.ifEmpty { DrawingsSettings.DEFAULT_PINNED_TOOLS }
    val remainingTools = remember(effectivePinned) {
        DrawingMode.entries.filter { it !in effectivePinned }
    }
    val commonRemaining = remember(remainingTools) {
        remainingTools.filter { it != DrawingMode.Navigate && it != DrawingMode.Reference && it != DrawingMode.Zone }
    }
    val utilityRemaining = remember(remainingTools) {
        listOf(DrawingMode.Navigate, DrawingMode.Reference, DrawingMode.Zone).filter { it in remainingTools }
    }
    val overflowSelected = mode in remainingTools
    var showMore by remember { mutableStateOf(false) }

    if (collapsed) {
        Surface(
            onClick = { onCollapsedChange(false) },
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
            modifier = modifier.semantics { contentDescription = "Expand toolbar (${mode.title})" },
        ) {
            if (vertical) {
                Column(
                    Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        painterResource(mode.iconRes()),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Icon(
                        painterResource(R.drawable.ic_plan_prev),
                        contentDescription = "Expand toolbar",
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(mode.iconRes()),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(mode.title, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    Icon(
                        painterResource(R.drawable.ic_plan_collapse_up),
                        contentDescription = "Expand toolbar",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        return
    }

    val buttonSize = if (vertical) 44.dp else 40.dp

    val pinnedButtons: @Composable () -> Unit = {
        for (tool in effectivePinned) {
            val locked = scaleLocked && (tool == DrawingMode.Reference || tool == DrawingMode.Zone)
            ToolTooltipIconButton(
                label = if (locked) "${tool.title} (locked)" else tool.title,
                iconRes = tool.iconRes(),
                selected = mode == tool,
                badgeDotColor = toolColors[tool],
                tint = if (locked) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f) else null,
                buttonSize = buttonSize,
                onClick = { onMode(tool) },
            )
        }
    }

    val moreButton: @Composable () -> Unit = {
        Box {
            val overflowTool = mode.takeIf { overflowSelected }
            ToolTooltipIconButton(
                label = overflowTool?.title ?: "More tools",
                iconRes = overflowTool?.iconRes() ?: R.drawable.ic_plan_more_markup,
                selected = overflowSelected,
                badgeDotColor = overflowTool?.let { toolColors[it] },
                expandBadge = true,
                buttonSize = buttonSize,
                onClick = { showMore = true },
            )
            DropdownMenu(showMore, onDismissRequest = { showMore = false }) {
                for (tool in commonRemaining) {
                    DropdownMenuItem(
                        text = { Text(if (mode == tool) "• ${tool.title}" else tool.title) },
                        leadingIcon = {
                            Icon(painterResource(tool.iconRes()), contentDescription = null, modifier = Modifier.size(20.dp))
                        },
                        onClick = {
                            showMore = false
                            onMode(tool)
                        },
                    )
                }
                if (commonRemaining.isNotEmpty() && utilityRemaining.isNotEmpty()) {
                    HorizontalDivider()
                }
                for (tool in utilityRemaining) {
                    val locked = scaleLocked && (tool == DrawingMode.Reference || tool == DrawingMode.Zone)
                    val titleText = if (tool == DrawingMode.Navigate) "${tool.title} only" else tool.title
                    DropdownMenuItem(
                        text = { Text(if (mode == tool) "• $titleText" else titleText) },
                        leadingIcon = {
                            Icon(painterResource(tool.iconRes()), contentDescription = null, modifier = Modifier.size(20.dp))
                        },
                        trailingIcon = if (locked) {
                            { Icon(painterResource(R.drawable.ic_plan_lock), contentDescription = "Scale locked", modifier = Modifier.size(16.dp)) }
                        } else null,
                        onClick = {
                            showMore = false
                            onMode(tool)
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Customize toolbar…") },
                    leadingIcon = {
                        Icon(painterResource(R.drawable.ic_plan_settings), contentDescription = null, modifier = Modifier.size(20.dp))
                    },
                    onClick = {
                        showMore = false
                        onCustomizeToolbar()
                    },
                )
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        modifier = modifier,
    ) {
        if (vertical) {
            Column(
                Modifier.widthIn(max = 56.dp).padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    pinnedButtons()
                }
                moreButton()
                ToolTooltipIconButton(
                    label = "Collapse toolbar",
                    iconRes = R.drawable.ic_plan_next,
                    buttonSize = 36.dp,
                    onClick = { onCollapsedChange(true) },
                )
            }
        } else {
            Row(
                Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    pinnedButtons()
                }
                moreButton()
                ToolTooltipIconButton(
                    label = "Collapse toolbar",
                    iconRes = R.drawable.ic_plan_expand,
                    buttonSize = 36.dp,
                    onClick = { onCollapsedChange(true) },
                )
            }
        }
    }
}

@Composable
internal fun EditorOptions(
    editable: Boolean,
    busy: Boolean,
    showCalibration: Boolean,
    scale: CalibrationStatus,
    scaleLocked: Boolean = false,
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
            MenuItem("Scale · ${scale.label}", close, R.drawable.ic_plan_tool_calibrate, action = onScale)
            if (editable) {
                MenuItem(
                    label = "Calibrate with known length",
                    close = close,
                    iconRes = R.drawable.ic_plan_tool_calibrate,
                    enabled = !scaleLocked,
                    trailingRes = if (scaleLocked) R.drawable.ic_plan_lock else null,
                ) { onMode(DrawingMode.Reference) }
                MenuItem(
                    label = "Draw scale zone",
                    close = close,
                    iconRes = R.drawable.ic_plan_tool_zone,
                    enabled = !scaleLocked,
                    trailingRes = if (scaleLocked) R.drawable.ic_plan_lock else null,
                ) { onMode(DrawingMode.Zone) }
            }
            DropdownMenuItem(
                text = { Text("Show calibration dimensions") },
                trailingIcon = { Checkbox(showCalibration, onCheckedChange = null) },
                onClick = { close(); onToggleCalibration() },
            )
            MenuItem(
                if (highlightsVisible) "Hide highlights" else "Show highlights",
                close,
                if (highlightsVisible) R.drawable.ic_plan_visibility_off else R.drawable.ic_plan_visibility,
                action = onToggleHighlights,
            )
            MenuItem("Rotate view", close, R.drawable.ic_plan_rotate, action = onRotate)
            MenuItem("Takeoff totals", close, R.drawable.ic_plan_takeoff, action = onTakeoff)
            MenuItem("Page measurements and markups", close, R.drawable.ic_plan_layers, action = onPageItems)
            HorizontalDivider()
            MenuItem("Export or share this sheet…", close, CoreR.drawable.ic_share, action = onExportSheet)
            MenuItem("Export whole PDF (searchable)", close, R.drawable.ic_plan_export, action = onExportPdf)
            MenuItem("Export drawing JSON", close, R.drawable.ic_plan_export, action = onExportJson)
        }
    }
}

@Composable
private fun MenuItem(
    label: String,
    close: () -> Unit,
    iconRes: Int? = null,
    enabled: Boolean = true,
    trailingRes: Int? = null,
    action: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        enabled = enabled,
        leadingIcon = iconRes?.let { res ->
            { Icon(painterResource(res), contentDescription = null, modifier = Modifier.size(20.dp)) }
        },
        trailingIcon = trailingRes?.let { res ->
            { Icon(painterResource(res), contentDescription = null, modifier = Modifier.size(16.dp)) }
        },
        onClick = {
            close()
            action()
        },
    )
}

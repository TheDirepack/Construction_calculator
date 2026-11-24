package com.thedirepack.constructioncalc.feature.drawings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thedirepack.constructioncalc.core.data.ModuleSettings
import com.thedirepack.constructioncalc.core.plans.SnapTarget
import com.thedirepack.constructioncalc.core.ui.components.ChoiceRow
import com.thedirepack.constructioncalc.core.ui.components.SettingsGroup
import com.thedirepack.constructioncalc.core.ui.components.SwitchRow
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices

internal enum class StylusBarrelAction(val label: String) {
    ERASER("Erase"),
    PAN("Pan sheet"),
    OFF("Off"),
}

internal enum class SpanInputMode(val label: String, val shortLabel: String) {
    POINTS_ON_RELEASE("Point A → B (at release)", "2-point"),
    DRAG_ACROSS("Drag across", "Drag"),
}

internal val configurableSpanModes: List<DrawingMode> = listOf(
    DrawingMode.Measure,
    DrawingMode.Dimension,
    DrawingMode.Reference,
    DrawingMode.Callout,
    DrawingMode.Arrow,
    DrawingMode.Line,
    DrawingMode.Rectangle,
    DrawingMode.Cloud,
    DrawingMode.CloudCallout,
    DrawingMode.Zone,
)

internal fun defaultSpanInputMode(mode: DrawingMode): SpanInputMode = when (mode) {
    DrawingMode.Measure, DrawingMode.Dimension, DrawingMode.Reference -> SpanInputMode.POINTS_ON_RELEASE
    else -> SpanInputMode.DRAG_ACROSS
}

internal data class DrawingsPrefs(
    val snapMeasurements: Boolean = true,
    val snapCalibration: Boolean = true,
    val measurementTarget: SnapTarget = SnapTarget.CENTER,
    val calibrationTarget: SnapTarget = SnapTarget.EDGE,
    val showCalibrationLayer: Boolean = true,
    val stylusOnlyDraw: Boolean = false,
    val stylusPressure: Boolean = true,
    val stylusBarrelAction: StylusBarrelAction = StylusBarrelAction.ERASER,
    val stylusHoverPreview: Boolean = true,
    val twoFingerUndo: Boolean = true,
    val eraserSizeDp: Int = 16,
    val spanInputModes: Map<DrawingMode, SpanInputMode> = emptyMap(),
    val pinnedTools: List<DrawingMode> = DrawingsSettings.DEFAULT_PINNED_TOOLS,
) {
    fun spanInputModeFor(mode: DrawingMode): SpanInputMode =
        spanInputModes[mode] ?: defaultSpanInputMode(mode)
}

internal interface KeyValueStore {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getString(key: String, default: String): String
    fun getInt(key: String, default: Int): Int
    fun setBoolean(key: String, value: Boolean)
    fun setString(key: String, value: String)
    fun setInt(key: String, value: Int)
}

private fun ModuleSettings.asKeyValueStore(): KeyValueStore = object : KeyValueStore {
    override fun getBoolean(key: String, default: Boolean): Boolean = this@asKeyValueStore.getBoolean(key, default)
    override fun getString(key: String, default: String): String = this@asKeyValueStore.getString(key, default)
    override fun getInt(key: String, default: Int): Int = this@asKeyValueStore.getInt(key, default)
    override fun setBoolean(key: String, value: Boolean) = this@asKeyValueStore.setBoolean(key, value)
    override fun setString(key: String, value: String) = this@asKeyValueStore.setString(key, value)
    override fun setInt(key: String, value: Int) = this@asKeyValueStore.setInt(key, value)
}

internal object DrawingsSettings {
    private const val KEY_SNAP_MEASURE = "snap_measurements"
    private const val KEY_SNAP_CALIBRATION = "snap_calibration"
    private const val KEY_TARGET_MEASURE = "snap_target_measurements"
    private const val KEY_TARGET_CALIBRATION = "snap_target_calibration"
    private const val KEY_SHOW_CALIBRATION = "show_calibration_layer"
    private const val KEY_STYLUS_ONLY_DRAW = "stylus_only_draw"
    private const val KEY_STYLUS_PRESSURE = "stylus_pressure"
    private const val KEY_STYLUS_BARREL = "stylus_barrel_action"
    private const val KEY_STYLUS_HOVER = "stylus_hover_preview"
    private const val KEY_TWO_FINGER_UNDO = "two_finger_undo"
    private const val KEY_ERASER_SIZE_DP = "eraser_size_dp"
    private const val KEY_SPAN_INPUT_PREFIX = "span_input_"
    internal const val KEY_PINNED_TOOLS = "pinned_tools"
    internal const val MIN_PINNED_TOOLS = 1
    internal const val MAX_PINNED_TOOLS = 8
    internal val DEFAULT_PINNED_TOOLS: List<DrawingMode> = listOf(
        DrawingMode.Adjust,
        DrawingMode.Measure,
        DrawingMode.Route,
        DrawingMode.Symbol,
        DrawingMode.Pen,
        DrawingMode.Cloud,
    )
    internal val eraserSizePresetsDp = listOf(8, 16, 28, 40)

    fun read(store: ModuleSettings): DrawingsPrefs = read(store.asKeyValueStore())

    internal fun read(store: KeyValueStore): DrawingsPrefs {
        val spanModes = configurableSpanModes.associateWith { mode ->
            val fallback = defaultSpanInputMode(mode)
            parseSpanInputMode(store.getString("$KEY_SPAN_INPUT_PREFIX${mode.name}", fallback.name), fallback)
        }
        return DrawingsPrefs(
            snapMeasurements = store.getBoolean(KEY_SNAP_MEASURE, true),
            snapCalibration = store.getBoolean(KEY_SNAP_CALIBRATION, true),
            measurementTarget = parseTarget(store.getString(KEY_TARGET_MEASURE, SnapTarget.CENTER.name), SnapTarget.CENTER),
            calibrationTarget = parseTarget(store.getString(KEY_TARGET_CALIBRATION, SnapTarget.EDGE.name), SnapTarget.EDGE),
            showCalibrationLayer = store.getBoolean(KEY_SHOW_CALIBRATION, true),
            stylusOnlyDraw = store.getBoolean(KEY_STYLUS_ONLY_DRAW, false),
            stylusPressure = store.getBoolean(KEY_STYLUS_PRESSURE, true),
            stylusBarrelAction = parseBarrelAction(store.getString(KEY_STYLUS_BARREL, StylusBarrelAction.ERASER.name)),
            stylusHoverPreview = store.getBoolean(KEY_STYLUS_HOVER, true),
            twoFingerUndo = store.getBoolean(KEY_TWO_FINGER_UNDO, true),
            eraserSizeDp = store.getInt(KEY_ERASER_SIZE_DP, 16).coerceIn(6, 48),
            spanInputModes = spanModes,
            pinnedTools = parsePinnedTools(store.getString(KEY_PINNED_TOOLS, "")),
        )
    }

    fun setSnapMeasurements(store: ModuleSettings, enabled: Boolean) = store.setBoolean(KEY_SNAP_MEASURE, enabled)
    fun setSnapCalibration(store: ModuleSettings, enabled: Boolean) = store.setBoolean(KEY_SNAP_CALIBRATION, enabled)
    fun setMeasurementTarget(store: ModuleSettings, target: SnapTarget) = store.setString(KEY_TARGET_MEASURE, target.name)
    fun setCalibrationTarget(store: ModuleSettings, target: SnapTarget) = store.setString(KEY_TARGET_CALIBRATION, target.name)
    fun setShowCalibrationLayer(store: ModuleSettings, visible: Boolean) = store.setBoolean(KEY_SHOW_CALIBRATION, visible)
    fun setStylusOnlyDraw(store: ModuleSettings, enabled: Boolean) = store.setBoolean(KEY_STYLUS_ONLY_DRAW, enabled)
    fun setStylusPressure(store: ModuleSettings, enabled: Boolean) = store.setBoolean(KEY_STYLUS_PRESSURE, enabled)
    fun setStylusBarrelAction(store: ModuleSettings, action: StylusBarrelAction) = store.setString(KEY_STYLUS_BARREL, action.name)
    fun setStylusHoverPreview(store: ModuleSettings, enabled: Boolean) = store.setBoolean(KEY_STYLUS_HOVER, enabled)
    fun setTwoFingerUndo(store: ModuleSettings, enabled: Boolean) = store.setBoolean(KEY_TWO_FINGER_UNDO, enabled)
    fun setEraserSizeDp(store: ModuleSettings, sizeDp: Int) = store.setInt(KEY_ERASER_SIZE_DP, sizeDp.coerceIn(6, 48))
    fun setSpanInputMode(store: ModuleSettings, mode: DrawingMode, inputMode: SpanInputMode) =
        store.setString("$KEY_SPAN_INPUT_PREFIX${mode.name}", inputMode.name)

    fun setPinnedTools(store: ModuleSettings, tools: List<DrawingMode>) =
        setPinnedTools(store.asKeyValueStore(), tools)

    internal fun setPinnedTools(store: KeyValueStore, tools: List<DrawingMode>) =
        store.setString(KEY_PINNED_TOOLS, serializePinnedTools(tools))

    fun resetPinnedTools(store: ModuleSettings) = resetPinnedTools(store.asKeyValueStore())

    internal fun resetPinnedTools(store: KeyValueStore) = setPinnedTools(store, DEFAULT_PINNED_TOOLS)

    fun normalizePinnedTools(tools: List<DrawingMode>): List<DrawingMode> {
        val cleaned = tools.distinct().take(MAX_PINNED_TOOLS)
        return if (cleaned.size >= MIN_PINNED_TOOLS) cleaned else DEFAULT_PINNED_TOOLS
    }

    fun parsePinnedTools(raw: String): List<DrawingMode> {
        if (raw.isBlank()) return DEFAULT_PINNED_TOOLS
        val byName = DrawingMode.entries.associateBy { it.name }
        val parsed = raw.split(',').mapNotNull { token -> byName[token.trim()] }
        return normalizePinnedTools(parsed)
    }

    fun serializePinnedTools(tools: List<DrawingMode>): String =
        normalizePinnedTools(tools).joinToString(",") { it.name }

    fun togglePinnedTool(current: List<DrawingMode>, tool: DrawingMode): List<DrawingMode> {
        val normalized = normalizePinnedTools(current)
        return if (tool in normalized) {
            if (normalized.size <= MIN_PINNED_TOOLS) normalized else normalized - tool
        } else {
            if (normalized.size >= MAX_PINNED_TOOLS) normalized else normalized + tool
        }
    }

    private fun parseTarget(value: String, fallback: SnapTarget): SnapTarget =
        SnapTarget.entries.firstOrNull { it.name == value } ?: fallback

    private fun parseBarrelAction(value: String): StylusBarrelAction =
        StylusBarrelAction.entries.firstOrNull { it.name == value } ?: StylusBarrelAction.ERASER

    private fun parseSpanInputMode(value: String, fallback: SpanInputMode): SpanInputMode =
        SpanInputMode.entries.firstOrNull { it.name == value } ?: fallback
}

@Composable
internal fun rememberDrawingsPrefs(): Pair<DrawingsPrefs, ModuleSettings> {
    val store = LocalAppServices.current.settings.module(DrawingsTool.ID)
    val version by store.changes.collectAsStateWithLifecycle()
    val prefs = remember(version) { DrawingsSettings.read(store) }
    return prefs to store
}

internal fun SnapTarget.label(): String = when (this) {
    SnapTarget.CENTER -> "Line center"
    SnapTarget.EDGE -> "Nearest edge"
}

@Composable
internal fun DrawingsSettingsScreen() {
    val (prefs, store) = rememberDrawingsPrefs()
    SettingsGroup("Line snapping") {
        SwitchRow(
            title = "Snap measurements",
            checked = prefs.snapMeasurements,
            onCheckedChange = { DrawingsSettings.setSnapMeasurements(store, it) },
            subtitle = "Snap measurement endpoints to walls and corners from the sheet's line map.",
        )
        ChoiceRow(
            title = "Measurement snap target",
            options = SnapTarget.entries,
            selected = prefs.measurementTarget,
            onSelect = { DrawingsSettings.setMeasurementTarget(store, it) },
            optionLabel = { it.label() },
        )
        SwitchRow(
            title = "Snap calibration dimensions",
            checked = prefs.snapCalibration,
            onCheckedChange = { DrawingsSettings.setSnapCalibration(store, it) },
            subtitle = "Turn off on sheets where snapping pulls off a known reference.",
        )
        ChoiceRow(
            title = "Calibration snap target",
            options = SnapTarget.entries,
            selected = prefs.calibrationTarget,
            onSelect = { DrawingsSettings.setCalibrationTarget(store, it) },
            optionLabel = { it.label() },
            subtitle = "Nearest edge defaults for dimension lines and wall faces.",
        )
    }
    SettingsGroup("Stylus & gestures") {
        SwitchRow(
            title = "Pencil mode (finger pans)",
            checked = prefs.stylusOnlyDraw,
            onCheckedChange = { DrawingsSettings.setStylusOnlyDraw(store, it) },
            subtitle = "Draw and mark with a stylus while a single finger pans the sheet. Palm rejection is always active.",
        )
        SwitchRow(
            title = "Pressure-sensitive ink",
            checked = prefs.stylusPressure,
            onCheckedChange = { DrawingsSettings.setStylusPressure(store, it) },
            subtitle = "Vary pen stroke width with stylus tip pressure.",
        )
        ChoiceRow(
            title = "Stylus barrel button",
            options = StylusBarrelAction.entries,
            selected = prefs.stylusBarrelAction,
            onSelect = { DrawingsSettings.setStylusBarrelAction(store, it) },
            optionLabel = { it.label },
        )
        SwitchRow(
            title = "Stylus hover preview",
            checked = prefs.stylusHoverPreview,
            onCheckedChange = { DrawingsSettings.setStylusHoverPreview(store, it) },
            subtitle = "Show brush size, eraser ring, and snap targets while hovering a stylus.",
        )
        SwitchRow(
            title = "Two-finger tap to undo",
            checked = prefs.twoFingerUndo,
            onCheckedChange = { DrawingsSettings.setTwoFingerUndo(store, it) },
            subtitle = "Quick two-finger tap undoes; three-finger tap redoes.",
        )
        ChoiceRow(
            title = "Eraser size",
            options = DrawingsSettings.eraserSizePresetsDp,
            selected = prefs.eraserSizeDp,
            onSelect = { DrawingsSettings.setEraserSizeDp(store, it) },
            optionLabel = { "${it}dp" },
            subtitle = "Current eraser radius: ${prefs.eraserSizeDp} dp (fine-tune anytime from the Eraser bar).",
        )
    }
    SettingsGroup("Toolbar tools") {
        ToolbarToolsEditor(
            pinnedTools = prefs.pinnedTools,
            onToggle = { tool ->
                DrawingsSettings.setPinnedTools(store, DrawingsSettings.togglePinnedTool(prefs.pinnedTools, tool))
            },
            onReset = { DrawingsSettings.resetPinnedTools(store) },
            horizontalPadding = 24.dp,
        )
    }
    SettingsGroup("Tool placement (Drag vs 2-point)") {
        Text(
            "Choose whether each tool selects Point A then Point B on release (with magnifier loupe), or draws directly when you drag across.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        for (tool in configurableSpanModes) {
            ChoiceRow(
                title = tool.title,
                options = SpanInputMode.entries,
                selected = prefs.spanInputModeFor(tool),
                onSelect = { DrawingsSettings.setSpanInputMode(store, tool, it) },
                optionLabel = { it.label },
            )
        }
    }
    SymbolSettings()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToolbarToolsEditor(
    pinnedTools: List<DrawingMode>,
    onToggle: (DrawingMode) -> Unit,
    onReset: () -> Unit,
    horizontalPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Pin up to ${DrawingsSettings.MAX_PINNED_TOOLS} frequent tools on the quick toolbar. Other tools stay one tap away in More.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${pinnedTools.size} of ${DrawingsSettings.MAX_PINNED_TOOLS} pinned",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            TextButton(
                onClick = onReset,
                enabled = pinnedTools != DrawingsSettings.DEFAULT_PINNED_TOOLS,
            ) {
                Text("Reset defaults")
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (tool in DrawingMode.entries) {
                val pinned = tool in pinnedTools
                val canToggle = if (pinned) pinnedTools.size > DrawingsSettings.MIN_PINNED_TOOLS
                else pinnedTools.size < DrawingsSettings.MAX_PINNED_TOOLS
                FilterChip(
                    selected = pinned,
                    enabled = canToggle,
                    onClick = { onToggle(tool) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(tool.iconRes()),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    label = { Text(tool.title) },
                )
            }
        }
    }
}

@Composable
internal fun CustomizeToolbarDialog(onDismiss: () -> Unit) {
    val (prefs, store) = rememberDrawingsPrefs()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Toolbar tools") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                ToolbarToolsEditor(
                    pinnedTools = prefs.pinnedTools,
                    onToggle = { tool ->
                        DrawingsSettings.setPinnedTools(store, DrawingsSettings.togglePinnedTool(prefs.pinnedTools, tool))
                    },
                    onReset = { DrawingsSettings.resetPinnedTools(store) },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}


@Composable
internal fun DrawingSettingsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Drawing settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DrawingsSettingsScreen()
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

package com.thedirepack.constructioncalc.feature.drawings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
)

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

    fun read(store: ModuleSettings): DrawingsPrefs = DrawingsPrefs(
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
    )

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

    private fun parseTarget(value: String, fallback: SnapTarget): SnapTarget =
        SnapTarget.entries.firstOrNull { it.name == value } ?: fallback

    private fun parseBarrelAction(value: String): StylusBarrelAction =
        StylusBarrelAction.entries.firstOrNull { it.name == value } ?: StylusBarrelAction.ERASER
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
    }
    SymbolSettings()
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

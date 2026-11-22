package com.thedirepack.constructioncalc.feature.drawings

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

internal data class DrawingsPrefs(
    val snapMeasurements: Boolean = true,
    val snapCalibration: Boolean = true,
    val measurementTarget: SnapTarget = SnapTarget.CENTER,
    val calibrationTarget: SnapTarget = SnapTarget.EDGE,
    val showCalibrationLayer: Boolean = true,
)

internal object DrawingsSettings {
    private const val KEY_SNAP_MEASURE = "snap_measurements"
    private const val KEY_SNAP_CALIBRATION = "snap_calibration"
    private const val KEY_TARGET_MEASURE = "snap_target_measurements"
    private const val KEY_TARGET_CALIBRATION = "snap_target_calibration"
    private const val KEY_SHOW_CALIBRATION = "show_calibration_layer"

    fun read(store: ModuleSettings): DrawingsPrefs = DrawingsPrefs(
        snapMeasurements = store.getBoolean(KEY_SNAP_MEASURE, true),
        snapCalibration = store.getBoolean(KEY_SNAP_CALIBRATION, true),
        measurementTarget = parseTarget(store.getString(KEY_TARGET_MEASURE, SnapTarget.CENTER.name), SnapTarget.CENTER),
        calibrationTarget = parseTarget(store.getString(KEY_TARGET_CALIBRATION, SnapTarget.EDGE.name), SnapTarget.EDGE),
        showCalibrationLayer = store.getBoolean(KEY_SHOW_CALIBRATION, true),
    )

    fun setSnapMeasurements(store: ModuleSettings, enabled: Boolean) = store.setBoolean(KEY_SNAP_MEASURE, enabled)
    fun setSnapCalibration(store: ModuleSettings, enabled: Boolean) = store.setBoolean(KEY_SNAP_CALIBRATION, enabled)
    fun setMeasurementTarget(store: ModuleSettings, target: SnapTarget) = store.setString(KEY_TARGET_MEASURE, target.name)
    fun setCalibrationTarget(store: ModuleSettings, target: SnapTarget) = store.setString(KEY_TARGET_CALIBRATION, target.name)
    fun setShowCalibrationLayer(store: ModuleSettings, visible: Boolean) = store.setBoolean(KEY_SHOW_CALIBRATION, visible)

    private fun parseTarget(value: String, fallback: SnapTarget): SnapTarget =
        SnapTarget.entries.firstOrNull { it.name == value } ?: fallback
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
    SymbolSettings()
}

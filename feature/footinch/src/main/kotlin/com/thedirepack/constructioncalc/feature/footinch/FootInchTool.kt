package com.thedirepack.constructioncalc.feature.footinch

import android.content.Context
import androidx.compose.runtime.Composable
import com.thedirepack.constructioncalc.core.data.ModuleSettings
import com.thedirepack.constructioncalc.core.data.ToolBackup
import com.thedirepack.constructioncalc.core.ui.R
import com.thedirepack.constructioncalc.core.ui.tool.ToolCategory
import com.thedirepack.constructioncalc.core.ui.tool.ToolHost
import com.thedirepack.constructioncalc.core.ui.tool.ToolIds
import com.thedirepack.constructioncalc.core.ui.tool.ToolModule

object FootInchTool : ToolModule {
    const val ID = ToolIds.FOOT_INCH

    override val id = ID
    override val title = "Foot-Inch Calculator"
    override val summary = "Feet, inches and fractions, with exact results"
    override val category = ToolCategory.MEASURE
    override val icon = R.drawable.ic_straighten
    override val hasSettings = true

    @Composable
    override fun Content(host: ToolHost) = FootInchScreen(host)

    @Composable
    override fun SettingsContent() = FootInchSettingsPage()

    override fun backup(context: Context): ToolBackup = FootInchHistory.get(context).backup
}

/**
 * The keypad choices the first-launch quick setup offers. They read and write this tool's own
 * settings store, so the tool's page in Settings shows the same values.
 */
object FootInchKeypadSetup {
    fun layout(store: ModuleSettings): KeypadLayout =
        KeypadLayout.parse(store.getString(FootInchPrefs.KEYPAD_LAYOUT, KeypadLayout.DEFAULT.name))

    fun setLayout(store: ModuleSettings, layout: KeypadLayout) = store.setString(FootInchPrefs.KEYPAD_LAYOUT, layout.name)

    /** Whether the Dual-Split keypad marks Feet and Inches keys with ' and " badges (not color alone). */
    fun shapeBadges(store: ModuleSettings): Boolean = store.getBoolean(FootInchPrefs.COLOR_BLIND_SHAPES, false)

    fun setShapeBadges(store: ModuleSettings, on: Boolean) = store.setBoolean(FootInchPrefs.COLOR_BLIND_SHAPES, on)
}

/** Keys in this tool's settings store. */
internal object FootInchPrefs {
    const val SHOW_FRACTION_KEYS = "show_fraction_keys"
    const val KEYPAD_LAYOUT = "keypad_layout"
    const val SHOW_INLINE_TAPE = "show_inline_tape"
    const val INPUT_FRACTION_PRECISION = "input_fraction_precision"
    const val SHOW_FUNCTION_STRIP = "show_function_strip"
    const val COLOR_BLIND_SHAPES = "color_blind_shapes"
}

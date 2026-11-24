package com.thedirepack.constructioncalc.feature.footinch

import android.content.Context
import androidx.compose.runtime.Composable
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

/** Keys in this tool's settings store. */
internal object FootInchPrefs {
    const val SHOW_FRACTION_KEYS = "show_fraction_keys"
    const val KEYPAD_LAYOUT = "keypad_layout"
    const val SHOW_INLINE_TAPE = "show_inline_tape"
    const val INPUT_FRACTION_PRECISION = "input_fraction_precision"
    const val SHOW_FUNCTION_STRIP = "show_function_strip"
    const val COLOR_BLIND_SHAPES = "color_blind_shapes"
}

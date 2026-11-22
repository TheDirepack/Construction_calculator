package com.thedirepack.constructioncalc.feature.drawings

import android.content.SharedPreferences
import com.thedirepack.constructioncalc.core.model.PlanArrowStyle
import com.thedirepack.constructioncalc.core.model.PlanDashStyle
import com.thedirepack.constructioncalc.core.model.PlanMark
import com.thedirepack.constructioncalc.core.model.PlanMarkKind

internal data class MarkupColorOption(val name: String, val argb: Long)

internal val markupColorPalette = listOf(
    MarkupColorOption("Blue", 0xFF155CBDL),
    MarkupColorOption("Red", 0xFFC62828L),
    MarkupColorOption("Orange", 0xFFE65100L),
    MarkupColorOption("Green", 0xFF2E7D32L),
    MarkupColorOption("Purple", 0xFF6A1B9AL),
    MarkupColorOption("Teal", 0xFF00796BL),
    MarkupColorOption("Black", 0xFF111111L),
    MarkupColorOption("Yellow", 0xFFFFE43CL),
)

internal data class MarkupToolStyle(
    val color: Long,
    val width: Double,
    val opacity: Float = 1f,
    val dash: PlanDashStyle = PlanDashStyle.SOLID,
    val arrowStyle: PlanArrowStyle? = null,
    val fillColor: Long? = null,
    val fillOpacity: Float = 0f,
) {
    fun applyTo(mark: PlanMark): PlanMark = mark.copy(
        width = width.coerceIn(0.5, 1_000.0),
        color = color and 0xffff_ffffL,
        opacity = opacity.coerceIn(0.05f, 1f),
        dash = dash,
        arrowStyle = arrowStyle,
        fillColor = fillColor?.let { it and 0xffff_ffffL },
        fillOpacity = fillOpacity.coerceIn(0f, 1f),
    )

    companion object {
        fun defaultFor(mode: DrawingMode): MarkupToolStyle = when (mode) {
            DrawingMode.Highlight -> MarkupToolStyle(color = 0xFFFFE43CL, width = 12.0, opacity = 0.40f)
            DrawingMode.Pen -> MarkupToolStyle(color = 0xFF155CBDL, width = 2.0, opacity = 1f)
            DrawingMode.Arrow -> MarkupToolStyle(color = 0xFFC62828L, width = 2.2, opacity = 1f, arrowStyle = PlanArrowStyle.END)
            DrawingMode.Line -> MarkupToolStyle(color = 0xFF155CBDL, width = 2.0, opacity = 1f, arrowStyle = PlanArrowStyle.NONE)
            DrawingMode.Rectangle -> MarkupToolStyle(color = 0xFF155CBDL, width = 2.0, opacity = 1f)
            DrawingMode.Text -> MarkupToolStyle(color = 0xFF111111L, width = 13.0, opacity = 1f)
            DrawingMode.Callout -> MarkupToolStyle(color = 0xFFC62828L, width = 13.0, opacity = 1f)
            DrawingMode.Dimension -> MarkupToolStyle(color = 0xFF00796BL, width = 2.0, opacity = 1f, arrowStyle = PlanArrowStyle.BOTH)
            DrawingMode.Cloud -> MarkupToolStyle(color = 0xFFC62828L, width = 2.2, opacity = 1f)
            DrawingMode.CloudCallout -> MarkupToolStyle(color = 0xFFC62828L, width = 13.0, opacity = 1f)
            DrawingMode.Photo, DrawingMode.DetailLink -> MarkupToolStyle(color = 0xFF155CBDL, width = 24.0, opacity = 1f)
            else -> MarkupToolStyle(color = 0xFF155CBDL, width = 2.0, opacity = 1f)
        }

        fun fromMark(mark: PlanMark): MarkupToolStyle {
            val defaultMode = mark.kind.toDrawingMode()
            val fallback = defaultFor(defaultMode)
            val rawColor = mark.color
            val (baseColor, effOpacity) = if (rawColor == null) {
                fallback.color to (if (mark.kind == PlanMarkKind.HIGHLIGHT) 0.40f * mark.opacity else mark.opacity)
            } else {
                val alphaByte = ((rawColor ushr 24) and 0xFFL).toInt()
                if (alphaByte in 1..254) {
                    (rawColor or 0xFF000000L) to ((alphaByte / 255f) * mark.opacity).coerceIn(0.05f, 1f)
                } else {
                    rawColor to mark.opacity
                }
            }
            return MarkupToolStyle(
                color = baseColor,
                width = mark.width,
                opacity = effOpacity.coerceIn(0.05f, 1f),
                dash = mark.dash,
                arrowStyle = mark.arrowStyle ?: fallback.arrowStyle,
                fillColor = mark.fillColor,
                fillOpacity = if (mark.fillColor != null) mark.fillOpacity else 0f,
            )
        }
    }
}

internal fun DrawingMode.supportsStyle(): Boolean =
    group == ToolGroup.MARKUP && this != DrawingMode.Symbol && this != DrawingMode.Eraser

internal fun PlanMarkKind.supportsStyle(): Boolean =
    this != PlanMarkKind.SYMBOL

internal fun PlanMarkKind.toDrawingMode(): DrawingMode = when (this) {
    PlanMarkKind.INK -> DrawingMode.Pen
    PlanMarkKind.HIGHLIGHT -> DrawingMode.Highlight
    PlanMarkKind.ARROW -> DrawingMode.Arrow
    PlanMarkKind.LINE -> DrawingMode.Line
    PlanMarkKind.RECTANGLE -> DrawingMode.Rectangle
    PlanMarkKind.TEXT -> DrawingMode.Text
    PlanMarkKind.SYMBOL -> DrawingMode.Symbol
    PlanMarkKind.CALLOUT -> DrawingMode.Callout
    PlanMarkKind.DIMENSION -> DrawingMode.Dimension
    PlanMarkKind.CLOUD -> DrawingMode.Cloud
    PlanMarkKind.CLOUD_CALLOUT -> DrawingMode.CloudCallout
    PlanMarkKind.PHOTO -> DrawingMode.Photo
    PlanMarkKind.DETAIL_LINK -> DrawingMode.DetailLink
}

internal fun PlanMarkKind.supportsDash(): Boolean =
    this == PlanMarkKind.LINE || this == PlanMarkKind.ARROW || this == PlanMarkKind.RECTANGLE ||
        this == PlanMarkKind.DIMENSION || this == PlanMarkKind.CLOUD || this == PlanMarkKind.CLOUD_CALLOUT

internal fun PlanMarkKind.supportsArrowStyle(): Boolean =
    this == PlanMarkKind.LINE || this == PlanMarkKind.ARROW || this == PlanMarkKind.DIMENSION

internal fun PlanMarkKind.supportsFill(): Boolean =
    this == PlanMarkKind.RECTANGLE || this == PlanMarkKind.TEXT || this == PlanMarkKind.CALLOUT ||
        this == PlanMarkKind.CLOUD || this == PlanMarkKind.CLOUD_CALLOUT || this == PlanMarkKind.DIMENSION

internal fun PlanMarkKind.isTextSized(): Boolean =
    this == PlanMarkKind.TEXT || this == PlanMarkKind.CALLOUT || this == PlanMarkKind.CLOUD_CALLOUT

internal class MarkupStyleStore(private val prefs: SharedPreferences) {
    fun read(mode: DrawingMode): MarkupToolStyle {
        val base = MarkupToolStyle.defaultFor(mode)
        if (!mode.supportsStyle()) return base
        val prefix = "style/${mode.name}"
        val color = if (prefs.contains("$prefix/color")) prefs.getLong("$prefix/color", base.color) else base.color
        val width = if (prefs.contains("$prefix/width")) prefs.getFloat("$prefix/width", base.width.toFloat()).toDouble() else base.width
        val opacity = if (prefs.contains("$prefix/opacity")) prefs.getFloat("$prefix/opacity", base.opacity) else base.opacity
        val dashName = prefs.getString("$prefix/dash", null)
        val dash = PlanDashStyle.entries.firstOrNull { it.name == dashName } ?: base.dash
        val arrowName = prefs.getString("$prefix/arrow", null)
        val arrow = if (arrowName == null) base.arrowStyle else PlanArrowStyle.entries.firstOrNull { it.name == arrowName }
        val savedFillColor = if (prefs.contains("$prefix/fillColor")) prefs.getLong("$prefix/fillColor", 0L) else base.fillColor
        val savedFillOpacity = if (prefs.contains("$prefix/fillOpacity")) prefs.getFloat("$prefix/fillOpacity", base.fillOpacity) else base.fillOpacity
        val hasExplicitFill = if (prefs.contains("$prefix/hasFill")) {
            prefs.getBoolean("$prefix/hasFill", false)
        } else {
            // Migrate legacy installs where Callout/CloudCallout auto-saved an opaque white fill.
            val legacyWhiteDefault = (mode == DrawingMode.Callout || mode == DrawingMode.CloudCallout) &&
                savedFillColor != null && (savedFillColor and 0xFFFFFFL) == 0xFFFFFFL && savedFillOpacity >= 0.94f
            !legacyWhiteDefault && savedFillColor != null && savedFillOpacity > 0f
        }
        val fillColor = if (hasExplicitFill) savedFillColor else null
        val fillOpacity = if (hasExplicitFill) savedFillOpacity else 0f
        return MarkupToolStyle(
            color = color and 0xffff_ffffL,
            width = width.coerceIn(0.5, 1_000.0),
            opacity = opacity.coerceIn(0.05f, 1f),
            dash = dash,
            arrowStyle = arrow,
            fillColor = fillColor?.let { it and 0xffff_ffffL },
            fillOpacity = fillOpacity.coerceIn(0f, 1f),
        )
    }

    fun save(mode: DrawingMode, style: MarkupToolStyle) {
        if (!mode.supportsStyle()) return
        val prefix = "style/${mode.name}"
        val hasFill = style.fillColor != null && style.fillOpacity > 0f
        prefs.edit().apply {
            putLong("$prefix/color", style.color and 0xffff_ffffL)
            putFloat("$prefix/width", style.width.toFloat())
            putFloat("$prefix/opacity", style.opacity.coerceIn(0.05f, 1f))
            putString("$prefix/dash", style.dash.name)
            if (style.arrowStyle == null) remove("$prefix/arrow") else putString("$prefix/arrow", style.arrowStyle.name)
            putBoolean("$prefix/hasFill", hasFill)
            if (!hasFill) {
                remove("$prefix/fillColor")
                putFloat("$prefix/fillOpacity", 0f)
            } else {
                putLong("$prefix/fillColor", style.fillColor!! and 0xffff_ffffL)
                putFloat("$prefix/fillOpacity", style.fillOpacity.coerceIn(0f, 1f))
            }
            apply()
        }
    }
}

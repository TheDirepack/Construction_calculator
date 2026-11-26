package com.thedirepack.constructioncalc.core.data

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.thedirepack.constructioncalc.core.model.DeviceCatalog
import com.thedirepack.constructioncalc.core.model.PlanCustomSymbol
import com.thedirepack.constructioncalc.core.model.PlanMark
import com.thedirepack.constructioncalc.core.model.PlanReviewState
import com.thedirepack.constructioncalc.core.model.PlanSymbolPlacement
import kotlin.math.roundToInt

/**
 * Symbol artwork, looked up by catalog device id (never stored on marks). A handful of common
 * devices have drawn SVGs; every other catalog device gets a family shape with its short tag, so
 * all of the catalog can be placed and recognized before it all has artwork.
 */
object DeviceGlyphs {
    private const val BLUE = "#146db0"

    private val artwork: Map<String, String> = mapOf(
        "receptacle.duplex" to """<circle cx="12" cy="12" r="8"/><path d="M9 5v14m6-14v14"/>""",
        "receptacle.duplex_gfci" to """<circle cx="12" cy="12" r="8"/><path d="M9 5v14m6-14v14M7 12h10"/>""",
        "receptacle.special_purpose" to """<circle cx="12" cy="12" r="8"/><path d="M8 7v10m4-10v10m4-10v10"/>""",
        "receptacle.floor_mounted" to """<rect x="4" y="4" width="16" height="16"/><circle cx="12" cy="12" r="6"/><path d="M9 7v10m6-10v10"/>""",
        "disconnect.disconnect_switch" to """<rect x="4" y="5" width="16" height="14"/><path d="M4 12h5l6-5m0 5h5"/>""",
        "switch.single_pole" to """<circle cx="12" cy="12" r="8"/><path d="M15 8q-7-2-6 2t6 3q1 5-6 3"/>""",
        "switch.three_way" to """<circle cx="12" cy="12" r="8"/><path d="M8 8h7l-4 4 4 4H8"/>""",
        "luminaire.surface_mounted" to """<circle cx="12" cy="12" r="8"/><path d="m7 7 10 10m0-10L7 17"/>""",
        "luminaire.emergency_battery_light" to """<rect x="6" y="10" width="12" height="8" rx="1"/><circle cx="8" cy="7" r="2.2"/><circle cx="16" cy="7" r="2.2"/>""",
        "luminaire.exit_sign" to """<rect x="3" y="7" width="18" height="10" rx="1"/><path d="m7 12 3-2.5v5zm10 0-3-2.5v5z"/>""",
        "junction_box.junction_box" to """<rect x="4" y="4" width="16" height="16"/><path d="M14 8v6q0 4-4 2"/>""",
        "panelboard.panelboard" to """<rect x="3" y="6" width="18" height="12"/><polygon points="3,6 21,6 21,18" fill="$BLUE"/>""",
        "junction_box.pull_box" to """<rect x="4" y="5" width="16" height="14"/><path d="M7 9h6m0 0-2-2m2 2-2 2M17 15h-6m0 0 2-2m-2 2 2 2"/>""",
        "data_outlet.data" to """<polygon points="12,4 20,19 4,19"/>""",
    ).mapValues { (_, shape) ->
        """<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><g fill="none" stroke="$BLUE" stroke-width="1.8">$shape</g></svg>"""
    }

    /** Drawn artwork for a catalog device, or null when it uses the generic glyph. */
    fun svgFor(deviceId: String): String? = artwork[deviceId]

    enum class Shape { CIRCLE, SQUARE, ROUNDED, TRIANGLE, DIAMOND, HEXAGON }

    fun shapeFor(family: String): Shape = when (family) {
        "receptacle", "luminaire", "sensor", "cctv_device" -> Shape.CIRCLE
        "switch" -> Shape.ROUNDED
        "data_outlet" -> Shape.TRIANGLE
        "fire_alarm_device", "security_device" -> Shape.DIAMOND
        "audio_visual_device" -> Shape.HEXAGON
        else -> Shape.SQUARE
    }

    private val tags = HashMap<String, Pair<Shape, String>>()

    private fun tagFor(deviceId: String): Pair<Shape, String> = synchronized(tags) {
        tags.getOrPut(deviceId) {
            val device = runCatching { DeviceCatalog.bundled.find(deviceId) }.getOrNull()
            if (device == null) Shape.SQUARE to "?" else shapeFor(device.family) to device.shortLabel
        }
    }

    /**
     * Draws a device glyph filling the square [left], [top], [size]: custom SVG, drawn artwork, or
     * the family shape with the device's short tag. [color] tints the generic glyph.
     */
    fun drawDevice(
        canvas: Canvas,
        deviceId: String,
        left: Float,
        top: Float,
        size: Float,
        paint: Paint,
        customSymbols: List<PlanCustomSymbol> = emptyList(),
        color: Int = 0xff146db0.toInt(),
    ) {
        val svg = if (deviceId.startsWith(PlanSymbolPlacement.CUSTOM_PREFIX)) {
            customSymbols.firstOrNull { it.id == deviceId }?.svg
        } else {
            artwork[deviceId]
        }
        if (svg != null && color == 0xff146db0.toInt() && drawPlanSymbol(canvas, svg, left, top, left + size, top + size)) return
        val (shape, tag) = if (deviceId.startsWith(PlanSymbolPlacement.CUSTOM_PREFIX)) Shape.SQUARE to "?" else tagFor(deviceId)
        drawGeneric(canvas, shape, tag, left, top, size, paint, color)
    }

    private fun drawGeneric(canvas: Canvas, shape: Shape, tag: String, left: Float, top: Float, size: Float, paint: Paint, color: Int) {
        val inset = size * 0.08f
        val l = left + inset
        val t = top + inset
        val s = size - inset * 2
        val cx = left + size / 2
        val cy = top + size / 2
        paint.reset()
        paint.isAntiAlias = true
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.07f
        paint.strokeJoin = Paint.Join.ROUND
        when (shape) {
            Shape.CIRCLE -> canvas.drawCircle(cx, cy, s / 2, paint)
            Shape.SQUARE -> canvas.drawRect(l, t, l + s, t + s, paint)
            Shape.ROUNDED -> canvas.drawRoundRect(l, t, l + s, t + s, s * 0.25f, s * 0.25f, paint)
            Shape.TRIANGLE, Shape.DIAMOND, Shape.HEXAGON -> canvas.drawPath(polygon(shape, l, t, s), paint)
        }
        if (tag.isEmpty()) return
        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        val box = if (shape == Shape.TRIANGLE || shape == Shape.DIAMOND) s * 0.5f else s * 0.68f
        paint.textSize = box
        val measured = paint.measureText(tag).coerceAtLeast(1f)
        if (measured > box) paint.textSize = box * box / measured
        val baseline = (if (shape == Shape.TRIANGLE) t + s * 0.72f else cy) - (paint.ascent() + paint.descent()) / 2
        canvas.drawText(tag, cx, baseline, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun polygon(shape: Shape, l: Float, t: Float, s: Float): Path = Path().apply {
        when (shape) {
            Shape.TRIANGLE -> {
                moveTo(l + s / 2, t); lineTo(l + s, t + s * 0.9f); lineTo(l, t + s * 0.9f)
            }
            Shape.DIAMOND -> {
                moveTo(l + s / 2, t); lineTo(l + s, t + s / 2); lineTo(l + s / 2, t + s); lineTo(l, t + s / 2)
            }
            else -> {
                val q = s / 4
                moveTo(l + q, t); lineTo(l + s - q, t); lineTo(l + s, t + s / 2)
                lineTo(l + s - q, t + s); lineTo(l + q, t + s); lineTo(l, t + s / 2)
            }
        }
        close()
    }

    private val dashes = HashMap<Float, DashPathEffect>()

    /**
     * Draws a symbol mark: centered on its point, scaled to its size and turned by its rotation.
     * Pending detections are orange in a dashed box with their confidence; rejected ones are faint
     * and crossed out ([showReview] false hides both, as in exports, which show only what counts).
     */
    fun drawSymbolMark(
        canvas: Canvas,
        mark: PlanMark,
        paint: Paint,
        customSymbols: List<PlanCustomSymbol>,
        showReview: Boolean = true,
    ) {
        val symbol = mark.symbol ?: return
        if (!showReview && !symbol.counts) return
        val center = mark.points.first()
        val size = symbol.size.toFloat()
        val half = size / 2
        val saved = canvas.save()
        try {
            canvas.translate(center.x.toFloat(), center.y.toFloat())
            if (symbol.rotationDegrees != 0.0) canvas.rotate(symbol.rotationDegrees.toFloat())
            when (symbol.reviewState) {
                PlanReviewState.ACCEPTED -> drawDevice(canvas, symbol.deviceId, -half, -half, size, paint, customSymbols)
                PlanReviewState.PENDING -> {
                    drawDevice(canvas, symbol.deviceId, -half, -half, size, paint, customSymbols, color = PENDING_COLOR)
                    paint.reset()
                    paint.isAntiAlias = true
                    paint.style = Paint.Style.STROKE
                    paint.color = PENDING_COLOR
                    paint.strokeWidth = (size * 0.05f).coerceAtLeast(0.5f)
                    paint.pathEffect = synchronized(dashes) {
                        val unit = (size * 0.12f).coerceAtLeast(1f)
                        dashes.getOrPut(unit) { DashPathEffect(floatArrayOf(unit, unit * 0.7f), 0f) }
                    }
                    val pad = size * 0.12f
                    canvas.drawRect(-half - pad, -half - pad, half + pad, half + pad, paint)
                    paint.pathEffect = null
                    symbol.confidence?.let { confidence ->
                        paint.style = Paint.Style.FILL
                        paint.textSize = size * 0.32f
                        paint.typeface = Typeface.DEFAULT_BOLD
                        canvas.drawText("${(confidence * 100).roundToInt()}%", -half - pad, -half - pad * 1.6f, paint)
                    }
                }
                PlanReviewState.REJECTED -> {
                    drawDevice(canvas, symbol.deviceId, -half, -half, size, paint, customSymbols, color = REJECTED_COLOR)
                    paint.reset()
                    paint.isAntiAlias = true
                    paint.style = Paint.Style.STROKE
                    paint.color = REJECTED_COLOR
                    paint.strokeWidth = size * 0.06f
                    canvas.drawLine(-half, -half, half, half, paint)
                    canvas.drawLine(half, -half, -half, half, paint)
                }
            }
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    const val PENDING_COLOR: Int = 0xffe65100.toInt()
    private const val REJECTED_COLOR: Int = 0x66888888
}

package com.thedirepack.constructioncalc.feature.drawings

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerType
import com.thedirepack.constructioncalc.core.model.PlanPoint
import kotlin.math.hypot
import kotlin.math.min

internal const val MIN_CANVAS_ZOOM = 0.85f
internal const val FIT_CANVAS_ZOOM = 1.0f
internal const val DOUBLE_TAP_ZOOM = 2.5f
internal const val MAX_CANVAS_ZOOM = 24f
internal const val PALM_COOLDOWN_MS = 250L
internal const val PALM_INTERRUPT_WINDOW_MS = 120L
internal const val DOUBLE_TAP_TIMEOUT_MS = 380L
internal const val MULTI_TAP_TIMEOUT_MS = 260L

internal data class InkPoint(val point: PlanPoint, val pressure: Float)

internal data class ViewportState(val zoom: Float, val scroll: Offset)

internal data class PageTransform(val offset: Offset, val pageCenter: Offset, val scale: Float, val quarterTurns: Int) {
    fun toScreen(point: PlanPoint): Offset = offset + (point.toOffset() - pageCenter).rotatedTurns(quarterTurns) * scale
    fun toPage(point: Offset): PlanPoint {
        val local = ((point - offset) / scale).rotatedTurns((4 - quarterTurns.mod(4)) % 4) + pageCenter
        return PlanPoint(local.x.toDouble(), local.y.toDouble())
    }
}

internal fun PlanPoint.toOffset(): Offset = Offset(x.toFloat(), y.toFloat())

internal fun Offset.rotatedTurns(quarterTurns: Int): Offset = when (quarterTurns.mod(4)) {
    1 -> Offset(-y, x)
    2 -> Offset(-x, -y)
    3 -> Offset(y, -x)
    else -> this
}

internal fun rotatedPageSize(pageWidth: Float, pageHeight: Float, quarterTurns: Int): Size =
    if (quarterTurns.mod(2) == 0) Size(pageWidth, pageHeight) else Size(pageHeight, pageWidth)

internal fun computePageTransform(
    state: ViewportState,
    pageWidth: Float,
    pageHeight: Float,
    quarterTurns: Int,
    viewportWidth: Float,
    viewportHeight: Float,
): PageTransform {
    val rotatedSize = rotatedPageSize(pageWidth.coerceAtLeast(1f), pageHeight.coerceAtLeast(1f), quarterTurns)
    val fit = min(viewportWidth / rotatedSize.width, viewportHeight / rotatedSize.height).coerceAtLeast(0.001f)
    val scale = fit * state.zoom
    val center = Offset(viewportWidth / 2f, viewportHeight / 2f)
    return PageTransform(
        offset = center + state.scroll,
        pageCenter = Offset(pageWidth / 2f, pageHeight / 2f),
        scale = scale,
        quarterTurns = quarterTurns.mod(4),
    )
}

/** Clamps scroll offset so at least part of the page remains in view on any rotation or zoom. */
internal fun clampViewportScroll(
    scroll: Offset,
    pageWidth: Float,
    pageHeight: Float,
    quarterTurns: Int,
    viewportWidth: Float,
    viewportHeight: Float,
    zoom: Float,
): Offset {
    if (viewportWidth <= 0f || viewportHeight <= 0f || pageWidth <= 0f || pageHeight <= 0f) return Offset.Zero
    val rotatedSize = rotatedPageSize(pageWidth, pageHeight, quarterTurns)
    val fit = min(viewportWidth / rotatedSize.width, viewportHeight / rotatedSize.height).coerceAtLeast(0.001f)
    val scale = fit * zoom
    val maxX = ((rotatedSize.width * scale - viewportWidth) / 2f + viewportWidth * 0.25f).coerceAtLeast(0f)
    val maxY = ((rotatedSize.height * scale - viewportHeight) / 2f + viewportHeight * 0.25f).coerceAtLeast(0f)
    val sx = if (scroll.x.isFinite()) scroll.x.coerceIn(-maxX, maxX) else 0f
    val sy = if (scroll.y.isFinite()) scroll.y.coerceIn(-maxY, maxY) else 0f
    return Offset(sx, sy)
}

/**
 * Applies a simultaneous zoom around [centroid] and screen-space [pan] to [current], preserving the
 * page point under [centroid] while clamping zoom to [MIN_CANVAS_ZOOM]..[MAX_CANVAS_ZOOM].
 */
internal fun computeViewportMove(
    current: ViewportState,
    pageWidth: Float,
    pageHeight: Float,
    quarterTurns: Int,
    viewportWidth: Float,
    viewportHeight: Float,
    centroid: Offset,
    pan: Offset,
    zoomFactor: Float = 1f,
): ViewportState {
    val safeZoom = if (zoomFactor.isFinite() && zoomFactor > 0f) zoomFactor else 1f
    val nextZoom = (current.zoom * safeZoom).coerceIn(MIN_CANVAS_ZOOM, MAX_CANVAS_ZOOM)
    if (viewportWidth <= 0f || viewportHeight <= 0f || pageWidth <= 0f || pageHeight <= 0f) {
        return ViewportState(nextZoom, Offset.Zero)
    }
    val before = computePageTransform(current, pageWidth, pageHeight, quarterTurns, viewportWidth, viewportHeight)
    val focalPoint = before.toPage(centroid)
    val intermediate = current.copy(zoom = nextZoom)
    val after = computePageTransform(intermediate, pageWidth, pageHeight, quarterTurns, viewportWidth, viewportHeight)
    val safePan = Offset(if (pan.x.isFinite()) pan.x else 0f, if (pan.y.isFinite()) pan.y else 0f)
    val nextScroll = current.scroll + centroid + safePan - after.toScreen(focalPoint)
    return ViewportState(
        zoom = nextZoom,
        scroll = clampViewportScroll(nextScroll, pageWidth, pageHeight, quarterTurns, viewportWidth, viewportHeight, nextZoom),
    )
}

/**
 * Computes the double-tap zoom toggle target: zooms back to page fit when zoomed in above 1.25x,
 * or zooms to [DOUBLE_TAP_ZOOM] bringing the tapped page point toward the viewport center.
 */
internal fun computeDoubleTapTarget(
    current: ViewportState,
    tapPos: Offset,
    pageWidth: Float,
    pageHeight: Float,
    quarterTurns: Int,
    viewportWidth: Float,
    viewportHeight: Float,
): ViewportState {
    if (current.zoom > 1.25f) return ViewportState(FIT_CANVAS_ZOOM, Offset.Zero)
    if (viewportWidth <= 0f || viewportHeight <= 0f || pageWidth <= 0f || pageHeight <= 0f) {
        return ViewportState(DOUBLE_TAP_ZOOM, Offset.Zero)
    }
    val center = Offset(viewportWidth / 2f, viewportHeight / 2f)
    val factor = DOUBLE_TAP_ZOOM / current.zoom.coerceAtLeast(0.01f)
    return computeViewportMove(
        current = current,
        pageWidth = pageWidth,
        pageHeight = pageHeight,
        quarterTurns = quarterTurns,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        centroid = tapPos,
        pan = center - tapPos,
        zoomFactor = factor,
    )
}

internal fun isDoubleTap(
    lastTimeMs: Long,
    lastPos: Offset,
    nowMs: Long,
    pos: Offset,
    maxDistancePx: Float,
): Boolean =
    lastTimeMs > 0L &&
        nowMs >= lastTimeMs &&
        (nowMs - lastTimeMs) <= DOUBLE_TAP_TIMEOUT_MS &&
        (pos - lastPos).getDistance() <= maxDistancePx

internal enum class PointerIntent { ACTIVE_TOOL, PAN_ONLY, TEMP_ERASE, IGNORE }

/**
 * Decides how a newly pressed pointer should behave based on tool mode, Pencil mode, stylus tail
 * eraser, barrel button state, and palm rejection.
 */
internal fun resolvePointerIntent(
    pointerType: PointerType,
    barrelPressed: Boolean,
    mode: DrawingMode,
    editable: Boolean,
    stylusOnlyDraw: Boolean,
    barrelAction: StylusBarrelAction,
    stylusHovering: Boolean,
    nowMs: Long,
    lastStylusActivityMs: Long,
): PointerIntent {
    val palmBlocked = pointerType == PointerType.Touch &&
        (stylusHovering || (lastStylusActivityMs > 0L && nowMs >= lastStylusActivityMs && nowMs - lastStylusActivityMs <= PALM_COOLDOWN_MS))
    if (palmBlocked) return PointerIntent.IGNORE
    if (!editable) return PointerIntent.PAN_ONLY
    return when (pointerType) {
        PointerType.Eraser -> PointerIntent.TEMP_ERASE
        PointerType.Stylus -> {
            if (barrelPressed) {
                when (barrelAction) {
                    StylusBarrelAction.ERASER -> PointerIntent.TEMP_ERASE
                    StylusBarrelAction.PAN -> PointerIntent.PAN_ONLY
                    StylusBarrelAction.OFF -> if (mode == DrawingMode.Navigate) PointerIntent.PAN_ONLY else PointerIntent.ACTIVE_TOOL
                }
            } else {
                if (mode == DrawingMode.Navigate) PointerIntent.PAN_ONLY else PointerIntent.ACTIVE_TOOL
            }
        }
        PointerType.Touch -> when {
            stylusOnlyDraw && mode != DrawingMode.Adjust -> PointerIntent.PAN_ONLY
            mode == DrawingMode.Navigate -> PointerIntent.PAN_ONLY
            else -> PointerIntent.ACTIVE_TOOL
        }
        else -> if (mode == DrawingMode.Navigate) PointerIntent.PAN_ONLY else PointerIntent.ACTIVE_TOOL
    }
}

internal enum class MultiTapAction { NONE, UNDO, REDO }

/** Recognizes quick two-finger tap (Undo) and three-finger tap (Redo) without triggering on pinch/pan. */
internal fun evaluateMultiFingerTap(
    enabled: Boolean,
    peakTouchCount: Int,
    durationMs: Long,
    maxPointerTravelPx: Float,
    touchSlopPx: Float,
    cumulativeZoom: Float,
): MultiTapAction {
    if (!enabled) return MultiTapAction.NONE
    return when {
        peakTouchCount == 2 &&
            durationMs in 0L..MULTI_TAP_TIMEOUT_MS &&
            maxPointerTravelPx <= touchSlopPx &&
            cumulativeZoom in 0.94f..1.06f -> MultiTapAction.UNDO
        peakTouchCount == 3 &&
            durationMs in 0L..320L &&
            maxPointerTravelPx <= touchSlopPx * 1.25f &&
            cumulativeZoom in 0.92f..1.08f -> MultiTapAction.REDO
        else -> MultiTapAction.NONE
    }
}

/** Smooths stylus pressure and decimates tightly clustered ink points on a single stroke. */
internal class InkStrokeFilter(
    private val pressureSensitive: Boolean,
    private val maxPoints: Int = 2_048,
) {
    private val items = mutableListOf<InkPoint>()
    private var smoothedPressure = 1f
    private var hasSamples = false

    val points: List<InkPoint> get() = items.toList()
    val size: Int get() = items.size

    fun append(point: PlanPoint, rawPressure: Float, isStylus: Boolean, minSpacingPt: Double): InkPoint? {
        if (items.size >= maxPoints) return null
        val previous = items.lastOrNull()
        if (previous != null && hypot(point.x - previous.point.x, point.y - previous.point.y) < minSpacingPt) {
            return null
        }
        val targetPressure = if (isStylus && pressureSensitive) {
            val clean = if (rawPressure.isFinite()) rawPressure.coerceIn(0f, 1f) else 1f
            0.20f + 0.80f * clean
        } else {
            1f
        }
        smoothedPressure = if (!hasSamples || !isStylus || !pressureSensitive) {
            targetPressure
        } else {
            (smoothedPressure + 0.35f * (targetPressure - smoothedPressure)).coerceIn(0.20f, 1f)
        }
        hasSamples = true
        val sample = InkPoint(point, smoothedPressure)
        items += sample
        return sample
    }
}

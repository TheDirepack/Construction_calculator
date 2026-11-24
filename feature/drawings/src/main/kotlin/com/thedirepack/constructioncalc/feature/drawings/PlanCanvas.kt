package com.thedirepack.constructioncalc.feature.drawings

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.MotionEvent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.data.PlanExportCrop
import com.thedirepack.constructioncalc.core.data.drawPlanMark
import com.thedirepack.constructioncalc.core.model.PlanMark
import com.thedirepack.constructioncalc.core.model.PlanMarkKind
import com.thedirepack.constructioncalc.core.model.PlanPoint
import com.thedirepack.constructioncalc.core.model.PlanZone
import com.thedirepack.constructioncalc.core.plans.LineMap
import com.thedirepack.constructioncalc.core.plans.SnapKind
import com.thedirepack.constructioncalc.core.plans.SnapTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class PageBitmap(val image: Bitmap, val width: Float, val height: Float)

private const val PAGE_CACHE_KB = 32 * 1024

private val pageBitmapCache = object : LruCache<String, PageBitmap>(PAGE_CACHE_KB) {
    override fun sizeOf(key: String, value: PageBitmap): Int =
        if (value.image.isRecycled) 1 else (value.image.allocationByteCount / 1024).coerceAtLeast(1)
}

/** Keeps the latest function/callback even across capturing local `::fn` references whose `equals()` ignores captures. */
@Composable
private fun <T> rememberLatest(value: T): State<T> =
    remember { mutableStateOf(value, referentialEqualityPolicy()) }.apply { this.value = value }

private data class RegionTile(val bitmap: Bitmap, val region: RectF, val scale: Float)

private data class LoupeState(val screenPos: Offset, val pagePoint: PlanPoint, val snapped: SnappedPoint?)

private data class StylusHoverState(
    val screenPos: Offset,
    val pagePoint: PlanPoint,
    val isEraser: Boolean,
    val snapped: SnappedPoint?,
)

private sealed interface PageState {
    data object Loading : PageState
    data class Loaded(val page: PageBitmap) : PageState
    data class Error(val message: String) : PageState
}

internal fun pdfPageCount(file: File): Int =
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        // PdfRenderer owns the descriptor once construction succeeds. The outer scope also closes it
        // if the constructor fails before taking ownership.
        PdfRenderer(descriptor).use { it.pageCount }
    }

internal fun loadPage(file: File, pageIndex: Int, maxPixels: Float = 2000f): PageBitmap =
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            renderer.openPage(pageIndex).use { page ->
                val ratio = min(2.5f, maxPixels / maxOf(page.width, page.height))
                val bitmap = Bitmap.createBitmap(
                    ceil(page.width * ratio).toInt().coerceAtLeast(1),
                    ceil(page.height * ratio).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                )
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                PageBitmap(bitmap, page.width.toFloat(), page.height.toFloat())
            }
        }
    }

private class PageViewport(val quarterTurns: Int) {
    var size by mutableStateOf(IntSize.Zero)
    var scroll by mutableStateOf(Offset.Zero)
    var zoom by mutableStateOf(FIT_CANVAS_ZOOM)
    private var animJob: Job? = null

    fun cancelAnimations() {
        animJob?.cancel()
        animJob = null
    }

    fun state(): ViewportState = ViewportState(zoom, scroll)

    private fun center() = Offset(size.width / 2f, size.height / 2f)

    fun transform(page: PageBitmap): PageTransform = computePageTransform(
        state = state(),
        pageWidth = page.width,
        pageHeight = page.height,
        quarterTurns = quarterTurns,
        viewportWidth = size.width.toFloat(),
        viewportHeight = size.height.toFloat(),
    )

    fun move(page: PageBitmap, centroid: Offset, pan: Offset, factor: Float = 1f) {
        cancelAnimations()
        val next = computeViewportMove(
            current = state(),
            pageWidth = page.width,
            pageHeight = page.height,
            quarterTurns = quarterTurns,
            viewportWidth = size.width.toFloat(),
            viewportHeight = size.height.toFloat(),
            centroid = centroid,
            pan = pan,
            zoomFactor = factor,
        )
        zoom = next.zoom
        scroll = next.scroll
    }

    fun zoomFromCenter(scope: CoroutineScope, page: PageBitmap, factor: Float) {
        val target = computeViewportMove(
            current = state(),
            pageWidth = page.width,
            pageHeight = page.height,
            quarterTurns = quarterTurns,
            viewportWidth = size.width.toFloat(),
            viewportHeight = size.height.toFloat(),
            centroid = center(),
            pan = Offset.Zero,
            zoomFactor = factor,
        )
        animateTo(scope, page, target)
    }

    fun animateTo(scope: CoroutineScope, page: PageBitmap, target: ViewportState) {
        cancelAnimations()
        val startZoom = zoom
        val startScroll = scroll
        val endZoom = target.zoom.coerceIn(MIN_CANVAS_ZOOM, MAX_CANVAS_ZOOM)
        val endScroll = clampViewportScroll(
            scroll = target.scroll,
            pageWidth = page.width,
            pageHeight = page.height,
            quarterTurns = quarterTurns,
            viewportWidth = size.width.toFloat(),
            viewportHeight = size.height.toFloat(),
            zoom = endZoom,
        )
        animJob = scope.launch {
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
            ) { t, _ ->
                zoom = startZoom + (endZoom - startZoom) * t
                scroll = Offset(
                    startScroll.x + (endScroll.x - startScroll.x) * t,
                    startScroll.y + (endScroll.y - startScroll.y) * t,
                )
            }
        }
    }

    fun fling(scope: CoroutineScope, page: PageBitmap, velocityPxPerSec: Offset) {
        cancelAnimations()
        if (hypot(velocityPxPerSec.x, velocityPxPerSec.y) < 120f) return
        animJob = scope.launch {
            var vx = velocityPxPerSec.x.coerceIn(-6000f, 6000f)
            var vy = velocityPxPerSec.y.coerceIn(-6000f, 6000f)
            while (hypot(vx, vy) >= 20f) {
                delay(16L)
                val dt = 0.016f
                val clamped = clampViewportScroll(
                    scroll = scroll + Offset(vx * dt, vy * dt),
                    pageWidth = page.width,
                    pageHeight = page.height,
                    quarterTurns = quarterTurns,
                    viewportWidth = size.width.toFloat(),
                    viewportHeight = size.height.toFloat(),
                    zoom = zoom,
                )
                if (clamped == scroll) break
                scroll = clamped
                vx *= 0.90f
                vy *= 0.90f
            }
        }
    }

    /** Visible page-coordinate bounds with a small margin for smooth panning. */
    fun visibleRegion(page: PageBitmap, margin: Float = 0.15f): RectF? {
        if (size.width <= 0 || size.height <= 0) return null
        val t = transform(page)
        val c0 = t.toPage(Offset.Zero)
        val c1 = t.toPage(Offset(size.width.toFloat(), 0f))
        val c2 = t.toPage(Offset(0f, size.height.toFloat()))
        val c3 = t.toPage(Offset(size.width.toFloat(), size.height.toFloat()))
        val minX = minOf(c0.x, c1.x, c2.x, c3.x).toFloat()
        val maxX = maxOf(c0.x, c1.x, c2.x, c3.x).toFloat()
        val minY = minOf(c0.y, c1.y, c2.y, c3.y).toFloat()
        val maxY = maxOf(c0.y, c1.y, c2.y, c3.y).toFloat()
        val padX = (maxX - minX) * margin
        val padY = (maxY - minY) * margin
        val left = (minX - padX).coerceIn(0f, page.width)
        val top = (minY - padY).coerceIn(0f, page.height)
        val right = (maxX + padX).coerceIn(0f, page.width)
        val bottom = (maxY + padY).coerceIn(0f, page.height)
        if (right - left < 1f || bottom - top < 1f) return null
        return RectF(left, top, right, bottom)
    }
}

private fun PlanPoint.offset() = toOffset()

private fun PageBitmap.hit(point: PlanPoint) = point.x in 0.0..width.toDouble() && point.y in 0.0..height.toDouble()

private fun isStylusBarrelPressed(event: PointerEvent): Boolean {
    val motionState = event.motionEvent?.buttonState
    if (motionState != null) {
        return (motionState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0
    }
    return event.buttons.isSecondaryPressed
}

private fun computeTouchTransform(changes: List<PointerInputChange>): Triple<Offset, Offset, Float>? {
    val tracked = changes.filter { it.type == PointerType.Touch && it.pressed && it.previousPressed }
    if (tracked.size < 2) return null
    var prevSum = Offset.Zero
    var curSum = Offset.Zero
    for (c in tracked) {
        prevSum += c.previousPosition
        curSum += c.position
    }
    val inv = 1f / tracked.size
    val prevCentroid = prevSum * inv
    val curCentroid = curSum * inv
    val pan = curCentroid - prevCentroid
    var prevDist = 0f
    var curDist = 0f
    for (c in tracked) {
        prevDist += (c.previousPosition - prevCentroid).getDistance()
        curDist += (c.position - curCentroid).getDistance()
    }
    val zoom = if (prevDist > 4f) curDist / prevDist else 1f
    return Triple(prevCentroid, pan, zoom)
}

internal data class CanvasLine(
    val first: PlanPoint,
    val second: PlanPoint,
    val label: String,
    val isReference: Boolean = false,
)

/** A route already sent to a run, drawn under the live trace. */
internal data class CanvasRoute(val points: List<PlanPoint>, val label: String, val focused: Boolean)

/** An endpoint being dragged in the Adjust tool, and the fixed end it's paired with. */
private data class EndpointDrag(val handle: EndpointHandle, val anchor: PlanPoint?, val point: PlanPoint)

@Composable
internal fun PlanCanvas(
    file: File,
    pageIndex: Int,
    drawingId: String,
    quarterTurns: Int,
    mode: DrawingMode,
    zones: List<PlanZone>,
    selectedZone: String?,
    lines: List<CanvasLine>,
    marks: List<PlanMark>,
    pending: PlanPoint?,
    lineMap: LineMap?,
    snapEnabled: Boolean,
    snapTarget: SnapTarget,
    onPageSize: (PageSize) -> Unit,
    onCropProvider: ((() -> PlanExportCrop?)?) -> Unit,
    /** A tap at a page point; the second value is the touch radius in page points, for hit tests. */
    onPlace: (PlanPoint, Double) -> Unit,
    onLine: (PlanPoint, PlanPoint) -> Unit,
    onInk: (List<InkPoint>) -> Unit,
    modifier: Modifier = Modifier,
    routePoints: List<PlanPoint> = emptyList(),
    routeLegLabels: List<String> = emptyList(),
    routeCornerLabels: List<Pair<PlanPoint, String>> = emptyList(),
    savedRoutes: List<CanvasRoute> = emptyList(),
    savedPaths: List<CanvasPath> = emptyList(),
    selectedPathId: String? = null,
    selectedPathVertex: Int? = null,
    selectedPathEdge: Int? = null,
    handles: List<EndpointHandle> = emptyList(),
    selectedMark: PlanMark?,
    onMoveMark: (PlanMark) -> Unit,
    onMoveEndpoint: (EndpointHandle, PlanPoint) -> Unit = { _, _ -> },
    activeStyle: MarkupToolStyle? = null,
    eraserMode: EraserMode = EraserMode.PARTIAL,
    onPartialErase: (List<PlanPoint>, Double) -> Unit = { _, _ -> },
    dimensionLabel: (PlanMark) -> String? = { null },
    pageCount: Int = 1,
    onPageStep: ((Int) -> Unit)? = null,
    onOpenSheets: (() -> Unit)? = null,
    editable: Boolean = true,
    stylusOnlyDraw: Boolean = false,
    stylusPressure: Boolean = true,
    stylusBarrelAction: StylusBarrelAction = StylusBarrelAction.ERASER,
    stylusHoverPreview: Boolean = true,
    twoFingerUndo: Boolean = true,
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    onDoubleTapMark: (PlanMark) -> Unit = {},
    onWholeEraseTap: (PlanPoint, Double) -> Unit = { _, _ -> },
    onStylusDetected: () -> Unit = {},
    spanInputMode: SpanInputMode = SpanInputMode.DRAG_ACROSS,
    eraserSizeDp: Int = 16,
    liveMeasureLabel: ((PlanPoint, PlanPoint) -> String?)? = null,
    onTapStagedRef: ((String) -> Unit)? = null,
    hudBottomPadding: Dp = 6.dp,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val views = remember(context) { context.getSharedPreferences("drawing-views", 0) }
    val viewKey = "$drawingId/$pageIndex"
    val reportPageSize by rememberLatest(onPageSize)
    val setCropProvider by rememberLatest(onCropProvider)
    val selected by rememberUpdatedState(selectedMark)
    val currentMarks by rememberUpdatedState(marks)
    val moveMark by rememberLatest(onMoveMark)
    val cacheKey = "${file.absolutePath}#$pageIndex"
    val cachedPage = remember(cacheKey) {
        pageBitmapCache.get(cacheKey)?.takeUnless { it.image.isRecycled }
    }
    val state by key(cacheKey) {
        produceState<PageState>(if (cachedPage != null) PageState.Loaded(cachedPage) else PageState.Loading) {
            if (cachedPage != null) {
                value = PageState.Loaded(cachedPage)
                return@produceState
            }
            value = try {
                // A short pause so paging quickly through a set doesn't render every page passed.
                delay(PAGE_DEBOUNCE_MS)
                val loaded = PdfWork.render { loadPage(file, pageIndex) }
                pageBitmapCache.put(cacheKey, loaded)
                PageState.Loaded(loaded)
            } catch (error: java.io.IOException) {
                PageState.Error("This PDF page could not be opened.")
            } catch (error: IllegalArgumentException) {
                PageState.Error("This PDF page is unavailable.")
            } catch (error: SecurityException) {
                PageState.Error("This PDF requires a password.")
            } catch (_: OutOfMemoryError) {
                PageState.Error("This page is too large to display on this device.")
            }
        }
    }
    val place by rememberLatest(onPlace)
    val line by rememberLatest(onLine)
    val ink by rememberLatest(onInk)
    val moveEndpoint by rememberLatest(onMoveEndpoint)
    val currentHandles by rememberUpdatedState(handles)
    val currentLineMap by rememberUpdatedState(lineMap)
    val currentSnapEnabled by rememberUpdatedState(snapEnabled)
    val currentSnapTarget by rememberUpdatedState(snapTarget)
    val currentActiveStyle by rememberUpdatedState(activeStyle)
    val currentEraserMode by rememberUpdatedState(eraserMode)
    val partialErase by rememberLatest(onPartialErase)
    val wholeEraseTap by rememberLatest(onWholeEraseTap)
    val doubleTapMark by rememberLatest(onDoubleTapMark)
    val currentDimensionLabel by rememberLatest(dimensionLabel)
    val currentMode by rememberUpdatedState(mode)
    val currentEditable by rememberUpdatedState(editable)
    val currentStylusOnlyDraw by rememberUpdatedState(stylusOnlyDraw)
    val currentStylusPressure by rememberUpdatedState(stylusPressure)
    val currentBarrelAction by rememberUpdatedState(stylusBarrelAction)
    val currentStylusHoverPreview by rememberUpdatedState(stylusHoverPreview)
    val currentTwoFingerUndo by rememberUpdatedState(twoFingerUndo)
    val currentCanUndo by rememberUpdatedState(canUndo)
    val currentCanRedo by rememberUpdatedState(canRedo)
    val currentOnUndo by rememberLatest(onUndo)
    val currentOnRedo by rememberLatest(onRedo)
    val currentOnStylusDetected by rememberLatest(onStylusDetected)
    val currentSpanInputMode by rememberUpdatedState(spanInputMode)
    val currentPending by rememberUpdatedState(pending)
    val currentRoutePoints by rememberUpdatedState(routePoints)
    val currentLiveMeasureLabel by rememberLatest(liveMeasureLabel)
    val currentOnTapStagedRef by rememberLatest(onTapStagedRef)

    val density = LocalDensity.current
    val snapRadiusPx = with(density) { 24.dp.toPx() }
    val currentEraserRadiusPx by rememberUpdatedState(with(density) { eraserSizeDp.dp.toPx() })
    val outlinePx = with(density) { 2.dp.toPx() }
    val dotPx = with(density) { 4.dp.toPx() }
    val handlePx = with(density) { 6.dp.toPx() }
    val measureTextPx = with(density) { 14.dp.toPx() }
    val routeTextPx = with(density) { 13.dp.toPx() }
    val smallTextPx = with(density) { 11.5.dp.toPx() }
    val doubleTapRadiusPx = with(density) { 48.dp.toPx() }
    val loupeRadiusPx = with(density) { 54.dp.toPx() }
    val loupeMarginPx = with(density) { 14.dp.toPx() }
    val loupeBorderPx = with(density) { 2.5.dp.toPx() }
    val loupeLabelSizePx = with(density) { 11.dp.toPx() }
    val background = MaterialTheme.colorScheme.surfaceContainerHighest

    LaunchedEffect(state) {
        val loaded = (state as? PageState.Loaded)?.page ?: return@LaunchedEffect
        reportPageSize(PageSize(loaded.width, loaded.height))
    }

    // Hoisted draw objects: zero per-frame allocations inside Canvas draw lambda.
    val bitmapPaint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    val textPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true } }
    val snapLabelPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true } }
    val loupeLabelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
    }
    val inkPath = remember { Path() }
    val markPaint = remember { Paint() }
    val markPath = remember { android.graphics.Path() }
    val loupeClipPath = remember { android.graphics.Path() }
    val loupeSrcRect = remember { Rect() }
    val loupeDstRect = remember { RectF() }

    Box(modifier.clipToBounds().background(background), contentAlignment = Alignment.Center) {
        when (val current = state) {
            is PageState.Loading -> CircularProgressIndicator()
            is PageState.Error -> Text(current.message)
            is PageState.Loaded -> {
                val page = current.page

                val pageRect = remember(page) { RectF(0f, 0f, page.width, page.height) }
                val viewport = remember(page, quarterTurns) {
                    PageViewport(quarterTurns).apply {
                        if (views.getInt("$viewKey/rotation", 0) == quarterTurns) {
                            zoom = views.getFloat("$viewKey/zoom", FIT_CANVAS_ZOOM).coerceIn(MIN_CANVAS_ZOOM, MAX_CANVAS_ZOOM)
                            scroll = Offset(views.getFloat("$viewKey/x", 0f), views.getFloat("$viewKey/y", 0f))
                        }
                    }
                }
                DisposableEffect(viewport, viewKey) {
                    setCropProvider {
                        viewport.visibleRegion(page, margin = 0f)?.let {
                            PlanExportCrop(it.left, it.top, it.right, it.bottom)
                        }
                    }
                    onDispose {
                        viewport.cancelAnimations()
                        setCropProvider(null)
                        views.edit().putInt("$viewKey/rotation", quarterTurns)
                            .putFloat("$viewKey/zoom", viewport.zoom)
                            .putFloat("$viewKey/x", viewport.scroll.x)
                            .putFloat("$viewKey/y", viewport.scroll.y)
                            .apply()
                    }
                }
                var markPreview by remember(page, mode) { mutableStateOf<PlanMark?>(null) }
                var preview by remember(page, mode) { mutableStateOf(emptyList<InkPoint>()) }
                var eraserPreview by remember(page, mode) { mutableStateOf(emptyList<PlanPoint>()) }
                var eraserHead by remember(page, mode) { mutableStateOf<PlanPoint?>(null) }
                var inkHead by remember(page, mode) { mutableStateOf<PlanPoint?>(null) }
                var aimPoint by remember(page, mode) { mutableStateOf<PlanPoint?>(null) }
                var activeSnap by remember(page, mode) { mutableStateOf<SnappedPoint?>(null) }
                var loupe by remember(page, mode) { mutableStateOf<LoupeState?>(null) }
                var endpointDrag by remember(page, mode) { mutableStateOf<EndpointDrag?>(null) }
                var hoverState by remember(page) { mutableStateOf<StylusHoverState?>(null) }
                var stylusHovering by remember(page) { mutableStateOf(false) }
                var lastStylusActivityMs by remember(page) { mutableStateOf(0L) }
                var regionTile by remember(page) { mutableStateOf<RegionTile?>(null) }

                // Recycle high-zoom region tiles cleanly whenever replaced or disposed.
                DisposableEffect(regionTile) {
                    val tile = regionTile
                    onDispose {
                        tile?.bitmap?.let { if (!it.isRecycled) it.recycle() }
                    }
                }

                // Quantized tile request key so high-zoom vector PDFs re-render crisply after pan/zoom settles.
                val tileKey = remember(
                    viewport.zoom,
                    viewport.scroll,
                    viewport.quarterTurns,
                    viewport.size,
                ) {
                    if (viewport.zoom < TILE_ZOOM_THRESHOLD || viewport.size == IntSize.Zero) null
                    else Triple(
                        (viewport.zoom * 10f).roundToInt(),
                        (viewport.scroll.x / 24f).roundToInt() to (viewport.scroll.y / 24f).roundToInt(),
                        viewport.quarterTurns to viewport.size,
                    )
                }

                LaunchedEffect(file, pageIndex, page, tileKey) {
                    if (tileKey == null) {
                        if (viewport.zoom < TILE_CLEAR_THRESHOLD) regionTile = null
                        return@LaunchedEffect
                    }
                    delay(TILE_DEBOUNCE_MS)
                    val visible = viewport.visibleRegion(page) ?: return@LaunchedEffect
                    val t = viewport.transform(page)
                    val maxSide = max(visible.width(), visible.height()).coerceAtLeast(1f)
                    val targetScale = min(t.scale, MAX_TILE_PIXELS / maxSide).coerceAtLeast(1f)
                    val rendered = try {
                        PdfWork.render { renderRegion(file, pageIndex, visible, targetScale) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        null
                    }
                    if (rendered != null) {
                        regionTile = RegionTile(rendered, visible, targetScale)
                    }
                }

                fun resolvePoint(screenPos: Offset, transform: PageTransform, snapAllowed: Boolean = currentMode.snaps): Pair<PlanPoint, SnappedPoint?> {
                    val raw = transform.toPage(screenPos)
                    if (!currentSnapEnabled || !snapAllowed) return raw to null
                    val map = currentLineMap ?: return raw to null
                    val radiusPt = (snapRadiusPx / transform.scale).toDouble().coerceIn(4.0, 48.0)
                    val hit = map.snap(raw.x, raw.y, radiusPt, currentSnapTarget) ?: return raw to null
                    val pt = PlanPoint(hit.x, hit.y)
                    return pt to SnappedPoint(pt, hit.kind)
                }

                Canvas(
                    Modifier
                        .fillMaxSize()
                        .onSizeChanged { viewport.size = it }
                        .semantics { contentDescription = "Drawing page ${pageIndex + 1}. ${mode.hint}" }
                        .pointerInput(page, viewport) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val stylusChange = event.changes.firstOrNull {
                                        it.type == PointerType.Stylus || it.type == PointerType.Eraser
                                    }
                                    val firstChange = stylusChange ?: event.changes.firstOrNull() ?: continue
                                    val isStylusOrEraser = stylusChange != null
                                    if (isStylusOrEraser) {
                                        currentOnStylusDetected()
                                        lastStylusActivityMs = firstChange.uptimeMillis
                                    }
                                    when (event.type) {
                                        PointerEventType.Scroll -> {
                                            val dy = firstChange.scrollDelta.y
                                            if (dy != 0f) {
                                                val factor = if (dy < 0f) 1.15f else 1f / 1.15f
                                                viewport.move(page, firstChange.position, Offset.Zero, factor)
                                                firstChange.consume()
                                            }
                                        }
                                        PointerEventType.Enter, PointerEventType.Move -> {
                                            if (isStylusOrEraser && !firstChange.pressed) {
                                                stylusHovering = true
                                                if (currentStylusHoverPreview) {
                                                    val t = viewport.transform(page)
                                                    val (pt, snapped) = resolvePoint(firstChange.position, t)
                                                    val barrelPressed = isStylusBarrelPressed(event)
                                                    hoverState = if (page.hit(pt)) {
                                                        StylusHoverState(
                                                            screenPos = firstChange.position,
                                                            pagePoint = pt,
                                                            isEraser = firstChange.type == PointerType.Eraser ||
                                                                (barrelPressed && currentBarrelAction == StylusBarrelAction.ERASER),
                                                            snapped = snapped,
                                                        )
                                                    } else null
                                                } else {
                                                    hoverState = null
                                                }
                                            } else if (firstChange.pressed) {
                                                hoverState = null
                                            }
                                        }
                                        PointerEventType.Exit -> {
                                            if (isStylusOrEraser || !event.changes.any { it.pressed }) {
                                                stylusHovering = false
                                                hoverState = null
                                            }
                                        }
                                        PointerEventType.Press -> {
                                            if (isStylusOrEraser) hoverState = null
                                        }
                                        PointerEventType.Release -> {
                                            if (isStylusOrEraser && !event.changes.any { it.pressed }) {
                                                stylusHovering = false
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        .pointerInput(page, mode, viewport) {
                            var lastEmptyTapMs = 0L
                            var lastEmptyTapPos = Offset.Zero
                            var lastMarkTapMs = 0L
                            var lastMarkTapPos = Offset.Zero
                            var lastMarkTapId: String? = null
                            fun toggleDoubleTapZoom(tapPos: Offset) {
                                viewport.animateTo(
                                    scope,
                                    page,
                                    computeDoubleTapTarget(
                                        current = viewport.state(),
                                        tapPos = tapPos,
                                        pageWidth = page.width,
                                        pageHeight = page.height,
                                        quarterTurns = quarterTurns,
                                        viewportWidth = viewport.size.width.toFloat(),
                                        viewportHeight = viewport.size.height.toFloat(),
                                    ),
                                )
                            }
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                viewport.cancelAnimations()
                                val downType = down.type
                                val isStylusPointer = downType == PointerType.Stylus || downType == PointerType.Eraser
                                if (isStylusPointer) {
                                    currentOnStylusDetected()
                                    lastStylusActivityMs = down.uptimeMillis
                                    hoverState = null
                                }
                                val isBarrelDown = downType == PointerType.Stylus && isStylusBarrelPressed(currentEvent)
                                val downIntent = resolvePointerIntent(
                                    pointerType = downType,
                                    barrelPressed = isBarrelDown,
                                    mode = mode,
                                    editable = currentEditable,
                                    stylusOnlyDraw = currentStylusOnlyDraw,
                                    barrelAction = currentBarrelAction,
                                    stylusHovering = stylusHovering,
                                    nowMs = down.uptimeMillis,
                                    lastStylusActivityMs = lastStylusActivityMs,
                                )
                                if (downIntent == PointerIntent.IGNORE) {
                                    do {
                                        val ev = awaitPointerEvent()
                                    } while (ev.changes.any { it.pressed })
                                    return@awaitEachGesture
                                }

                                val tempErase = downIntent == PointerIntent.TEMP_ERASE
                                val effectiveWholeErase = (tempErase || (downIntent == PointerIntent.ACTIVE_TOOL && mode == DrawingMode.Eraser)) &&
                                    currentEraserMode == EraserMode.WHOLE
                                val partialErasing = (tempErase || (downIntent == PointerIntent.ACTIVE_TOOL && mode == DrawingMode.Eraser)) &&
                                    currentEraserMode == EraserMode.PARTIAL
                                val drawing = !tempErase && downIntent == PointerIntent.ACTIVE_TOOL && mode.isFreehand
                                val adjustActive = !tempErase && downIntent == PointerIntent.ACTIVE_TOOL && mode == DrawingMode.Adjust
                                val pointAiming = !tempErase && downIntent == PointerIntent.ACTIVE_TOOL &&
                                    mode.usesSpan && currentSpanInputMode == SpanInputMode.POINTS_ON_RELEASE
                                val effectiveUsesLoupe = mode.usesLoupe || pointAiming

                                val downTransform = viewport.transform(page)
                                val (first, firstSnap) = resolvePoint(down.position, downTransform)
                                val touchRadiusPt = (snapRadiusPx / downTransform.scale).toDouble()
                                val eraserRadiusPt = (currentEraserRadiusPx / downTransform.scale).toDouble()
                                val rawFirst = downTransform.toPage(down.position)
                                val mark = selected.takeIf { adjustActive }
                                val markHandle = mark?.editHandles()?.let { points ->
                                    points.indices.minByOrNull { i ->
                                        EditorActions.segmentDistance(rawFirst, points[i], points[i])
                                    }?.takeIf { i -> EditorActions.segmentDistance(rawFirst, points[i], points[i]) <= touchRadiusPt * .55 }
                                }
                                val grabbedMark = mark?.takeIf {
                                    markHandle != null || markAt(listOf(it), rawFirst, touchRadiusPt * .55) != null
                                }
                                val canGrabHandle = adjustActive ||
                                    (downIntent == PointerIntent.ACTIVE_TOOL && mode == DrawingMode.Reference && currentPending == null)
                                val grabbed = if (canGrabHandle && grabbedMark == null) {
                                    EditorActions.handleAt(currentHandles, rawFirst, touchRadiusPt)
                                } else null
                                val anchor = grabbed?.takeUnless { it.target is EndpointTarget.Path }?.let { g ->
                                    currentHandles.firstOrNull { it.target == g.target && it.end != g.end }?.point
                                }
                                // In Adjust, a drag that doesn't start on a handle pans the sheet; Pencil finger-pan also pans.
                                val pans = downIntent == PointerIntent.PAN_ONLY ||
                                    (adjustActive && grabbed == null && grabbedMark == null)

                                val inkFilter = InkStrokeFilter(pressureSensitive = currentStylusPressure)
                                val eraserTrace = mutableListOf<PlanPoint>()
                                if (page.hit(first) && drawing) {
                                    val minSpacingPt = (1.25f / downTransform.scale).toDouble()
                                    inkFilter.append(first, down.pressure, isStylusPointer, minSpacingPt)
                                    preview = inkFilter.points
                                    inkHead = first
                                }
                                if (page.hit(rawFirst) && partialErasing) {
                                    eraserTrace += rawFirst
                                    eraserPreview = eraserTrace.toList()
                                    eraserHead = rawFirst
                                }
                                val showLoupe = !isStylusPointer || viewport.zoom < 2.5f
                                if (page.hit(first) && !drawing && !partialErasing && !effectiveWholeErase && !pans) {
                                    activeSnap = firstSnap
                                    if (grabbed == null && (pointAiming || mode == DrawingMode.Route)) {
                                        aimPoint = first
                                    }
                                    if ((effectiveUsesLoupe || grabbed != null) && showLoupe) {
                                        loupe = LoupeState(down.position, first, firstSnap)
                                    }
                                    if (grabbed == null && mode.usesSpan && currentPending != null) {
                                        preview = listOf(InkPoint(currentPending!!, 1f), InkPoint(first, 1f))
                                    }
                                }
                                if (grabbed != null) endpointDrag = EndpointDrag(grabbed, anchor, grabbed.point)

                                var last = first
                                var lastPosition = down.position
                                var moved = false
                                var multi = false
                                var suppressedByStylus = false
                                var onPage = page.hit(first)

                                val velocityTracker = VelocityTracker()
                                if (pans) velocityTracker.addPointerInputChange(down)

                                // Multi-touch state (strictly Touch pointers; never mixes resting palm with stylus)
                                var peakTouchCount = if (downType == PointerType.Touch) 1 else 0
                                var multiStartMs = down.uptimeMillis
                                var multiEndMs = down.uptimeMillis
                                val startPositions = mutableMapOf<Long, Offset>()
                                if (downType == PointerType.Touch) startPositions[down.id.value] = down.position
                                var maxTouchTravelPx = 0f
                                var cumulativeZoom = 1f
                                var continuationPointerId: Long? = null
                                var continuationPrevPos = Offset.Zero

                                try {
                                    do {
                                        val event = awaitPointerEvent()
                                        val stylusPressedNow = event.changes.any {
                                            it.pressed && (it.type == PointerType.Stylus || it.type == PointerType.Eraser)
                                        }
                                        if (stylusPressedNow) {
                                            lastStylusActivityMs = event.changes.maxOf { it.uptimeMillis }
                                            if (downType == PointerType.Touch && !pans && !multi &&
                                                (!moved || event.changes.first().uptimeMillis - down.uptimeMillis <= PALM_INTERRUPT_WINDOW_MS)
                                            ) {
                                                suppressedByStylus = true
                                                preview = emptyList()
                                                eraserPreview = emptyList()
                                                eraserHead = null
                                                inkHead = null
                                                aimPoint = null
                                                activeSnap = null
                                                loupe = null
                                                endpointDrag = null
                                                markPreview = null
                                                break
                                            }
                                        }

                                        val pressedTouches = if (downType == PointerType.Touch) {
                                            event.changes.filter { it.type == PointerType.Touch && it.pressed }
                                        } else emptyList()

                                        if (downType == PointerType.Touch && pressedTouches.size > 1) {
                                            if (!multi) {
                                                multi = true
                                                multiStartMs = down.uptimeMillis
                                                peakTouchCount = pressedTouches.size
                                                cumulativeZoom = 1f
                                                if (moved) maxTouchTravelPx = maxOf(maxTouchTravelPx, (lastPosition - down.position).getDistance())
                                                preview = emptyList()
                                                eraserPreview = emptyList()
                                                eraserHead = null
                                                inkHead = null
                                                aimPoint = null
                                                activeSnap = null
                                                loupe = null
                                                endpointDrag = null
                                                markPreview = null
                                                velocityTracker.resetTracking()
                                            } else {
                                                peakTouchCount = maxOf(peakTouchCount, pressedTouches.size)
                                            }
                                            for (c in pressedTouches) {
                                                startPositions.putIfAbsent(c.id.value, c.previousPosition)
                                                val travel = (c.position - (startPositions[c.id.value] ?: c.position)).getDistance()
                                                if (travel > maxTouchTravelPx) maxTouchTravelPx = travel
                                            }
                                            computeTouchTransform(event.changes)?.let { (centroid, pan, zoom) ->
                                                cumulativeZoom *= zoom
                                                viewport.move(page, centroid, pan, zoom)
                                            }
                                            continuationPointerId = null
                                            multiEndMs = event.changes.maxOfOrNull { it.uptimeMillis } ?: multiEndMs
                                            pressedTouches.forEach { it.consume() }
                                        } else if (multi) {
                                            for (c in event.changes) {
                                                if (c.type == PointerType.Touch) {
                                                    startPositions.putIfAbsent(c.id.value, c.previousPosition)
                                                    val travel = (c.position - (startPositions[c.id.value] ?: c.position)).getDistance()
                                                    if (travel > maxTouchTravelPx) maxTouchTravelPx = travel
                                                    multiEndMs = maxOf(multiEndMs, c.uptimeMillis)
                                                }
                                            }
                                            if (pressedTouches.size == 1) {
                                                val only = pressedTouches[0]
                                                if (continuationPointerId != only.id.value) {
                                                    continuationPointerId = only.id.value
                                                    continuationPrevPos = only.position
                                                    velocityTracker.resetTracking()
                                                    velocityTracker.addPosition(only.uptimeMillis, only.position)
                                                } else {
                                                    val delta = only.position - continuationPrevPos
                                                    continuationPrevPos = only.position
                                                    if (delta != Offset.Zero) {
                                                        viewport.move(page, only.position, delta, 1f)
                                                    }
                                                    velocityTracker.addPointerInputChange(only)
                                                }
                                                only.consume()
                                            }
                                        } else {
                                            val change = event.changes.firstOrNull { it.id == down.id }
                                            if (change != null) {
                                                val delta = change.position - lastPosition
                                                moved = moved || (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                                                if (pans) {
                                                    velocityTracker.addPointerInputChange(change)
                                                    if (moved) viewport.move(page, lastPosition, delta)
                                                } else if (!effectiveWholeErase) {
                                                    val t = viewport.transform(page)
                                                    val minSpacingPt = (1.25f / t.scale).toDouble()
                                                    if (drawing) {
                                                        for (h in change.historical) {
                                                            val hp = t.toPage(h.position)
                                                            if (page.hit(hp)) {
                                                                inkFilter.append(hp, change.pressure, isStylusPointer, minSpacingPt)
                                                                last = hp
                                                            }
                                                        }
                                                    } else if (partialErasing) {
                                                        for (h in change.historical) {
                                                            val hp = t.toPage(h.position)
                                                            if (page.hit(hp) && eraserTrace.size < 2_048 &&
                                                                (eraserTrace.isEmpty() || eraserTrace.last() != hp)
                                                            ) {
                                                                eraserTrace += hp
                                                            }
                                                        }
                                                    }
                                                    val (point, snapped) = if (drawing || grabbedMark != null || partialErasing) {
                                                        t.toPage(change.position) to null
                                                    } else {
                                                        resolvePoint(change.position, t)
                                                    }
                                                    onPage = page.hit(point)
                                                    if (onPage) {
                                                        last = point
                                                        activeSnap = snapped
                                                        if (partialErasing) {
                                                            if (eraserTrace.size < 2_048 && (eraserTrace.isEmpty() || eraserTrace.last() != point)) {
                                                                eraserTrace += point
                                                            }
                                                            eraserPreview = eraserTrace.toList()
                                                            eraserHead = point
                                                        }
                                                        if (grabbed == null && (pointAiming || mode == DrawingMode.Route)) {
                                                            aimPoint = point
                                                        }
                                                        if (!drawing && !partialErasing && (effectiveUsesLoupe || grabbed != null) && (!isStylusPointer || viewport.zoom < 2.5f)) {
                                                            loupe = LoupeState(change.position, point, snapped)
                                                        }
                                                        if (drawing) {
                                                            inkFilter.append(point, change.pressure, isStylusPointer, minSpacingPt)
                                                            preview = inkFilter.points
                                                            inkHead = point
                                                        }
                                                        endpointDrag = endpointDrag?.copy(point = point)
                                                        if (moved && grabbedMark != null) {
                                                            val dx = point.x - rawFirst.x
                                                            val dy = point.y - rawFirst.y
                                                            markPreview = if (markHandle == null) grabbedMark.move(dx, dy)
                                                            else grabbedMark.resize(markHandle, grabbedMark.editHandles()[markHandle].offset(dx, dy))
                                                        }
                                                        if (grabbed == null && mode.usesSpan) {
                                                            val anchorPt = currentPending
                                                            if (anchorPt != null) {
                                                                preview = listOf(InkPoint(anchorPt, 1f), InkPoint(last, 1f))
                                                            } else if (!pointAiming && moved && page.hit(first)) {
                                                                preview = listOf(InkPoint(first, 1f), InkPoint(last, 1f))
                                                            }
                                                        }
                                                    }
                                                }
                                                lastPosition = change.position
                                                change.consume()
                                            }
                                        }
                                    } while (event.changes.any { it.pressed })

                                    if (!suppressedByStylus && multi) {
                                        val tapAction = evaluateMultiFingerTap(
                                            enabled = currentTwoFingerUndo,
                                            peakTouchCount = peakTouchCount,
                                            durationMs = multiEndMs - multiStartMs,
                                            maxPointerTravelPx = maxTouchTravelPx,
                                            touchSlopPx = viewConfiguration.touchSlop,
                                            cumulativeZoom = cumulativeZoom,
                                        )
                                        when (tapAction) {
                                            MultiTapAction.UNDO -> if (currentEditable && currentCanUndo) {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                currentOnUndo()
                                            }
                                            MultiTapAction.REDO -> if (currentEditable && currentCanRedo) {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                currentOnRedo()
                                            }
                                            MultiTapAction.NONE -> {
                                                val vel = velocityTracker.calculateVelocity()
                                                if (continuationPointerId != null && hypot(vel.x, vel.y) > 120f) {
                                                    viewport.fling(scope, page, Offset(vel.x, vel.y))
                                                }
                                            }
                                        }
                                    } else if (!suppressedByStylus && !multi) {
                                        if (pans && moved) {
                                            val vel = velocityTracker.calculateVelocity()
                                            viewport.fling(scope, page, Offset(vel.x, vel.y))
                                        } else if (pans && !moved && downIntent == PointerIntent.PAN_ONLY) {
                                            val pinHit = markAt(
                                                currentMarks.filter { it.kind == PlanMarkKind.PHOTO || it.kind == PlanMarkKind.DETAIL_LINK },
                                                rawFirst,
                                                touchRadiusPt * 0.85,
                                            )
                                            if (pinHit != null) {
                                                lastEmptyTapMs = 0L
                                                doubleTapMark(pinHit)
                                            } else if (isDoubleTap(lastEmptyTapMs, lastEmptyTapPos, down.uptimeMillis, down.position, doubleTapRadiusPx)) {
                                                lastEmptyTapMs = 0L
                                                toggleDoubleTapZoom(down.position)
                                            } else {
                                                lastEmptyTapMs = down.uptimeMillis
                                                lastEmptyTapPos = down.position
                                            }
                                        } else if (page.hit(first) && downIntent != PointerIntent.IGNORE) {
                                            when {
                                                effectiveWholeErase -> wholeEraseTap(rawFirst, eraserRadiusPt)
                                                grabbedMark != null && moved -> if (onPage) markPreview?.let(moveMark)
                                                grabbedMark != null && !moved -> {
                                                    if (isDoubleTap(lastMarkTapMs, lastMarkTapPos, down.uptimeMillis, down.position, doubleTapRadiusPx) &&
                                                        lastMarkTapId == grabbedMark.id
                                                    ) {
                                                        lastMarkTapMs = 0L
                                                        lastMarkTapId = null
                                                        doubleTapMark(grabbedMark)
                                                    } else {
                                                        lastMarkTapMs = down.uptimeMillis
                                                        lastMarkTapPos = down.position
                                                        lastMarkTapId = grabbedMark.id
                                                        place(rawFirst, touchRadiusPt)
                                                    }
                                                }
                                                grabbed != null -> {
                                                    val target = grabbed.target
                                                    if (moved && onPage) moveEndpoint(grabbed, last)
                                                    else if (!moved && target is EndpointTarget.StagedReference) currentOnTapStagedRef?.invoke(target.id)
                                                    else place(rawFirst, touchRadiusPt)
                                                }
                                                partialErasing && eraserTrace.isNotEmpty() -> partialErase(eraserTrace.toList(), eraserRadiusPt)
                                                adjustActive && !moved -> {
                                                    val hitMark = markAt(currentMarks, rawFirst, touchRadiusPt * .55)
                                                    if (hitMark != null) {
                                                        lastMarkTapMs = down.uptimeMillis
                                                        lastMarkTapPos = down.position
                                                        lastMarkTapId = hitMark.id
                                                        lastEmptyTapMs = 0L
                                                        place(rawFirst, touchRadiusPt)
                                                    } else if (isDoubleTap(lastEmptyTapMs, lastEmptyTapPos, down.uptimeMillis, down.position, doubleTapRadiusPx)) {
                                                        lastEmptyTapMs = 0L
                                                        toggleDoubleTapZoom(down.position)
                                                    } else {
                                                        lastEmptyTapMs = down.uptimeMillis
                                                        lastEmptyTapPos = down.position
                                                        place(rawFirst, touchRadiusPt)
                                                    }
                                                }
                                                drawing && inkFilter.size > 0 -> ink(inkFilter.points.toList())
                                                downIntent == PointerIntent.ACTIVE_TOOL && (mode == DrawingMode.Route || pointAiming) && onPage -> place(last, touchRadiusPt)
                                                downIntent == PointerIntent.ACTIVE_TOOL && currentPending != null && mode.usesSpan && onPage -> place(last, touchRadiusPt)
                                                downIntent == PointerIntent.ACTIVE_TOOL && !moved && onPage -> place(last, touchRadiusPt)
                                                downIntent == PointerIntent.ACTIVE_TOOL && moved && onPage && mode.usesSpan -> line(first, last)
                                            }
                                        }
                                    }
                                } finally {
                                    preview = emptyList()
                                    eraserPreview = emptyList()
                                    eraserHead = null
                                    inkHead = null
                                    aimPoint = null
                                    activeSnap = null
                                    loupe = null
                                    endpointDrag = null
                                    markPreview = null
                                }
                            }
                        },
                ) {
                    val transform = viewport.transform(page)
                    withTransform({
                        translate(transform.offset.x, transform.offset.y)
                        scale(transform.scale, transform.scale, Offset.Zero)
                        rotate(transform.quarterTurns * 90f, Offset.Zero)
                        translate(-transform.pageCenter.x, -transform.pageCenter.y)
                    }) {
                        drawIntoCanvas { canvas ->
                            if (!page.image.isRecycled) {
                                canvas.nativeCanvas.drawBitmap(page.image, null, pageRect, bitmapPaint)
                            }
                            val tile = regionTile
                            if (tile != null && !tile.bitmap.isRecycled) {
                                canvas.nativeCanvas.drawBitmap(tile.bitmap, null, tile.region, bitmapPaint)
                            }
                        }
                        val outline = outlinePx / transform.scale
                        for (zone in zones) {
                            drawZone(
                                zone.first.offset(),
                                zone.second.offset(),
                                if (selectedZone == zone.id) Color(0xFF007A73) else Color(0xFF3472B2),
                                outline,
                            )
                        }
                        for (saved in lines) {
                            val color = if (saved.isReference) Color(0xFFB45309) else Color(0xFF126FB4)
                            val p1 = saved.first.offset()
                            val p2 = saved.second.offset()
                            drawLine(color, p1, p2, outline * 1.15f)
                            val dotR = dotPx / transform.scale
                            drawCircle(color, dotR, p1)
                            drawCircle(color, dotR, p2)
                            val dx = p2.x - p1.x
                            val dy = p2.y - p1.y
                            val segLen = hypot(dx, dy).coerceAtLeast(1f)
                            var nx = -dy / segLen
                            var ny = dx / segLen
                            if (ny > 0f) {
                                nx = -nx
                                ny = -ny
                            }
                            val textOffset = (measureTextPx * 0.78f) / transform.scale
                            val labelPos = (p1 + p2) / 2f + Offset(nx * textOffset, ny * textOffset)
                            drawPlanText(saved.label, labelPos, measureTextPx / transform.scale, color, textPaint, centered = true)
                        }
                        for (saved in savedRoutes) {
                            val color = if (saved.focused) Color(0xFFD81B60) else Color(0x997B1FA2)
                            val width = outline * if (saved.focused) 2.4f else 1.2f
                            for (i in 0 until saved.points.lastIndex) {
                                drawLine(color, saved.points[i].offset(), saved.points[i + 1].offset(), width, cap = StrokeCap.Round)
                            }
                            saved.points.firstOrNull()?.let { start ->
                                drawPlanText(saved.label, start.offset() + Offset(4f / transform.scale, 0f), routeTextPx / transform.scale, color, textPaint)
                            }
                        }
                        for ((path, label) in savedPaths) {
                            val selectedPath = path.id == selectedPathId
                            val color = Color(0xFF007A73)
                            val drag = endpointDrag?.takeIf { (it.handle.target as? EndpointTarget.Path)?.id == path.id }
                            for (i in 0 until path.edgeCount) {
                                val next = (i + 1) % path.points.size
                                val a = (if (drag?.handle?.end == i) drag.point else path.points[i]).offset()
                                val b = (if (drag?.handle?.end == next) drag.point else path.points[next]).offset()
                                val edgeColor = if (selectedPath && selectedPathEdge == i) Color(0xFF126FB4) else color
                                drawLine(edgeColor, a, b, if (selectedPath) outline * 2f else outline, cap = StrokeCap.Round)
                            }
                            drawPlanText(label, path.points.first().offset() + Offset(4f / transform.scale, 0f),
                                routeTextPx / transform.scale, color, textPaint)
                        }
                        // Draw active polyline route
                        if (routePoints.isNotEmpty()) {
                            val routeColor = Color(0xFF7B1FA2)
                            for (i in 0 until routePoints.lastIndex) {
                                val a = routePoints[i].offset()
                                val b = routePoints[i + 1].offset()
                                drawLine(routeColor, a, b, outline * 1.4f, cap = StrokeCap.Round)
                                routeLegLabels.getOrNull(i)?.let { legLabel ->
                                    drawPlanText(legLabel, (a + b) / 2f, routeTextPx / transform.scale, routeColor, textPaint, centered = true)
                                }
                            }
                            if (mode == DrawingMode.Route && aimPoint != null) {
                                val a = routePoints.last()
                                val b = aimPoint!!
                                drawLine(routeColor.copy(alpha = 0.7f), a.offset(), b.offset(), outline * 1.15f, cap = StrokeCap.Round)
                                currentLiveMeasureLabel?.invoke(a, b)?.let { legText ->
                                    drawPlanText(legText, (a.offset() + b.offset()) / 2f, routeTextPx / transform.scale, routeColor, textPaint, centered = true)
                                }
                            }
                            for ((cornerPt, angleLabel) in routeCornerLabels) {
                                val pos = cornerPt.offset() + Offset(4f / transform.scale, -4f / transform.scale)
                                drawPlanText(angleLabel, pos, smallTextPx / transform.scale, Color(0xFF4A148C), textPaint)
                            }
                            for (pt in routePoints) {
                                val pos = pt.offset()
                                drawCircle(Color.White, (dotPx * 1.3f) / transform.scale, pos)
                                drawCircle(routeColor, (dotPx * 0.85f) / transform.scale, pos)
                            }
                        }
                        drawIntoCanvas { canvas ->
                            for (mark in marks) drawPlanMark(
                                canvas.nativeCanvas,
                                markPreview?.takeIf { it.id == mark.id } ?: mark,
                                markPaint,
                                markPath,
                                currentDimensionLabel,
                            )
                        }
                        (markPreview ?: selectedMark)?.let { mark ->
                            val points = mark.editHandles().ifEmpty { listOf(mark.points.first()) }
                            val r = (handlePx * 0.9f) / transform.scale
                            for (point in points) {
                                drawCircle(Color.White, r, point.offset())
                                drawCircle(Color(0xFF126FB4), r, point.offset(), style = Stroke(outline))
                            }
                        }
                        if (mode == DrawingMode.Adjust || mode == DrawingMode.Reference) {
                            val r = handlePx / transform.scale
                            for (handle in handles) {
                                val pathVertex = handle.target is EndpointTarget.Path
                                val stagedRef = handle.target is EndpointTarget.StagedReference
                                val drag = endpointDrag?.takeIf {
                                    (pathVertex || stagedRef) && it.handle == handle
                                }
                                val p = (drag?.point ?: handle.point).offset()
                                val activeVertex = pathVertex && handle.end == selectedPathVertex
                                val fillColor = when {
                                    activeVertex -> Color(0xFF126FB4)
                                    stagedRef -> Color(0xFFFFF3E0)
                                    else -> Color.White
                                }
                                val strokeColor = if (stagedRef) Color(0xFFB45309) else Color(0xFF126FB4)
                                drawRect(fillColor, Offset(p.x - r, p.y - r), Size(r * 2, r * 2))
                                drawRect(strokeColor, Offset(p.x - r, p.y - r), Size(r * 2, r * 2), style = Stroke(outline))
                            }
                            endpointDrag?.let { drag ->
                                drag.anchor?.let { drawLine(Color(0xFF00897B), it.offset(), drag.point.offset(), outline * 1.5f) }
                                drawCircle(Color(0xFF00897B), (dotPx * 1.1f) / transform.scale, drag.point.offset())
                            }
                        }
                        pending?.let {
                            val point = it.offset()
                            val ptColor = if (mode == DrawingMode.Reference) Color(0xFFB45309) else Color(0xFF126FB4)
                            drawCircle(Color.White, (handlePx * 0.95f) / transform.scale, point)
                            drawCircle(ptColor, (dotPx * 1.1f) / transform.scale, point)
                        }
                        val snapToDraw = activeSnap ?: hoverState?.takeIf {
                            currentStylusHoverPreview && mode.snaps && !it.isEraser && preview.isEmpty() && eraserPreview.isEmpty()
                        }?.snapped
                        if (aimPoint != null && snapToDraw == null && (mode == DrawingMode.Route || (mode.usesSpan && currentSpanInputMode == SpanInputMode.POINTS_ON_RELEASE))) {
                            val p = aimPoint!!.offset()
                            val arm = (handlePx * 1.6f) / transform.scale
                            val aimColor = if (mode == DrawingMode.Reference) Color(0xFFB45309) else Color(0xFF126FB4)
                            drawLine(aimColor, Offset(p.x - arm, p.y), Offset(p.x + arm, p.y), outline * 0.9f)
                            drawLine(aimColor, Offset(p.x, p.y - arm), Offset(p.x, p.y + arm), outline * 0.9f)
                        }
                        snapToDraw?.let { snapped ->
                            val p = snapped.point.offset()
                            val r = (handlePx * 1.2f) / transform.scale
                            val snapColor = Color(0xFF00897B)
                            if (snapped.kind != SnapKind.LINE) {
                                drawRect(snapColor, Offset(p.x - r, p.y - r), Size(r * 2, r * 2), style = Stroke(outline))
                            } else {
                                drawCircle(snapColor, r, p, style = Stroke(outline))
                            }
                            drawLine(snapColor, Offset(p.x - r * 1.4f, p.y), Offset(p.x + r * 1.4f, p.y), outline)
                            drawLine(snapColor, Offset(p.x, p.y - r * 1.4f), Offset(p.x, p.y + r * 1.4f), outline)
                            val snapLabel = when (snapped.kind) {
                                SnapKind.CORNER -> "Corner"
                                SnapKind.END -> "End"
                                SnapKind.LINE -> "Line"
                            }
                            drawPlanText(snapLabel, Offset(p.x + r * 1.5f, p.y - r * 1.5f), smallTextPx / transform.scale, snapColor, snapLabelPaint)
                        }
                        val previewColor = currentActiveStyle?.let { Color(it.color.toInt()) }
                        when (mode) {
                            DrawingMode.Pen -> {
                                val penColor = previewColor?.copy(alpha = currentActiveStyle?.opacity ?: 1f) ?: Color(0xFF155CBD)
                                val penWidth = currentActiveStyle?.width?.toFloat() ?: 1.8f
                                drawInk(preview, penColor, penWidth, inkPath)
                                inkHead?.let { head ->
                                    val r = maxOf(penWidth / 2f, outline * 2.2f)
                                    drawCircle(penColor.copy(alpha = 0.25f), r, head.offset())
                                    drawCircle(penColor.copy(alpha = 0.85f), r, head.offset(), style = Stroke(outline * 0.8f))
                                }
                            }
                            DrawingMode.Highlight -> {
                                val hlColor = previewColor?.copy(alpha = currentActiveStyle?.opacity ?: 0.40f) ?: Color(0x66FFE43C)
                                val hlWidth = currentActiveStyle?.width?.toFloat() ?: 12f
                                drawInk(preview, hlColor, hlWidth, inkPath)
                                inkHead?.let { head ->
                                    val r = maxOf(hlWidth / 2f, outline * 2.5f)
                                    drawCircle(hlColor.copy(alpha = 0.35f), r, head.offset())
                                    drawCircle(hlColor.copy(alpha = 0.9f), r, head.offset(), style = Stroke(outline * 0.8f))
                                }
                            }
                            DrawingMode.Eraser -> Unit
                            DrawingMode.Zone -> if (preview.size == 2) {
                                drawZone(preview[0].point.offset(), preview[1].point.offset(), Color(0xFF007A73), outline)
                            }
                            DrawingMode.Rectangle, DrawingMode.Cloud, DrawingMode.CloudCallout -> if (preview.size == 2) {
                                drawZone(preview[0].point.offset(), preview[1].point.offset(), previewColor ?: Color(0xFF007A73), outline)
                            }
                            DrawingMode.Callout -> if (preview.size == 2) {
                                val a = preview[0].point.offset()
                                val b = preview[1].point.offset()
                                val c = previewColor ?: Color(0xFFC62828)
                                drawLine(c, a, b, outline)
                                val boxW = (64f / transform.scale).coerceAtLeast(18f)
                                val boxH = (26f / transform.scale).coerceAtLeast(10f)
                                drawRect(
                                    c,
                                    b,
                                    Size(boxW, boxH),
                                    style = Stroke(outline, pathEffect = PathEffect.dashPathEffect(floatArrayOf(outline * 3f, outline * 2f), 0f)),
                                )
                            }
                            else -> if (preview.size == 2) {
                                val color = if (mode == DrawingMode.Reference) Color(0xFFB45309) else (previewColor ?: Color(0xFF126FB4))
                                val p0 = preview[0].point.offset()
                                val p1 = preview[1].point.offset()
                                drawLine(color, p0, p1, outline * 1.15f)
                                drawCircle(color, dotPx / transform.scale, p0)
                                drawCircle(color, dotPx / transform.scale, p1)
                                currentLiveMeasureLabel?.invoke(preview[0].point, preview[1].point)?.let { liveText ->
                                    val dx = p1.x - p0.x
                                    val dy = p1.y - p0.y
                                    val segLen = hypot(dx, dy).coerceAtLeast(1f)
                                    var nx = -dy / segLen
                                    var ny = dx / segLen
                                    if (ny > 0f) { nx = -nx; ny = -ny }
                                    val off = (measureTextPx * 0.78f) / transform.scale
                                    drawPlanText(liveText, (p0 + p1) / 2f + Offset(nx * off, ny * off), measureTextPx / transform.scale, color, textPaint, centered = true)
                                }
                            }
                        }
                        if (eraserPreview.size >= 2) {
                            val eraserColor = Color(0x55D32F2F)
                            val eraserStroke = (currentEraserRadiusPx * 2f) / transform.scale
                            for (i in 0 until eraserPreview.lastIndex) {
                                drawLine(eraserColor, eraserPreview[i].offset(), eraserPreview[i + 1].offset(), eraserStroke, StrokeCap.Round)
                            }
                        }
                        eraserHead?.let { head ->
                            val er = currentEraserRadiusPx / transform.scale
                            drawCircle(Color(0x28D32F2F), er, head.offset())
                            drawCircle(Color(0xAAD32F2F), er, head.offset(), style = Stroke(outline))
                        }
                    }

                    // Stylus hover cursor rendered cleanly in screen space when hovering above the sheet.
                    hoverState?.takeIf {
                        currentStylusHoverPreview && preview.isEmpty() && eraserPreview.isEmpty() &&
                            endpointDrag == null && markPreview == null && loupe == null
                    }?.let { activeHover ->
                        when {
                            activeHover.isEraser || mode == DrawingMode.Eraser -> {
                                drawCircle(
                                    color = Color(0x88D32F2F),
                                    radius = currentEraserRadiusPx,
                                    center = activeHover.screenPos,
                                    style = Stroke(2f),
                                )
                            }
                            mode.isFreehand -> {
                                val style = currentActiveStyle
                                val baseColor = style?.let { Color(it.color.toInt()) }
                                    ?: if (mode == DrawingMode.Highlight) Color(0xFFFFE43C) else Color(0xFF155CBD)
                                val widthPt = style?.width?.toFloat() ?: if (mode == DrawingMode.Highlight) 12f else 2f
                                val r = (widthPt * transform.scale / 2f).coerceIn(4f, 44f)
                                drawCircle(baseColor.copy(alpha = 0.25f), r, activeHover.screenPos)
                                drawCircle(baseColor.copy(alpha = 0.85f), r, activeHover.screenPos, style = Stroke(1.5f))
                            }
                            mode.snaps -> {
                                val accent = if (activeHover.snapped != null) Color(0xFF00897B) else Color(0xFF126FB4)
                                val arm = 10f
                                val p = activeHover.screenPos
                                drawLine(accent, Offset(p.x - arm, p.y), Offset(p.x + arm, p.y), 1.5f)
                                drawLine(accent, Offset(p.x, p.y - arm), Offset(p.x, p.y + arm), 1.5f)
                            }
                        }
                    }

                    // Snapping loupe / magnifier overlay drawn in screen coordinates so fingers never hide the target.
                    loupe?.let { active ->
                        drawSnappingLoupe(
                            loupe = active,
                            snapTarget = currentSnapTarget,
                            page = page,
                            tile = regionTile,
                            transform = transform,
                            radius = loupeRadiusPx,
                            margin = loupeMarginPx,
                            borderWidth = loupeBorderPx,
                            labelTextSize = loupeLabelSizePx,
                            bitmapPaint = bitmapPaint,
                            labelPaint = loupeLabelPaint,
                            clipPath = loupeClipPath,
                            srcRect = loupeSrcRect,
                            dstRect = loupeDstRect,
                        )
                    }
                }
                if (pageCount > 1 && onPageStep != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.medium,
                        shadowElevation = 2.dp,
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = hudBottomPadding),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { onPageStep((pageIndex - 1).coerceAtLeast(0)) },
                                enabled = pageIndex > 0,
                            ) {
                                Icon(painterResource(R.drawable.ic_plan_prev), contentDescription = "Previous page")
                            }
                            TextButton(
                                onClick = { onOpenSheets?.invoke() },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                            ) {
                                Text("${pageIndex + 1}/$pageCount", style = MaterialTheme.typography.labelMedium)
                            }
                            IconButton(
                                onClick = { onPageStep((pageIndex + 1).coerceAtMost(pageCount - 1)) },
                                enabled = pageIndex + 1 < pageCount,
                            ) {
                                Icon(painterResource(R.drawable.ic_plan_next), contentDescription = "Next page")
                            }
                        }
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium,
                    shadowElevation = 2.dp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = hudBottomPadding),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { viewport.zoomFromCenter(scope, page, 1f / 1.5f) },
                            enabled = viewport.zoom > MIN_CANVAS_ZOOM + 0.01f,
                        ) {
                            Icon(painterResource(R.drawable.ic_plan_zoom_out), contentDescription = "Zoom out")
                        }
                        val offFit = abs(viewport.zoom - FIT_CANVAS_ZOOM) > 0.04f || viewport.scroll != Offset.Zero
                        TextButton(
                            onClick = {
                                val target = if (offFit) ViewportState(FIT_CANVAS_ZOOM, Offset.Zero) else ViewportState(2.0f, Offset.Zero)
                                viewport.animateTo(scope, page, target)
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                        ) {
                            if (offFit) {
                                Icon(painterResource(R.drawable.ic_plan_fit), contentDescription = "Fit to page", Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                            }
                            Text("${(viewport.zoom * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                        }
                        IconButton(
                            onClick = { viewport.zoomFromCenter(scope, page, 1.5f) },
                            enabled = viewport.zoom < MAX_CANVAS_ZOOM - 0.01f,
                        ) {
                            Icon(painterResource(R.drawable.ic_plan_zoom_in), contentDescription = "Zoom in")
                        }
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawSnappingLoupe(
    loupe: LoupeState,
    snapTarget: SnapTarget,
    page: PageBitmap,
    tile: RegionTile?,
    transform: PageTransform,
    radius: Float,
    margin: Float,
    borderWidth: Float,
    labelTextSize: Float,
    bitmapPaint: Paint,
    labelPaint: Paint,
    clipPath: android.graphics.Path,
    srcRect: Rect,
    dstRect: RectF,
) {
    // Place loupe in top-left, or shift to top-right when finger is near top-left.
    val placeRight = loupe.screenPos.x < size.width * 0.45f && loupe.screenPos.y < size.height * 0.45f
    val cx = if (placeRight) size.width - margin - radius else margin + radius
    val cy = margin + radius
    val center = Offset(cx, cy)

    // Sample half-width in PDF points for ~2.5x magnification over the current display scale.
    val loupeScale = transform.scale * 2.5f
    val halfPts = (radius / loupeScale).coerceAtLeast(4f)
    val px = loupe.pagePoint.x.toFloat()
    val py = loupe.pagePoint.y.toFloat()

    // Background disc
    drawCircle(Color.White, radius, center)

    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        val save = native.save()
        try {
            clipPath.reset()
            clipPath.addCircle(cx, cy, radius - borderWidth * 0.5f, android.graphics.Path.Direction.CW)
            native.clipPath(clipPath)
            native.translate(cx, cy)
            native.rotate(transform.quarterTurns * 90f)
            dstRect.set(-radius, -radius, radius, radius)

            val activeTile = tile?.takeIf {
                !it.bitmap.isRecycled &&
                    px - halfPts >= it.region.left && px + halfPts <= it.region.right &&
                    py - halfPts >= it.region.top && py + halfPts <= it.region.bottom
            }

            if (activeTile != null) {
                val sx = activeTile.bitmap.width / activeTile.region.width()
                val sy = activeTile.bitmap.height / activeTile.region.height()
                val left = ((px - halfPts - activeTile.region.left) * sx).roundToInt().coerceIn(0, activeTile.bitmap.width - 1)
                val top = ((py - halfPts - activeTile.region.top) * sy).roundToInt().coerceIn(0, activeTile.bitmap.height - 1)
                val right = ((px + halfPts - activeTile.region.left) * sx).roundToInt().coerceIn(left + 1, activeTile.bitmap.width)
                val bottom = ((py + halfPts - activeTile.region.top) * sy).roundToInt().coerceIn(top + 1, activeTile.bitmap.height)
                srcRect.set(left, top, right, bottom)
                native.drawBitmap(activeTile.bitmap, srcRect, dstRect, bitmapPaint)
            } else if (!page.image.isRecycled) {
                val sx = page.image.width / page.width
                val sy = page.image.height / page.height
                val left = ((px - halfPts) * sx).roundToInt().coerceIn(0, page.image.width - 1)
                val top = ((py - halfPts) * sy).roundToInt().coerceIn(0, page.image.height - 1)
                val right = ((px + halfPts) * sx).roundToInt().coerceIn(left + 1, page.image.width)
                val bottom = ((py + halfPts) * sy).roundToInt().coerceIn(top + 1, page.image.height)
                srcRect.set(left, top, right, bottom)
                native.drawBitmap(page.image, srcRect, dstRect, bitmapPaint)
            }
        } finally {
            native.restoreToCount(save)
        }
    }

    // Center crosshair and snap reticle
    val snapped = loupe.snapped
    val accent = if (snapped != null) Color(0xFF00897B) else Color(0xFF126FB4)
    val cross = radius * 0.32f
    drawLine(accent, Offset(cx - cross, cy), Offset(cx + cross, cy), 1.5f)
    drawLine(accent, Offset(cx, cy - cross), Offset(cx, cy + cross), 1.5f)
    if (snapped != null) {
        val r = radius * 0.16f
        if (snapped.kind != SnapKind.LINE) {
            drawRect(accent, Offset(cx - r, cy - r), Size(r * 2f, r * 2f), style = Stroke(2f))
        } else {
            drawCircle(accent, r, center, style = Stroke(2f))
        }
    }

    // Outer ring
    drawCircle(accent, radius, center, style = Stroke(borderWidth))

    // Snap badge below the loupe
    if (snapped != null) {
        val kindText = when (snapped.kind) {
            SnapKind.CORNER -> "Corner"
            SnapKind.LINE -> "Line"
            SnapKind.END -> "End"
        }
        val targetText = if (snapTarget == SnapTarget.CENTER) "Center" else "Edge"
        val badgeText = "$kindText · $targetText"
        drawIntoCanvas { canvas ->
            labelPaint.textSize = labelTextSize
            labelPaint.color = accent.toArgb()
            canvas.nativeCanvas.drawText(badgeText, cx, cy + radius + labelTextSize * 1.3f, labelPaint)
        }
    }
}

private fun DrawScope.drawZone(a: Offset, b: Offset, color: Color, width: Float) {
    val start = Offset(minOf(a.x, b.x), minOf(a.y, b.y))
    val size = Size(abs(a.x - b.x), abs(a.y - b.y))
    drawRect(color.copy(alpha = .10f), start, size)
    drawRect(color, start, size, style = Stroke(width))
}

private fun DrawScope.drawInk(points: List<InkPoint>, color: Color, width: Float, reusablePath: Path) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        drawCircle(color, width * points[0].pressure / 2, points[0].point.offset())
        return
    }
    if (color.alpha < 1f) {
        // One stroked path so translucent highlighter segments do not darken on their seams.
        reusablePath.reset()
        reusablePath.moveTo(points[0].point.x.toFloat(), points[0].point.y.toFloat())
        for (index in 1 until points.size) {
            reusablePath.lineTo(points[index].point.x.toFloat(), points[index].point.y.toFloat())
        }
        drawPath(reusablePath, color, style = Stroke(width, cap = StrokeCap.Round))
        return
    }
    for ((a, b) in points.zipWithNext()) {
        drawLine(color, a.point.offset(), b.point.offset(), width * (a.pressure + b.pressure) / 2, StrokeCap.Round)
    }
}

private fun DrawScope.drawPlanText(
    text: String,
    point: Offset,
    textSize: Float,
    color: Color,
    paint: Paint,
    centered: Boolean = false,
) {
    if (text.isBlank()) return
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        paint.textSize = textSize
        paint.isFakeBoldText = true
        paint.textAlign = if (centered) Paint.Align.CENTER else Paint.Align.LEFT
        val fm = paint.fontMetrics
        val lineHeight = (fm.descent - fm.ascent).coerceAtLeast(textSize)
        var line = 0
        var start = 0
        while (start <= text.length) {
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            val lineStr = text.substring(start, end)
            val y = (if (centered) point.y - (fm.ascent + fm.descent) / 2f else point.y - fm.ascent) + lineHeight * line
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = maxOf(0.5f, textSize * 0.22f)
            paint.color = android.graphics.Color.WHITE
            paint.alpha = 225
            native.drawText(lineStr, point.x, y, paint)
            paint.style = Paint.Style.FILL
            paint.color = color.toArgb()
            paint.alpha = 255
            native.drawText(lineStr, point.x, y, paint)
            line++
            start = end + 1
        }
        paint.textAlign = Paint.Align.LEFT
    }
}

private const val TILE_ZOOM_THRESHOLD = 2.0f
private const val TILE_CLEAR_THRESHOLD = 1.7f
private const val TILE_DEBOUNCE_MS = 120L
private const val PAGE_DEBOUNCE_MS = 150L
private const val MAX_TILE_PIXELS = 2048f

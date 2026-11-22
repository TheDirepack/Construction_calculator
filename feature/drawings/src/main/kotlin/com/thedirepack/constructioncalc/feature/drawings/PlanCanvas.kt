package com.thedirepack.constructioncalc.feature.drawings

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.data.drawPlanMark
import com.thedirepack.constructioncalc.core.model.PlanMark
import com.thedirepack.constructioncalc.core.model.PlanPoint
import com.thedirepack.constructioncalc.core.model.PlanZone
import com.thedirepack.constructioncalc.core.plans.LineMap
import com.thedirepack.constructioncalc.core.plans.SnapKind
import com.thedirepack.constructioncalc.core.plans.SnapTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal enum class DrawingMode(val title: String, val hint: String) {
    Navigate("Pan", "Drag to pan. Pinch to zoom."),
    Reference("Calibrate", "Tap or drag between the ends of a known dimension."),
    Measure("Measure", "Tap or drag between the ends of a line to measure."),
    Route("Route", "Tap connected points along a conduit route to measure legs and bends."),
    Zone("Zone", "Draw an override zone for a detail with a different scale."),
    Pen("Pen", "Draw with a finger or stylus. Pinch with two fingers to zoom."),
    Highlight("Highlight", "Drag a finger or stylus over the area to highlight."),
    Arrow("Arrow", "Tap or drag from the tail to the tip."),
    Line("Line", "Tap or drag between the line endpoints."),
    Rectangle("Rectangle", "Tap or drag between two opposite corners."),
    Text("Text", "Tap where the text should begin."),
    Symbol("Symbol", "Tap where the SVG symbol should appear."),

    ;

    val usesSpan: Boolean
        get() = this == Zone || this == Reference || this == Measure || this == Arrow || this == Line || this == Rectangle

    val usesLoupe: Boolean
        get() = this == Reference || this == Measure || this == Route
}

internal data class InkPoint(val point: PlanPoint, val pressure: Float)

private data class PageBitmap(val image: Bitmap, val width: Float, val height: Float)

private data class RegionTile(val bitmap: Bitmap, val region: RectF, val scale: Float)

private data class LoupeState(val screenPos: Offset, val pagePoint: PlanPoint, val snapped: SnappedPoint?)

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

private fun loadPage(file: File, pageIndex: Int): PageBitmap =
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            renderer.openPage(pageIndex).use { page ->
                val ratio = min(2.5f, 2000f / maxOf(page.width, page.height))
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

private class PageViewport {
    var size by mutableStateOf(IntSize.Zero)
    var scroll by mutableStateOf(Offset.Zero)
    var zoom by mutableStateOf(1f)
    var quarterTurns by mutableStateOf(0)
        private set

    private fun pageSize(page: PageBitmap) =
        if (quarterTurns % 2 == 0) Size(page.width, page.height) else Size(page.height, page.width)

    fun transform(page: PageBitmap): PageTransform {
        val rotatedSize = pageSize(page)
        val fit = min(size.width / rotatedSize.width, size.height / rotatedSize.height).coerceAtLeast(0.001f)
        val scale = fit * zoom
        return PageTransform(
            center() + scroll,
            Offset(page.width / 2, page.height / 2),
            scale,
            quarterTurns,
        )
    }

    fun zoomFromCenter(page: PageBitmap, factor: Float) = move(page, center(), Offset.Zero, factor)

    fun rotate() {
        quarterTurns = (quarterTurns + 1) % 4
        scroll = Offset.Zero
        zoom = 1f
    }

    private fun center() = Offset(size.width / 2f, size.height / 2f)

    fun move(page: PageBitmap, centroid: Offset, pan: Offset, factor: Float = 1f) {
        val before = transform(page)
        val point = before.toPage(centroid)
        zoom = (zoom * factor).coerceIn(1f, 16f)
        val after = transform(page)
        scroll += centroid + pan - after.toScreen(point)
        val rotatedSize = pageSize(page)
        val x = ((rotatedSize.width * after.scale - size.width) / 2 + size.width * .25f).coerceAtLeast(0f)
        val y = ((rotatedSize.height * after.scale - size.height) / 2 + size.height * .25f).coerceAtLeast(0f)
        scroll = Offset(scroll.x.coerceIn(-x, x), scroll.y.coerceIn(-y, y))
    }

    /** Visible page-coordinate bounds with a small margin for smooth panning. */
    fun visibleRegion(page: PageBitmap): RectF? {
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
        val padX = (maxX - minX) * 0.15f
        val padY = (maxY - minY) * 0.15f
        val left = (minX - padX).coerceIn(0f, page.width)
        val top = (minY - padY).coerceIn(0f, page.height)
        val right = (maxX + padX).coerceIn(0f, page.width)
        val bottom = (maxY + padY).coerceIn(0f, page.height)
        if (right - left < 1f || bottom - top < 1f) return null
        return RectF(left, top, right, bottom)
    }
}

private data class PageTransform(val offset: Offset, val pageCenter: Offset, val scale: Float, val quarterTurns: Int) {
    fun toScreen(point: PlanPoint) = offset + (point.offset() - pageCenter).rotated(quarterTurns) * scale
    fun toPage(point: Offset): PlanPoint {
        val local = ((point - offset) / scale).rotated((4 - quarterTurns) % 4) + pageCenter
        return PlanPoint(local.x.toDouble(), local.y.toDouble())
    }
}

private fun Offset.rotated(quarterTurns: Int) = when (quarterTurns) {
    1 -> Offset(-y, x)
    2 -> Offset(-x, -y)
    3 -> Offset(y, -x)
    else -> this
}

private fun PlanPoint.offset() = Offset(x.toFloat(), y.toFloat())

private fun PageBitmap.hit(point: PlanPoint) = point.x in 0.0..width.toDouble() && point.y in 0.0..height.toDouble()

internal data class CanvasLine(
    val first: PlanPoint,
    val second: PlanPoint,
    val label: String,
    val isReference: Boolean = false,
)

@Composable
internal fun PlanCanvas(
    file: File,
    pageIndex: Int,
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
    onPlace: (PlanPoint) -> Unit,
    onLine: (PlanPoint, PlanPoint) -> Unit,
    onInk: (List<InkPoint>) -> Unit,
    modifier: Modifier = Modifier,
    routePoints: List<PlanPoint> = emptyList(),
    routeLegLabels: List<String> = emptyList(),
    routeCornerLabels: List<Pair<PlanPoint, String>> = emptyList(),
) {
    val state by produceState<PageState>(PageState.Loading, file, pageIndex) {
        value = PageState.Loading
        value = try {
            PageState.Loaded(withContext(Dispatchers.IO) { loadPage(file, pageIndex) })
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
    val place by rememberUpdatedState(onPlace)
    val line by rememberUpdatedState(onLine)
    val ink by rememberUpdatedState(onInk)
    val currentLineMap by rememberUpdatedState(lineMap)
    val currentSnapEnabled by rememberUpdatedState(snapEnabled)
    val currentSnapTarget by rememberUpdatedState(snapTarget)
    val density = LocalDensity.current
    val snapRadiusPx = with(density) { 24.dp.toPx() }
    val loupeRadiusPx = with(density) { 54.dp.toPx() }
    val loupeMarginPx = with(density) { 14.dp.toPx() }
    val loupeBorderPx = with(density) { 2.5.dp.toPx() }
    val loupeLabelSizePx = with(density) { 11.dp.toPx() }
    val background = MaterialTheme.colorScheme.surfaceContainerHighest

    LaunchedEffect(state) {
        val loaded = (state as? PageState.Loaded)?.page ?: return@LaunchedEffect
        onPageSize(PageSize(loaded.width, loaded.height))
    }

    // Hoisted draw objects: zero per-frame allocations inside Canvas draw lambda.
    val bitmapPaint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    val textPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val loupeLabelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
    }
    val inkPath = remember { Path() }
    val loupeClipPath = remember { android.graphics.Path() }
    val loupeSrcRect = remember { Rect() }
    val loupeDstRect = remember { RectF() }

    Box(modifier.clipToBounds().background(background), contentAlignment = Alignment.Center) {
        when (val current = state) {
            is PageState.Loading -> CircularProgressIndicator()
            is PageState.Error -> Text(current.message)
            is PageState.Loaded -> {
                val page = current.page
                // Recycle the base page bitmap when switching pages/files or leaving composition.
                DisposableEffect(page) {
                    onDispose {
                        if (!page.image.isRecycled) page.image.recycle()
                    }
                }

                val pageRect = remember(page) { RectF(0f, 0f, page.width, page.height) }
                val viewport = remember(page) { PageViewport() }
                var preview by remember(page, mode) { mutableStateOf(emptyList<InkPoint>()) }
                var activeSnap by remember(page, mode) { mutableStateOf<SnappedPoint?>(null) }
                var loupe by remember(page, mode) { mutableStateOf<LoupeState?>(null) }
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
                        withContext(Dispatchers.IO) { renderRegion(file, pageIndex, visible, targetScale) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        null
                    }
                    if (rendered != null) {
                        regionTile = RegionTile(rendered, visible, targetScale)
                    }
                }

                fun resolvePoint(screenPos: Offset, transform: PageTransform): Pair<PlanPoint, SnappedPoint?> {
                    val raw = transform.toPage(screenPos)
                    if (!currentSnapEnabled) return raw to null
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
                        .pointerInput(page, mode) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val downTransform = viewport.transform(page)
                                val (first, firstSnap) = resolvePoint(down.position, downTransform)
                                val drawing = mode == DrawingMode.Pen || mode == DrawingMode.Highlight
                                val points = mutableListOf<InkPoint>()
                                if (page.hit(first) && drawing) {
                                    points += InkPoint(first, down.pressureForInk())
                                    preview = points.toList()
                                }
                                if (page.hit(first) && !drawing) {
                                    activeSnap = firstSnap
                                    if (mode.usesLoupe) {
                                        loupe = LoupeState(down.position, first, firstSnap)
                                    }
                                }
                                var last = first
                                var lastPosition = down.position
                                var moved = false
                                var multi = false
                                var onPage = page.hit(first)
                                try {
                                    do {
                                        val event = awaitPointerEvent()
                                        if (event.changes.count { it.pressed } > 1) {
                                            multi = true
                                            preview = emptyList()
                                            activeSnap = null
                                            loupe = null
                                            val center = event.calculateCentroid(useCurrent = false)
                                            if (center != Offset.Unspecified) {
                                                viewport.move(page, center, event.calculatePan(), event.calculateZoom())
                                            }
                                            event.changes.forEach { it.consume() }
                                        } else if (!multi) {
                                            val change = event.changes.firstOrNull { it.id == down.id }
                                            if (change != null) {
                                                val delta = change.position - lastPosition
                                                moved = moved || (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                                                if (mode == DrawingMode.Navigate && moved) {
                                                    viewport.move(page, lastPosition, delta)
                                                } else {
                                                    val t = viewport.transform(page)
                                                    val (point, snapped) = if (drawing) t.toPage(change.position) to null else resolvePoint(change.position, t)
                                                    onPage = page.hit(point)
                                                    if (onPage) {
                                                        last = point
                                                        activeSnap = snapped
                                                        if (mode.usesLoupe) {
                                                            loupe = LoupeState(change.position, point, snapped)
                                                        }
                                                        if (drawing && points.isNotEmpty() && points.size < 2_048 && point != points.last().point) {
                                                            points += InkPoint(point, change.pressureForInk())
                                                        }
                                                        if (drawing) preview = points.toList()
                                                        if (moved && page.hit(first) && mode.usesSpan) {
                                                            preview = listOf(InkPoint(first, 1f), InkPoint(last, 1f))
                                                        }
                                                    }
                                                }
                                                lastPosition = change.position
                                                change.consume()
                                            }
                                        }
                                    } while (event.changes.any { it.pressed })
                                    if (!multi && page.hit(first) && mode != DrawingMode.Navigate) {
                                        when {
                                            drawing && points.isNotEmpty() -> ink(points.toList())
                                            mode == DrawingMode.Route && onPage -> place(last)
                                            !moved && onPage -> place(last)
                                            moved && onPage && mode.usesSpan -> line(first, last)
                                        }
                                    }
                                } finally {
                                    preview = emptyList()
                                    activeSnap = null
                                    loupe = null
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
                        val outline = 1.5f / transform.scale
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
                            drawLine(color, saved.first.offset(), saved.second.offset(), outline)
                            drawCircle(color, 2.5f / transform.scale, saved.first.offset())
                            drawCircle(color, 2.5f / transform.scale, saved.second.offset())
                            drawPlanText(saved.label, (saved.first.offset() + saved.second.offset()) / 2f, 12f / transform.scale, color, textPaint)
                        }
                        // Draw active polyline route
                        if (routePoints.isNotEmpty()) {
                            val routeColor = Color(0xFF7B1FA2)
                            for (i in 0 until routePoints.lastIndex) {
                                val a = routePoints[i].offset()
                                val b = routePoints[i + 1].offset()
                                drawLine(routeColor, a, b, outline * 1.4f, cap = StrokeCap.Round)
                                routeLegLabels.getOrNull(i)?.let { legLabel ->
                                    drawPlanText(legLabel, (a + b) / 2f, 12f / transform.scale, routeColor, textPaint)
                                }
                            }
                            for ((cornerPt, angleLabel) in routeCornerLabels) {
                                val pos = cornerPt.offset() + Offset(4f / transform.scale, -4f / transform.scale)
                                drawPlanText(angleLabel, pos, 10.5f / transform.scale, Color(0xFF4A148C), textPaint)
                            }
                            for (pt in routePoints) {
                                val pos = pt.offset()
                                drawCircle(Color.White, 4.5f / transform.scale, pos)
                                drawCircle(routeColor, 3f / transform.scale, pos)
                            }
                        }
                        for (mark in marks) drawMark(mark)
                        pending?.let {
                            val point = it.offset()
                            drawCircle(Color.White, 5f / transform.scale, point)
                            drawCircle(Color(0xFF126FB4), 3f / transform.scale, point)
                        }
                        activeSnap?.let { snapped ->
                            val p = snapped.point.offset()
                            val r = 7f / transform.scale
                            val snapColor = Color(0xFF00897B)
                            if (snapped.kind == SnapKind.CORNER) {
                                drawRect(snapColor, Offset(p.x - r, p.y - r), Size(r * 2, r * 2), style = Stroke(outline))
                            } else {
                                drawCircle(snapColor, r, p, style = Stroke(outline))
                            }
                            drawLine(snapColor, Offset(p.x - r * 1.4f, p.y), Offset(p.x + r * 1.4f, p.y), outline)
                            drawLine(snapColor, Offset(p.x, p.y - r * 1.4f), Offset(p.x, p.y + r * 1.4f), outline)
                        }
                        when (mode) {
                            DrawingMode.Pen -> drawInk(preview, Color(0xFF155CBD), 1.8f, inkPath)
                            DrawingMode.Highlight -> drawInk(preview, Color(0x66FFE43C), 11f, inkPath)
                            DrawingMode.Zone, DrawingMode.Rectangle -> if (preview.size == 2) {
                                drawZone(preview[0].point.offset(), preview[1].point.offset(), Color(0xFF007A73), outline)
                            }
                            else -> if (preview.size == 2) {
                                val color = if (mode == DrawingMode.Reference) Color(0xFFB45309) else Color(0xFF126FB4)
                                drawLine(color, preview[0].point.offset(), preview[1].point.offset(), outline)
                            }
                        }
                    }

                    // Snapping loupe / magnifier overlay drawn in screen coordinates so fingers never hide the target.
                    loupe?.let { active ->
                        drawSnappingLoupe(
                            loupe = active,
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
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium,
                    shadowElevation = 2.dp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                ) {
                    Row {
                        TextButton(onClick = { viewport.zoomFromCenter(page, 1f / 1.5f) }, enabled = viewport.zoom > 1f) {
                            Text("Zoom −")
                        }
                        TextButton(onClick = { viewport.zoomFromCenter(page, 1.5f) }, enabled = viewport.zoom < 16f) {
                            Text("Zoom +")
                        }
                        TextButton(onClick = { viewport.rotate() }) { Text("Rotate") }
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawSnappingLoupe(
    loupe: LoupeState,
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

            val useTile = tile != null && !tile.bitmap.isRecycled &&
                px - halfPts >= tile.region.left && px + halfPts <= tile.region.right &&
                py - halfPts >= tile.region.top && py + halfPts <= tile.region.bottom

            if (useTile && tile != null) {
                val sx = tile.bitmap.width / tile.region.width()
                val sy = tile.bitmap.height / tile.region.height()
                val left = ((px - halfPts - tile.region.left) * sx).roundToInt().coerceIn(0, tile.bitmap.width - 1)
                val top = ((py - halfPts - tile.region.top) * sy).roundToInt().coerceIn(0, tile.bitmap.height - 1)
                val right = ((px + halfPts - tile.region.left) * sx).roundToInt().coerceIn(left + 1, tile.bitmap.width)
                val bottom = ((py + halfPts - tile.region.top) * sy).roundToInt().coerceIn(top + 1, tile.bitmap.height)
                srcRect.set(left, top, right, bottom)
                native.drawBitmap(tile.bitmap, srcRect, dstRect, bitmapPaint)
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
        if (snapped.kind == SnapKind.CORNER) {
            drawRect(accent, Offset(cx - r, cy - r), Size(r * 2f, r * 2f), style = Stroke(2f))
        } else {
            drawCircle(accent, r, center, style = Stroke(2f))
        }
    }

    // Outer ring
    drawCircle(accent, radius, center, style = Stroke(borderWidth))

    // Snap badge below the loupe
    if (snapped != null) {
        val badgeText = when (snapped.kind) {
            SnapKind.CORNER -> "Corner"
            SnapKind.LINE -> "Line"
        }
        drawIntoCanvas { canvas ->
            labelPaint.textSize = labelTextSize
            labelPaint.color = accent.toArgb()
            canvas.nativeCanvas.drawText(badgeText, cx, cy + radius + labelTextSize * 1.3f, labelPaint)
        }
    }
}

private fun androidx.compose.ui.input.pointer.PointerInputChange.pressureForInk(): Float =
    if (type == PointerType.Stylus || type == PointerType.Eraser) pressure.takeIf { it.isFinite() }?.coerceIn(.15f, 1f) ?: 1f else 1f

private fun DrawScope.drawZone(a: Offset, b: Offset, color: Color, width: Float) {
    val start = Offset(minOf(a.x, b.x), minOf(a.y, b.y))
    val size = Size(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y))
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

private fun DrawScope.drawMark(mark: PlanMark) {
    drawIntoCanvas { drawPlanMark(it.nativeCanvas, mark) }
}

private fun DrawScope.drawPlanText(text: String, point: Offset, textSize: Float, color: Color, paint: Paint) {
    if (text.isBlank()) return
    drawIntoCanvas { canvas ->
        paint.textSize = textSize
        paint.color = color.toArgb()
        text.lines().forEachIndexed { index, line ->
            canvas.nativeCanvas.drawText(line, point.x, point.y + textSize * (index + 1), paint)
        }
    }
}

private const val TILE_ZOOM_THRESHOLD = 2.0f
private const val TILE_CLEAR_THRESHOLD = 1.7f
private const val TILE_DEBOUNCE_MS = 120L
private const val MAX_TILE_PIXELS = 2048f

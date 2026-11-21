package com.thedirepack.constructioncalc.feature.drawings

import android.graphics.Bitmap
import android.graphics.Paint
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.data.drawPlanMark
import com.thedirepack.constructioncalc.core.model.PlanMark
import com.thedirepack.constructioncalc.core.model.PlanPoint
import com.thedirepack.constructioncalc.core.model.PlanZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.math.min

internal enum class DrawingMode(val title: String, val hint: String) {
    Navigate("Pan", "Drag to pan. Pinch to zoom."),
    Zone("Zone", "Tap or drag between two opposite corners."),
    Reference("Calibrate", "Tap or drag between the ends of a known dimension."),
    Measure("Measure", "Tap or drag between the ends of a line inside a calibrated zone."),
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
}

internal data class InkPoint(val point: PlanPoint, val pressure: Float)

private data class PageBitmap(val image: Bitmap, val width: Float, val height: Float)

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
        zoom = (zoom * factor).coerceIn(1f, 8f)
        val after = transform(page)
        scroll += centroid + pan - after.toScreen(point)
        val rotatedSize = pageSize(page)
        val x = ((rotatedSize.width * after.scale - size.width) / 2 + size.width * .25f).coerceAtLeast(0f)
        val y = ((rotatedSize.height * after.scale - size.height) / 2 + size.height * .25f).coerceAtLeast(0f)
        scroll = Offset(scroll.x.coerceIn(-x, x), scroll.y.coerceIn(-y, y))
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

internal data class PlanLine(
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
    lines: List<PlanLine>,
    marks: List<PlanMark>,
    pending: PlanPoint?,
    onPlace: (PlanPoint) -> Unit,
    onLine: (PlanPoint, PlanPoint) -> Unit,
    onInk: (List<InkPoint>) -> Unit,
    modifier: Modifier = Modifier,
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
    val background = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier.clipToBounds().background(background), contentAlignment = Alignment.Center) {
        when (val current = state) {
            is PageState.Loading -> CircularProgressIndicator()
            is PageState.Error -> Text(current.message)
            is PageState.Loaded -> {
                val page = current.page
                val viewport = remember(page) { PageViewport() }
                var preview by remember(page, mode) { mutableStateOf(emptyList<InkPoint>()) }
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .onSizeChanged { viewport.size = it }
                        .semantics { contentDescription = "Drawing page ${pageIndex + 1}. ${mode.hint}" }
                        .pointerInput(page, mode) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val first = viewport.transform(page).toPage(down.position)
                                val drawing = mode == DrawingMode.Pen || mode == DrawingMode.Highlight
                                val points = mutableListOf<InkPoint>()
                                if (page.hit(first) && drawing) {
                                    points += InkPoint(first, down.pressureForInk())
                                    preview = points.toList()
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
                                                    val point = viewport.transform(page).toPage(change.position)
                                                    onPage = page.hit(point)
                                                    if (onPage) {
                                                        last = point
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
                                            !moved && onPage -> place(last)
                                            moved && onPage && mode.usesSpan ->
                                                line(first, last)
                                        }
                                    }
                                } finally {
                                    preview = emptyList()
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
                            canvas.nativeCanvas.drawBitmap(
                                page.image,
                                null,
                                android.graphics.RectF(0f, 0f, page.width, page.height),
                                Paint(Paint.FILTER_BITMAP_FLAG),
                            )
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
                            val color = if (saved.isReference) Color(0xFF8E4700) else Color(0xFF126FB4)
                            drawLine(color, saved.first.offset(), saved.second.offset(), outline)
                            drawPlanText(saved.label, (saved.first.offset() + saved.second.offset()) / 2f, 12f / transform.scale, color)
                        }
                        for (mark in marks) drawMark(mark)
                        pending?.let {
                            val point = it.offset()
                            drawCircle(Color.White, 5f / transform.scale, point)
                            drawCircle(Color(0xFF126FB4), 3f / transform.scale, point)
                        }
                        when (mode) {
                            DrawingMode.Pen -> drawInk(preview, Color(0xFF155CBD), 1.8f)
                            DrawingMode.Highlight -> drawInk(preview, Color(0x66FFE43C), 11f)
                            DrawingMode.Zone, DrawingMode.Rectangle -> if (preview.size == 2) {
                                drawZone(preview[0].point.offset(), preview[1].point.offset(), Color(0xFF007A73), outline)
                            }
                            else -> if (preview.size == 2) {
                                drawLine(Color(0xFF126FB4), preview[0].point.offset(), preview[1].point.offset(), outline)
                            }
                        }
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
                        TextButton(onClick = { viewport.zoomFromCenter(page, 1.5f) }, enabled = viewport.zoom < 8f) {
                            Text("Zoom +")
                        }
                        TextButton(onClick = { viewport.rotate() }) { Text("Rotate") }
                    }
                }
            }
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

private fun DrawScope.drawInk(points: List<InkPoint>, color: Color, width: Float) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        drawCircle(color, width * points[0].pressure / 2, points[0].point.offset())
        return
    }
    if (color.alpha < 1f) {
        // One stroked path so translucent highlighter segments do not darken on their seams.
        val path = Path().apply {
            moveTo(points[0].point.x.toFloat(), points[0].point.y.toFloat())
            for (point in points.drop(1)) lineTo(point.point.x.toFloat(), point.point.y.toFloat())
        }
        drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round))
        return
    }
    for ((a, b) in points.zipWithNext()) {
        drawLine(color, a.point.offset(), b.point.offset(), width * (a.pressure + b.pressure) / 2, StrokeCap.Round)
    }
}

private fun DrawScope.drawMark(mark: PlanMark) {
    drawIntoCanvas { drawPlanMark(it.nativeCanvas, mark) }
}

private fun DrawScope.drawPlanText(text: String, point: Offset, textSize: Float, color: Color) {
    if (text.isBlank()) return
    drawIntoCanvas { canvas ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            this.color = color.toArgb()
        }
        text.lines().forEachIndexed { index, line ->
            canvas.nativeCanvas.drawText(line, point.x, point.y + textSize * (index + 1), paint)
        }
    }
}

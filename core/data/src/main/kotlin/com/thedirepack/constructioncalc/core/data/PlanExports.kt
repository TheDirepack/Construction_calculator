package com.thedirepack.constructioncalc.core.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.PlanDrawing
import com.thedirepack.constructioncalc.core.model.PlanMark
import com.thedirepack.constructioncalc.core.model.PlanMarkKind
import com.thedirepack.constructioncalc.core.model.PlanSheetSource
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Bounds in displayed source-page points, with the origin at the top left. */
data class PlanExportCrop(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(listOf(left, top, right, bottom).all(Float::isFinite))
        require(left >= 0 && top >= 0 && right > left && bottom > top)
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

object PlanExports {
    fun writeJson(drawing: PlanDrawing, out: OutputStream) {
        val bytes = DrawingJson.encode(drawing).encodeToByteArray()
        if (bytes.size > PlanFiles.MAX_JSON_BYTES) throw IOException("Drawing JSON is too large to export.")
        out.write(bytes)
    }

    /** Adds a vector form to each marked source page. The PDF in project storage is never written. */
    fun writePdf(
        context: Context,
        drawing: PlanDrawing,
        out: OutputStream,
        format: (Quantity) -> String,
        routeLabel: (runId: String) -> String = { "Run" },
    ) {
        val source = PlanFiles(context).file(drawing.sha256)
        val markedPages = (drawing.marks.map { it.page } + drawing.measurements.map { it.page } + drawing.routes.map { it.page })
            .distinct().sorted()
        if (markedPages.isEmpty()) {
            source.inputStream().use { it.copyTo(out) }
            return
        }
        val overlay = File.createTempFile("plan-overlay-", ".pdf", context.cacheDir)
        try {
            val sizes = writeOverlay(source, overlay, drawing, markedPages, format, routeLabel)
            PDFBoxResourceLoader.init(context.applicationContext)
            val memory = { MemoryUsageSetting.setupMixed(24L * 1024 * 1024).setTempDir(context.cacheDir) }
            PDDocument.load(source, memory()).use { original ->
                PDDocument.load(overlay, memory()).use { markup ->
                    val layers = LayerUtility(original)
                    markedPages.forEachIndexed { index, pageNumber ->
                        val page = original.getPage(pageNumber)
                        val form = layers.importPageAsForm(markup, index)
                        val (width, height) = sizes[index]
                        PDPageContentStream(original, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                            stream.saveGraphicsState()
                            stream.transform(overlayMatrix(page, width.toFloat(), height.toFloat()))
                            stream.drawForm(form)
                            stream.restoreGraphicsState()
                        }
                    }
                    original.save(out)
                }
            }
        } finally {
            overlay.delete()
        }
    }

    private fun writeOverlay(
        source: File,
        overlay: File,
        drawing: PlanDrawing,
        markedPages: List<Int>,
        format: (Quantity) -> String,
        routeLabel: (String) -> String,
    ): List<Pair<Int, Int>> {
        val descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try {
            PdfRenderer(descriptor)
        } catch (error: Exception) {
            descriptor.close()
            throw error
        }
        val sizes = renderer.use {
            markedPages.map { page ->
                if (page !in 0 until it.pageCount) throw IOException("A markup refers to a missing PDF page.")
                it.openPage(page).use { original -> original.width to original.height }
            }
        }
        val document = PdfDocument()
        try {
            markedPages.forEachIndexed { index, pageNumber ->
                val (width, height) = sizes[index]
                val page = document.startPage(PdfDocument.PageInfo.Builder(width, height, index + 1).create())
                try {
                    drawAnnotations(page.canvas, drawing, pageNumber, format, routeLabel)
                } finally {
                    document.finishPage(page)
                }
            }
            overlay.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
        return sizes
    }

    private fun drawAnnotations(
        canvas: Canvas,
        drawing: PlanDrawing,
        pageNumber: Int,
        format: (Quantity) -> String,
        routeLabel: (String) -> String,
    ) {
        drawing.marks.filter { it.page == pageNumber }.forEach { drawPlanMark(canvas, it) }
        drawing.routes.filter { it.page == pageNumber }.forEach { route ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xcc7b1fa2.toInt()
                strokeWidth = 2f
                style = Paint.Style.STROKE
                strokeJoin = Paint.Join.ROUND
            }
            val path = Path().apply {
                moveTo(route.points.first().x.toFloat(), route.points.first().y.toFloat())
                route.points.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) }
            }
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            paint.textSize = 10f
            paint.typeface = Typeface.DEFAULT_BOLD
            val start = route.points.first()
            val name = routeLabel(route.runId)
            val label = if (route.circuitTag.isBlank()) name else "$name · ${route.circuitTag}"
            canvas.drawText(label, start.x.toFloat() + 3f, start.y.toFloat() - 4f, paint)
        }
        drawing.measurements.filter { it.page == pageNumber }.forEach { measure ->
            val value = drawing.length(measure)?.let { "≈ ${format(it)}" } ?: "Uncalibrated"
            val label = if (measure.label.isBlank()) value else "${measure.label}: $value"
            val x1 = measure.first.x.toFloat()
            val y1 = measure.first.y.toFloat()
            val x2 = measure.second.x.toFloat()
            val y2 = measure.second.y.toFloat()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff08765d.toInt(); strokeWidth = 1.5f }
            canvas.drawLine(x1, y1, x2, y2, paint)
            canvas.drawCircle(x1, y1, 2f, paint)
            canvas.drawCircle(x2, y2, 2f, paint)
            paint.textSize = 11f
            paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText(label, (x1 + x2) / 2 + 3f, (y1 + y2) / 2 - 4f, paint)
        }
    }

    /** A flattened sheet or viewport crop. Only visible pixels enter the exported PDF. */
    fun writeSheetPdf(
        context: Context,
        drawing: PlanDrawing,
        source: PlanSheetSource,
        crop: PlanExportCrop?,
        out: OutputStream,
        format: (Quantity) -> String,
        routeLabel: (String) -> String,
    ) {
        require(source.drawingId == drawing.id && source.sha256 == drawing.sha256)
        val file = PlanFiles(context).file(source.sha256)
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(source.page).use { page ->
                    val region = crop ?: PlanExportCrop(0f, 0f, page.width.toFloat(), page.height.toFloat())
                    require(region.right <= page.width && region.bottom <= page.height)
                    val detail = min(3f, 4096f / maxOf(region.width, region.height))
                    val bitmap = Bitmap.createBitmap(
                        ceil(region.width * detail).toInt().coerceAtLeast(1),
                        ceil(region.height * detail).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        val map = android.graphics.Matrix().apply {
                            setTranslate(-region.left, -region.top)
                            postScale(bitmap.width / region.width, bitmap.height / region.height)
                        }
                        page.render(bitmap, null, map, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        val pixels = Canvas(bitmap)
                        pixels.concat(map)
                        drawAnnotations(pixels, drawing, source.page, format, routeLabel)
                        writeStampedSheet(bitmap, region, source, out)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    private fun writeStampedSheet(bitmap: Bitmap, region: PlanExportCrop, source: PlanSheetSource, out: OutputStream) {
        val header = 36
        val width = maxOf(180, ceil(region.width).toInt())
        val left = (width - region.width) / 2
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(width, ceil(region.height).toInt() + header, 1).create())
            try {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                val lines = listOf("Sheet ${source.sheetId}", source.revision?.let { "Revision $it" } ?: "Revision unassigned")
                paint.typeface = Typeface.DEFAULT_BOLD
                paint.textSize = 11f
                lines.forEachIndexed { index, line ->
                    paint.textScaleX = 1f
                    paint.textScaleX = min(1f, (width - 16f) / paint.measureText(line).coerceAtLeast(1f))
                    page.canvas.drawText(line, 8f, 13f + index * 14f, paint)
                }
                page.canvas.drawBitmap(bitmap, null, RectF(left, header.toFloat(), left + region.width, header + region.height), paint)
            } finally {
                document.finishPage(page)
            }
            document.writeTo(out)
        } finally {
            document.close()
        }
    }

    /** The form uses bottom-left coordinates in the displayed page orientation. */
    private fun overlayMatrix(page: PDPage, width: Float, height: Float): Matrix {
        val crop = page.cropBox
        val x = crop.lowerLeftX
        val y = crop.lowerLeftY
        val w = crop.width
        val h = crop.height
        val rotation = ((page.rotation % 360) + 360) % 360
        return when (rotation) {
            90 -> Matrix(0f, h / width, -w / height, 0f, x + w, y)
            180 -> Matrix(-w / width, 0f, 0f, -h / height, x + w, y + h)
            270 -> Matrix(0f, -h / width, w / height, 0f, x, y + h)
            else -> Matrix(w / width, 0f, 0f, h / height, x, y)
        }
    }
}

/**
 * Used for the on-screen markup and its vector appearance in an exported PDF. The editor passes
 * its own [paint] and [path] so drawing every mark each frame allocates nothing; both are reset here.
 */
fun drawPlanMark(canvas: Canvas, mark: PlanMark, paint: Paint = Paint(), path: Path = Path()) {
    val points = mark.points
    val first = points.first()
    val last = points.last()
    val x = first.x.toFloat()
    val y = first.y.toFloat()
    val endX = last.x.toFloat()
    val endY = last.y.toFloat()
    val width = mark.width.toFloat()
    paint.reset()
    paint.isAntiAlias = true
    paint.color = mark.color?.toInt() ?: if (mark.kind == PlanMarkKind.HIGHLIGHT) 0x66ffe43c else 0xff155cbd.toInt()
    paint.strokeWidth = width
    paint.strokeCap = Paint.Cap.ROUND
    paint.strokeJoin = Paint.Join.ROUND
    when (mark.kind) {
        PlanMarkKind.INK, PlanMarkKind.HIGHLIGHT -> {
            if (points.size == 1) {
                canvas.drawCircle(x, y, width * (mark.pressures.firstOrNull() ?: 1f) / 2, paint)
            } else if (paint.alpha == 255 && mark.pressures.isNotEmpty()) {
                for (index in 0 until points.lastIndex) {
                    val from = points[index]
                    val to = points[index + 1]
                    paint.strokeWidth = width * (mark.pressures[index] + mark.pressures[index + 1]) / 2
                    canvas.drawLine(from.x.toFloat(), from.y.toFloat(), to.x.toFloat(), to.y.toFloat(), paint)
                }
            } else {
                path.reset()
                path.moveTo(x, y)
                for (index in 1 until points.size) path.lineTo(points[index].x.toFloat(), points[index].y.toFloat())
                paint.style = Paint.Style.STROKE
                canvas.drawPath(path, paint)
            }
        }
        PlanMarkKind.LINE, PlanMarkKind.ARROW -> {
            canvas.drawLine(x, y, endX, endY, paint)
            if (mark.kind == PlanMarkKind.ARROW) drawArrowHead(canvas, paint, x, y, endX, endY)
        }
        PlanMarkKind.RECTANGLE -> {
            paint.style = Paint.Style.STROKE
            canvas.drawRect(minOf(x, endX), minOf(y, endY), maxOf(x, endX), maxOf(y, endY), paint)
        }
        PlanMarkKind.TEXT, PlanMarkKind.CALLOUT -> {
            val callout = mark.kind == PlanMarkKind.CALLOUT
            paint.textSize = width
            val left = if (callout) endX else x
            val top = if (callout) endY else y
            val inset = if (callout) width * .25f else 0f
            if (callout) {
                val color = paint.color
                val lines = mark.text.lines()
                val right = left + lines.maxOf { paint.measureText(it) } + inset * 2
                val bottom = top + (lines.size + 1) * width + inset
                paint.strokeWidth = maxOf(1f, width / 8)
                val edgeX = x.coerceIn(left, right)
                val edgeY = y.coerceIn(top, bottom)
                canvas.drawLine(x, y, edgeX, edgeY, paint)
                drawArrowHead(canvas, paint, edgeX, edgeY, x, y)
                paint.color = android.graphics.Color.WHITE
                canvas.drawRect(left, top, right, bottom, paint)
                paint.color = color
                paint.style = Paint.Style.STROKE
                canvas.drawRect(left, top, right, bottom, paint)
                paint.style = Paint.Style.FILL
            }
            var line = 0
            var start = 0
            val text = mark.text
            while (start <= text.length) {
                val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
                canvas.drawText(text, start, end, left + inset, top + inset + width * (line + 1), paint)
                line++
                start = end + 1
            }
        }
        PlanMarkKind.SYMBOL -> mark.svg?.let { drawPlanSymbol(canvas, it, x, y, x + width, y + width) }
    }
}

private fun drawArrowHead(canvas: Canvas, paint: Paint, x: Float, y: Float, endX: Float, endY: Float) {
    val length = hypot(endX - x, endY - y)
    if (length == 0f) return
    val size = min(paint.strokeWidth * 5f, length * .45f)
    val angle = atan2(endY - y, endX - x)
    for (side in 0..1) {
        val direction = if (side == 0) angle - .52f else angle + .52f
        canvas.drawLine(endX, endY, endX - cos(direction) * size, endY - sin(direction) * size, paint)
    }
}

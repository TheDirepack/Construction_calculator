package com.thedirepack.constructioncalc.core.data

import android.content.Context
import android.graphics.Canvas
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
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

object PlanExports {
    fun writeJson(drawing: PlanDrawing, out: OutputStream) {
        val bytes = DrawingJson.encode(drawing).encodeToByteArray()
        if (bytes.size > PlanFiles.MAX_JSON_BYTES) throw IOException("Drawing JSON is too large to export.")
        out.write(bytes)
    }

    /** Adds a vector form to each marked source page. The PDF in project storage is never written. */
    fun writePdf(context: Context, drawing: PlanDrawing, out: OutputStream, format: (Quantity) -> String) {
        val source = PlanFiles(context).file(drawing.sha256)
        val markedPages = (drawing.marks.map { it.page } + drawing.measurements.map { it.page }).distinct().sorted()
        if (markedPages.isEmpty()) {
            source.inputStream().use { it.copyTo(out) }
            return
        }
        val overlay = File.createTempFile("plan-overlay-", ".pdf", context.cacheDir)
        try {
            val sizes = writeOverlay(source, overlay, drawing, markedPages, format)
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
                    drawing.marks.filter { it.page == pageNumber }.forEach { drawPlanMark(page.canvas, it) }
                    drawing.measurements.filter { it.page == pageNumber }.forEach { measure ->
                        val label = drawing.length(measure)?.let { "≈ ${format(it)}" } ?: "Uncalibrated"
                        val x1 = measure.first.x.toFloat()
                        val y1 = measure.first.y.toFloat()
                        val x2 = measure.second.x.toFloat()
                        val y2 = measure.second.y.toFloat()
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff08765d.toInt(); strokeWidth = 1.5f }
                        page.canvas.drawLine(x1, y1, x2, y2, paint)
                        page.canvas.drawCircle(x1, y1, 2f, paint)
                        page.canvas.drawCircle(x2, y2, 2f, paint)
                        paint.textSize = 11f
                        paint.typeface = Typeface.DEFAULT_BOLD
                        page.canvas.drawText(label, (x1 + x2) / 2 + 3f, (y1 + y2) / 2 - 4f, paint)
                    }
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

/** Used for the on-screen markup and its vector appearance in an exported PDF. */
fun drawPlanMark(canvas: Canvas, mark: PlanMark) {
    val first = mark.points.first()
    val last = mark.points.last()
    val x = first.x.toFloat()
    val y = first.y.toFloat()
    val endX = last.x.toFloat()
    val endY = last.y.toFloat()
    val width = mark.width.toFloat()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = mark.color?.toInt() ?: if (mark.kind == PlanMarkKind.HIGHLIGHT) 0x66ffe43c else 0xff155cbd.toInt()
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    when (mark.kind) {
        PlanMarkKind.INK, PlanMarkKind.HIGHLIGHT -> {
            val path = Path().apply {
                moveTo(x, y)
                mark.points.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) }
            }
            if (mark.points.size == 1) {
                canvas.drawCircle(x, y, width * (mark.pressures.firstOrNull() ?: 1f) / 2, paint)
            } else if (paint.alpha == 255 && mark.pressures.isNotEmpty()) {
                mark.points.zipWithNext().forEachIndexed { index, (from, to) ->
                    paint.strokeWidth = width * (mark.pressures[index] + mark.pressures[index + 1]) / 2
                    canvas.drawLine(from.x.toFloat(), from.y.toFloat(), to.x.toFloat(), to.y.toFloat(), paint)
                }
            } else {
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
        PlanMarkKind.TEXT -> {
            paint.textSize = width
            mark.text.lineSequence().forEachIndexed { line, text ->
                canvas.drawText(text, x, y + width * (line + 1), paint)
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
    for (direction in listOf(angle - .52f, angle + .52f)) {
        canvas.drawLine(endX, endY, endX - cos(direction) * size, endY - sin(direction) * size, paint)
    }
}

package com.thedirepack.constructioncalc.core.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.PlanArrowStyle
import com.thedirepack.constructioncalc.core.model.PlanDashStyle
import com.thedirepack.constructioncalc.core.model.PlanDrawing
import com.thedirepack.constructioncalc.core.model.PlanMark
import com.thedirepack.constructioncalc.core.model.PlanMarkKind
import com.thedirepack.constructioncalc.core.model.PlanPathMeasurement
import com.thedirepack.constructioncalc.core.model.PlanSheetSource
import com.thedirepack.constructioncalc.core.model.measurePath
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
import kotlin.math.roundToInt
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
        val markedPages = (
            drawing.marks.map { it.page } +
                drawing.measurements.map { it.page } +
                drawing.routes.map { it.page } +
                drawing.paths.map { it.page }
            )
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
        val dimensionFormatter: (PlanMark) -> String = { mark ->
            val measured = drawing.dimensionLength(mark)?.let { "≈ ${format(it)}" } ?: "Uncalibrated"
            if (mark.text.isBlank()) measured else "${mark.text}: $measured"
        }
        drawing.marks.filter { it.page == pageNumber }.forEach {
            drawPlanMark(canvas, it, dimensionLabel = dimensionFormatter)
        }
        drawing.paths.filter { it.page == pageNumber }.forEach { savedPath ->
            val androidPath = Path().apply {
                val first = savedPath.points.first()
                moveTo(first.x.toFloat(), first.y.toFloat())
                savedPath.points.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) }
                if (savedPath.closed) close()
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            if (savedPath.closed) {
                paint.color = 0x22007a73
                paint.style = Paint.Style.FILL
                canvas.drawPath(androidPath, paint)
            }
            paint.color = 0xff007a73.toInt()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawPath(androidPath, paint)

            val label = when (val measured = drawing.measurePath(savedPath)) {
                PlanPathMeasurement.CrossesZones -> "Spans multiple scales"
                PlanPathMeasurement.Uncalibrated -> "Uncalibrated"
                is PlanPathMeasurement.Measured ->
                    measured.area?.let { "Perimeter ≈ ${format(measured.length)} · Area ≈ ${format(it)}" }
                        ?: "Length ≈ ${format(measured.length)}"
            }
            paint.style = Paint.Style.FILL
            paint.textSize = 11f
            paint.typeface = Typeface.DEFAULT_BOLD
            val anchor = savedPath.points.first()
            canvas.drawText(label, anchor.x.toFloat() + 4f, anchor.y.toFloat() - 4f, paint)
        }
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

private val dashCache = LruCache<String, DashPathEffect>(32)

private fun dashEffectFor(dash: PlanDashStyle, width: Float): DashPathEffect? {
    if (dash == PlanDashStyle.SOLID) return null
    val key = "${dash.name}:${(width * 10f).roundToInt()}"
    return dashCache.get(key) ?: when (dash) {
        PlanDashStyle.SOLID -> null
        PlanDashStyle.DASHED -> DashPathEffect(floatArrayOf(maxOf(4f, width * 4f), maxOf(3f, width * 2.5f)), 0f)
        PlanDashStyle.DOTTED -> DashPathEffect(floatArrayOf(maxOf(1f, width * 0.6f), maxOf(3f, width * 2.5f)), 0f)
    }?.also { dashCache.put(key, it) }
}

/**
 * Used for the on-screen markup and its vector appearance in an exported PDF. The editor passes
 * its own [paint] and [path] so drawing every mark each frame allocates nothing; both are reset here.
 */
fun drawPlanMark(
    canvas: Canvas,
    mark: PlanMark,
    paint: Paint = Paint(),
    path: Path = Path(),
    dimensionLabel: ((PlanMark) -> String?)? = null,
) {
    val points = mark.points
    val first = points.first()
    val last = points.last()
    val x = first.x.toFloat()
    val y = first.y.toFloat()
    val endX = last.x.toFloat()
    val endY = last.y.toFloat()
    val width = mark.width.toFloat()
    val baseColor = mark.color?.toInt() ?: if (mark.kind == PlanMarkKind.HIGHLIGHT) 0x66ffe43c else 0xff155cbd.toInt()
    val effectiveAlpha = (Color.alpha(baseColor) * mark.opacity).roundToInt().coerceIn(0, 255)
    val dashEffect = dashEffectFor(mark.dash, width)

    fun resetStrokePaint() {
        paint.reset()
        paint.isAntiAlias = true
        paint.color = baseColor
        paint.alpha = effectiveAlpha
        paint.strokeWidth = width
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.pathEffect = dashEffect
    }

    fun applyFillPaint(): Boolean {
        val fill = mark.fillColor?.toInt() ?: return false
        if (mark.fillOpacity <= 0f) return false
        paint.reset()
        paint.isAntiAlias = true
        paint.color = fill
        paint.alpha = (255f * mark.fillOpacity * mark.opacity).roundToInt().coerceIn(0, 255)
        paint.style = Paint.Style.FILL
        return true
    }

    resetStrokePaint()
    when (mark.kind) {
        PlanMarkKind.INK, PlanMarkKind.HIGHLIGHT -> {
            paint.pathEffect = null
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
            paint.style = Paint.Style.STROKE
            canvas.drawLine(x, y, endX, endY, paint)
            paint.pathEffect = null
            val arrowStyle = mark.arrowStyle ?: if (mark.kind == PlanMarkKind.ARROW) PlanArrowStyle.END else PlanArrowStyle.NONE
            drawStyledArrowHeads(canvas, paint, path, arrowStyle, x, y, endX, endY)
        }
        PlanMarkKind.RECTANGLE -> {
            val left = minOf(x, endX)
            val top = minOf(y, endY)
            val right = maxOf(x, endX)
            val bottom = maxOf(y, endY)
            if (applyFillPaint()) {
                canvas.drawRect(left, top, right, bottom, paint)
                resetStrokePaint()
            }
            paint.style = Paint.Style.STROKE
            canvas.drawRect(left, top, right, bottom, paint)
        }
        PlanMarkKind.TEXT -> {
            paint.pathEffect = null
            paint.textSize = width
            val lines = mark.text.lines()
            if (applyFillPaint()) {
                val inset = width * 0.25f
                val maxW = lines.maxOfOrNull { paint.measureText(it) } ?: 0f
                canvas.drawRoundRect(
                    x - inset,
                    y - inset * 0.4f,
                    x + maxW + inset,
                    y + lines.size * width + inset * 1.1f,
                    inset,
                    inset,
                    paint,
                )
                resetStrokePaint()
                paint.pathEffect = null
                paint.textSize = width
            }
            paint.style = Paint.Style.FILL
            drawMultilineText(canvas, mark.text, x, y, width, paint)
        }
        PlanMarkKind.CALLOUT -> {
            paint.pathEffect = null
            drawCalloutNote(
                canvas = canvas,
                paint = paint,
                anchorX = x,
                anchorY = y,
                boxLeft = endX,
                boxTop = endY,
                edgeStartX = x,
                edgeStartY = y,
                text = mark.text,
                fontSize = width,
                strokeColor = baseColor,
                strokeAlpha = effectiveAlpha,
                fillColor = mark.fillColor?.toInt() ?: Color.WHITE,
                fillAlpha = if (mark.fillColor != null && mark.fillOpacity > 0f) {
                    (255f * mark.fillOpacity * mark.opacity).roundToInt().coerceIn(0, 255)
                } else {
                    effectiveAlpha
                },
            )
        }
        PlanMarkKind.DIMENSION -> {
            val len = hypot(endX - x, endY - y)
            if (len <= 0f) return
            val nx = -(endY - y) / len
            val ny = (endX - x) / len
            val tickHalf = maxOf(6f, width * 3.5f)
            // Witness extension ticks are always solid.
            paint.pathEffect = null
            paint.style = Paint.Style.STROKE
            canvas.drawLine(x - nx * tickHalf, y - ny * tickHalf, x + nx * tickHalf, y + ny * tickHalf, paint)
            canvas.drawLine(endX - nx * tickHalf, endY - ny * tickHalf, endX + nx * tickHalf, endY + ny * tickHalf, paint)
            // Main dimension line
            paint.pathEffect = dashEffect
            canvas.drawLine(x, y, endX, endY, paint)
            paint.pathEffect = null
            val arrowStyle = mark.arrowStyle ?: PlanArrowStyle.BOTH
            drawStyledArrowHeads(canvas, paint, path, arrowStyle, x, y, endX, endY)
            // Readable centered label over white backing
            val labelText = dimensionLabel?.invoke(mark) ?: mark.text.ifBlank { "Dimension" }
            val labelSize = maxOf(10f, width * 4.5f).coerceAtMost(18f)
            paint.style = Paint.Style.FILL
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = labelSize
            val textW = paint.measureText(labelText)
            val padX = labelSize * 0.35f
            val padY = labelSize * 0.25f
            val cx = (x + endX) / 2f
            val cy = (y + endY) / 2f
            paint.color = Color.WHITE
            paint.alpha = (235f * mark.opacity).roundToInt().coerceIn(0, 255)
            canvas.drawRoundRect(
                cx - textW / 2f - padX,
                cy - labelSize * 0.65f - padY,
                cx + textW / 2f + padX,
                cy + labelSize * 0.35f + padY,
                3f,
                3f,
                paint,
            )
            paint.color = baseColor
            paint.alpha = effectiveAlpha
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(labelText, cx, cy + labelSize * 0.25f, paint)
            paint.textAlign = Paint.Align.LEFT
        }
        PlanMarkKind.CLOUD -> {
            val left = minOf(x, endX)
            val top = minOf(y, endY)
            val right = maxOf(x, endX)
            val bottom = maxOf(y, endY)
            buildCloudPath(path, left, top, right, bottom, maxOf(10f, width * 5f))
            if (applyFillPaint()) {
                canvas.drawPath(path, paint)
                resetStrokePaint()
            }
            paint.style = Paint.Style.STROKE
            canvas.drawPath(path, paint)
        }
        PlanMarkKind.CLOUD_CALLOUT -> {
            val p1 = points.getOrElse(1) { first }
            val left = minOf(x, p1.x.toFloat())
            val top = minOf(y, p1.y.toFloat())
            val right = maxOf(x, p1.x.toFloat())
            val bottom = maxOf(y, p1.y.toFloat())
            val fontSize = maxOf(width, 8f)
            val strokeW = maxOf(1.5f, fontSize / 7.5f)
            paint.strokeWidth = strokeW
            buildCloudPath(path, left, top, right, bottom, maxOf(10f, strokeW * 6f))
            if (applyFillPaint()) {
                canvas.drawPath(path, paint)
                resetStrokePaint()
                paint.strokeWidth = strokeW
            }
            paint.style = Paint.Style.STROKE
            canvas.drawPath(path, paint)

            val boxCorner = points.getOrNull(2)
            val boxLeft = boxCorner?.x?.toFloat() ?: (right + 18f)
            val boxTop = boxCorner?.y?.toFloat() ?: top
            val anchorX = boxLeft.coerceIn(left, right)
            val anchorY = boxTop.coerceIn(top, bottom)
            paint.pathEffect = null
            drawCalloutNote(
                canvas = canvas,
                paint = paint,
                anchorX = anchorX,
                anchorY = anchorY,
                boxLeft = boxLeft,
                boxTop = boxTop,
                edgeStartX = anchorX,
                edgeStartY = anchorY,
                text = mark.text.ifBlank { "Revision" },
                fontSize = fontSize,
                strokeColor = baseColor,
                strokeAlpha = effectiveAlpha,
                fillColor = mark.fillColor?.toInt() ?: Color.WHITE,
                fillAlpha = if (mark.fillColor != null && mark.fillOpacity > 0f) {
                    (255f * mark.fillOpacity * mark.opacity).roundToInt().coerceIn(0, 255)
                } else {
                    effectiveAlpha
                },
            )
        }
        PlanMarkKind.PHOTO -> {
            paint.pathEffect = null
            val badge = maxOf(18f, min(width, 48f))
            val half = badge / 2f
            paint.style = Paint.Style.FILL
            paint.color = mark.fillColor?.toInt() ?: baseColor
            paint.alpha = effectiveAlpha
            canvas.drawRoundRect(x - half, y - half, x + half, y + half, badge * 0.22f, badge * 0.22f, paint)
            // Inner camera body & lens in white
            paint.color = Color.WHITE
            paint.alpha = effectiveAlpha
            val bodyW = badge * 0.56f
            val bodyH = badge * 0.38f
            canvas.drawRoundRect(x - bodyW / 2f, y - bodyH / 2f + badge * 0.04f, x + bodyW / 2f, y + bodyH / 2f + badge * 0.04f, 2f, 2f, paint)
            paint.color = mark.fillColor?.toInt() ?: baseColor
            canvas.drawCircle(x, y + badge * 0.04f, badge * 0.11f, paint)
            if (mark.text.isNotBlank()) {
                drawBadgeCaption(canvas, paint, x + half + 4f, y, mark.text, maxOf(10f, badge * 0.45f), baseColor, effectiveAlpha)
            }
        }
        PlanMarkKind.DETAIL_LINK -> {
            paint.pathEffect = null
            val badge = maxOf(18f, min(width, 48f))
            val r = badge / 2f
            paint.style = Paint.Style.FILL
            paint.color = mark.fillColor?.toInt() ?: Color.WHITE
            paint.alpha = (235f * mark.opacity).roundToInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, r, paint)
            paint.style = Paint.Style.STROKE
            paint.color = baseColor
            paint.alpha = effectiveAlpha
            paint.strokeWidth = maxOf(1.5f, badge * 0.09f)
            canvas.drawCircle(x, y, r, paint)
            canvas.drawCircle(x, y, r * 0.72f, paint)
            paint.style = Paint.Style.FILL
            paint.typeface = Typeface.DEFAULT_BOLD
            val label = mark.text.ifBlank { "Detail" }
            drawBadgeCaption(canvas, paint, x + r + 4f, y, label, maxOf(10f, badge * 0.45f), baseColor, effectiveAlpha)
        }
        PlanMarkKind.SYMBOL -> mark.svg?.let { drawPlanSymbol(canvas, it, x, y, x + width, y + width) }
    }
}

private fun drawMultilineText(canvas: Canvas, text: String, left: Float, top: Float, lineHeight: Float, paint: Paint) {
    var line = 0
    var start = 0
    while (start <= text.length) {
        val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        canvas.drawText(text, start, end, left, top + lineHeight * (line + 1), paint)
        line++
        start = end + 1
    }
}

private fun drawCalloutNote(
    canvas: Canvas,
    paint: Paint,
    anchorX: Float,
    anchorY: Float,
    boxLeft: Float,
    boxTop: Float,
    edgeStartX: Float,
    edgeStartY: Float,
    text: String,
    fontSize: Float,
    strokeColor: Int,
    strokeAlpha: Int,
    fillColor: Int,
    fillAlpha: Int,
) {
    paint.textSize = fontSize
    val inset = fontSize * .25f
    val lines = text.lines()
    val right = boxLeft + (lines.maxOfOrNull { paint.measureText(it) } ?: 0f) + inset * 2
    val bottom = boxTop + (lines.size + 1) * fontSize + inset
    paint.strokeWidth = maxOf(1f, fontSize / 8)
    paint.color = strokeColor
    paint.alpha = strokeAlpha
    val edgeX = edgeStartX.coerceIn(boxLeft, right)
    val edgeY = edgeStartY.coerceIn(boxTop, bottom)
    if (edgeX != anchorX || edgeY != anchorY || boxLeft !in anchorX..anchorX) {
        canvas.drawLine(anchorX, anchorY, edgeX, edgeY, paint)
        drawArrowHead(canvas, paint, edgeX, edgeY, anchorX, anchorY)
    } else {
        canvas.drawLine(anchorX, anchorY, edgeX, edgeY, paint)
        drawArrowHead(canvas, paint, edgeX, edgeY, anchorX, anchorY)
    }
    paint.style = Paint.Style.FILL
    paint.color = fillColor
    paint.alpha = fillAlpha
    canvas.drawRect(boxLeft, boxTop, right, bottom, paint)
    paint.color = strokeColor
    paint.alpha = strokeAlpha
    paint.style = Paint.Style.STROKE
    canvas.drawRect(boxLeft, boxTop, right, bottom, paint)
    paint.style = Paint.Style.FILL
    drawMultilineText(canvas, text, boxLeft + inset, boxTop + inset, fontSize, paint)
}

private fun drawBadgeCaption(
    canvas: Canvas,
    paint: Paint,
    x: Float,
    centerY: Float,
    text: String,
    textSize: Float,
    color: Int,
    alpha: Int,
) {
    paint.style = Paint.Style.FILL
    paint.typeface = Typeface.DEFAULT_BOLD
    paint.textSize = textSize
    val pad = textSize * 0.25f
    val w = paint.measureText(text)
    paint.color = Color.WHITE
    paint.alpha = (alpha * 0.92f).roundToInt().coerceIn(0, 255)
    canvas.drawRoundRect(x - pad, centerY - textSize * 0.7f - pad, x + w + pad, centerY + textSize * 0.3f + pad, 3f, 3f, paint)
    paint.color = color
    paint.alpha = alpha
    canvas.drawText(text, x, centerY + textSize * 0.2f, paint)
}

private fun buildCloudPath(path: Path, left: Float, top: Float, right: Float, bottom: Float, stepTarget: Float) {
    path.reset()
    val w = right - left
    val h = bottom - top
    if (w <= 0f || h <= 0f) {
        path.addRect(left, top, right, bottom, Path.Direction.CW)
        return
    }
    path.moveTo(left, top)
    fun addEdge(x1: Float, y1: Float, x2: Float, y2: Float, outX: Float, outY: Float) {
        val len = hypot(x2 - x1, y2 - y1)
        val count = (len / stepTarget).roundToInt().coerceIn(2, 32)
        val segLen = len / count
        val bulge = segLen * 0.45f
        for (i in 0 until count) {
            val t0 = i.toFloat() / count
            val t1 = (i + 1).toFloat() / count
            val sx = x1 + (x2 - x1) * t0
            val sy = y1 + (y2 - y1) * t0
            val ex = x1 + (x2 - x1) * t1
            val ey = y1 + (y2 - y1) * t1
            val cx = (sx + ex) / 2f + outX * bulge
            val cy = (sy + ey) / 2f + outY * bulge
            path.quadTo(cx, cy, ex, ey)
        }
    }
    addEdge(left, top, right, top, 0f, -1f)
    addEdge(right, top, right, bottom, 1f, 0f)
    addEdge(right, bottom, left, bottom, 0f, 1f)
    addEdge(left, bottom, left, top, -1f, 0f)
    path.close()
}

private fun drawStyledArrowHeads(
    canvas: Canvas,
    paint: Paint,
    path: Path,
    style: PlanArrowStyle,
    x: Float,
    y: Float,
    endX: Float,
    endY: Float,
) {
    when (style) {
        PlanArrowStyle.NONE -> Unit
        PlanArrowStyle.END -> drawArrowHead(canvas, paint, x, y, endX, endY)
        PlanArrowStyle.BOTH -> {
            drawArrowHead(canvas, paint, x, y, endX, endY)
            drawArrowHead(canvas, paint, endX, endY, x, y)
        }
        PlanArrowStyle.FILLED_END -> drawFilledArrowHead(canvas, paint, path, x, y, endX, endY)
        PlanArrowStyle.FILLED_BOTH -> {
            drawFilledArrowHead(canvas, paint, path, x, y, endX, endY)
            drawFilledArrowHead(canvas, paint, path, endX, endY, x, y)
        }
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

private fun drawFilledArrowHead(canvas: Canvas, paint: Paint, path: Path, x: Float, y: Float, endX: Float, endY: Float) {
    val length = hypot(endX - x, endY - y)
    if (length == 0f) return
    val size = min(paint.strokeWidth * 5.5f, length * .45f)
    val angle = atan2(endY - y, endX - x)
    val prevStyle = paint.style
    paint.style = Paint.Style.FILL
    path.reset()
    path.moveTo(endX, endY)
    path.lineTo(endX - cos(angle - .48f) * size, endY - sin(angle - .48f) * size)
    path.lineTo(endX - cos(angle + .48f) * size, endY - sin(angle + .48f) * size)
    path.close()
    canvas.drawPath(path, paint)
    paint.style = prevStyle
}

package com.thedirepack.constructioncalc.feature.drawings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PointF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.thedirepack.constructioncalc.core.plans.GrayImage
import com.thedirepack.constructioncalc.core.plans.LineMap
import com.thedirepack.constructioncalc.core.plans.RasterLines
import com.thedirepack.constructioncalc.core.plans.VectorPathCollector
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.opencv.android.OpenCVLoader
import java.io.File
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Builds and caches each page's [LineMap] ahead of time from the source PDF:
 * - Vector (CAD) pages use PDFBox-Android's graphics stream engine to read drawn paths directly.
 * - Scanned or photographed pages render once at the embedded image's native resolution (within a
 *   memory cap) and find lines with OpenCV [RasterLines].
 *
 * Results are kept in memory and cached on disk under [Context.getCacheDir], so zoom and pan never
 * recompute or affect snap geometry.
 */
internal class PlanLineStore(private val context: Context) {
    private val cacheRoot = File(context.cacheDir, "plan-lines")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getOrBuild(sha256: String, file: File, pageIndex: Int): LineMap = withContext(Dispatchers.Default) {
        val key = "$sha256:$pageIndex"
        synchronized(memoryCache) { memoryCache[key] }?.let { return@withContext it }
        val cacheFile = cacheFile(sha256, pageIndex)
        if (cacheFile.isFile) {
            runCatching { json.decodeFromString<LineMap>(cacheFile.readText()) }.getOrNull()
                ?.takeIf { it.version == LineMap.VERSION }
                ?.let { cached ->
                    putMemory(key, cached)
                    return@withContext cached
                }
        }
        val built = buildLineMap(file, pageIndex)
        saveCache(cacheFile, built)
        putMemory(key, built)
        built
    }

    private fun buildLineMap(file: File, pageIndex: Int): LineMap {
        PDFBoxResourceLoader.init(context.applicationContext)
        val memory = MemoryUsageSetting.setupMixed(24L * 1024 * 1024).setTempDir(context.cacheDir)
        val (collector, vectorMap) = PDDocument.load(file, memory).use { doc ->
            val page = doc.getPage(pageIndex)
            val box = page.cropBox
            val c = VectorPathCollector(
                doubleArrayOf(
                    box.lowerLeftX.toDouble(),
                    box.lowerLeftY.toDouble(),
                    box.upperRightX.toDouble(),
                    box.upperRightY.toDouble(),
                ),
                page.rotation,
            )
            AndroidPdfPathEngine(page, c).processPage(page)
            c to c.lineMap()
        }
        val pageArea = max(1.0, collector.width * collector.height)
        val mainImage = collector.images.maxByOrNull { it.area }
        val looksLikeScan = mainImage != null && mainImage.area >= pageArea * 0.5 && vectorMap.lines.size < 20
        if (vectorMap.lines.isNotEmpty() && !looksLikeScan) {
            return vectorMap
        }
        // Scanned or photographed page: render at the embedded image's native resolution (capped
        // for mobile memory) and extract lines with OpenCV.
        val desiredScale = (mainImage?.pixelsPerPoint ?: DEFAULT_RASTER_SCALE).coerceIn(1.0, MAX_RASTER_SCALE)
        val maxSide = max(collector.width, collector.height).coerceAtLeast(1.0)
        val scale = min(desiredScale, MAX_RASTER_PIXELS / maxSide).coerceAtLeast(0.5)
        if (!openCvLoaded) {
            openCvLoaded = OpenCVLoader.initLocal()
        }
        if (!openCvLoaded) return vectorMap
        val gray = renderPageGrayscale(file, pageIndex, scale.toFloat()) ?: return vectorMap
        return RasterLines.find(gray, pointsPerPixel = 1.0 / scale)
    }

    private fun renderPageGrayscale(file: File, pageIndex: Int, scale: Float): GrayImage? =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(pageIndex).use { page ->
                    val width = ceil(page.width * scale).toInt().coerceIn(1, MAX_RASTER_PIXELS)
                    val height = ceil(page.height * scale).toInt().coerceIn(1, MAX_RASTER_PIXELS)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        val transform = Matrix().apply { setScale(scale, scale) }
                        page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val pixels = IntArray(width * height)
                        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                        GrayImage.fromArgb(width, height, pixels)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }

    private fun cacheFile(sha256: String, pageIndex: Int): File =
        File(File(cacheRoot, sha256), "page-$pageIndex-v${LineMap.VERSION}.json")

    private fun saveCache(target: File, map: LineMap) {
        runCatching {
            target.parentFile?.mkdirs()
            val temp = File.createTempFile("lines-", ".tmp", target.parentFile)
            try {
                temp.writeText(json.encodeToString(map))
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
            } catch (_: Exception) {
                temp.delete()
            }
        }
    }

    private fun putMemory(key: String, map: LineMap) {
        synchronized(memoryCache) {
            memoryCache[key] = map
            while (memoryCache.size > MAX_MEMORY_PAGES) {
                val oldest = memoryCache.entries.firstOrNull()?.key ?: break
                memoryCache.remove(oldest)
            }
        }
    }

    private class AndroidPdfPathEngine(page: PDPage, private val collector: VectorPathCollector) :
        PDFGraphicsStreamEngine(page) {
        private var currentPoint = PointF()

        private fun lineWidth(): Double {
            val lw = graphicsState.lineWidth.toDouble()
            val ctm = graphicsState.currentTransformationMatrix
            val sx = hypot(ctm.scaleX.toDouble(), ctm.shearY.toDouble())
            val sy = hypot(ctm.shearX.toDouble(), ctm.scaleY.toDouble())
            return lw * (sx + sy) / 2.0
        }

        override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) {
            collector.rectangle(
                p0.x.toDouble(), p0.y.toDouble(),
                p1.x.toDouble(), p1.y.toDouble(),
                p2.x.toDouble(), p2.y.toDouble(),
                p3.x.toDouble(), p3.y.toDouble(),
            )
            currentPoint = PointF(p0.x, p0.y)
        }

        override fun drawImage(pdImage: PDImage) {
            val ctm = graphicsState.currentTransformationMatrix
            collector.image(
                ctm.scaleX.toDouble(), ctm.shearY.toDouble(),
                ctm.shearX.toDouble(), ctm.scaleY.toDouble(),
                ctm.translateX.toDouble(), ctm.translateY.toDouble(),
                pdImage.width, pdImage.height,
            )
        }

        override fun clip(windingRule: Path.FillType) {}

        override fun moveTo(x: Float, y: Float) {
            collector.moveTo(x.toDouble(), y.toDouble())
            currentPoint = PointF(x, y)
        }

        override fun lineTo(x: Float, y: Float) {
            collector.lineTo(x.toDouble(), y.toDouble())
            currentPoint = PointF(x, y)
        }

        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            collector.curveTo(
                x1.toDouble(), y1.toDouble(),
                x2.toDouble(), y2.toDouble(),
                x3.toDouble(), y3.toDouble(),
            )
            currentPoint = PointF(x3, y3)
        }

        override fun getCurrentPoint(): PointF = currentPoint
        override fun closePath() = collector.closePath()
        override fun endPath() = collector.endPath()
        override fun strokePath() = collector.stroke(lineWidth())
        override fun fillPath(windingRule: Path.FillType) = collector.fill()
        override fun fillAndStrokePath(windingRule: Path.FillType) = collector.fillAndStroke(lineWidth())
        override fun shadingFill(shadingName: COSName) {}
    }

    private companion object {
        @Volatile
        private var openCvLoaded = false
        private val memoryCache = LinkedHashMap<String, LineMap>(8, 0.75f, true)
        private const val MAX_MEMORY_PAGES = 6
        private const val DEFAULT_RASTER_SCALE = 2.5
        private const val MAX_RASTER_SCALE = 4.0
        private const val MAX_RASTER_PIXELS = 3200
    }
}

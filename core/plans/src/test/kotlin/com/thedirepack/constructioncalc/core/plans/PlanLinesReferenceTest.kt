package com.thedirepack.constructioncalc.core.plans

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nu.pattern.OpenCV
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.graphics.image.PDImage
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Checks pre-computed [LineMap] snapping against the local blueprint reference set:
 * - Vector PDFs via Apache PDFBox's [PDFGraphicsStreamEngine] feeding [VectorPathCollector].
 * - Rendered, degraded and photographed sheets via OpenCV [RasterLines.find].
 */
class PlanLinesReferenceTest {
    private val library = listOf(File("../../blueprints_and_libraries"), File("blueprints_and_libraries"))
        .firstOrNull { File(it, "_snap_reference/cases.json").isFile }
    private val folder get() = library?.let { File(it, "_snap_reference") }

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            OpenCV.loadLocally()
        }
    }

    @Test
    fun vectorLineMapSnapsExactlyOnSourcePdfs() {
        val dir = folder
        assumeTrue("no reference cases; run scripts/snap_reference_cases.py", dir != null)
        val root = Json.parseToJsonElement(File(dir!!, "cases.json").readText()).jsonObject
        val radiusPx = root.getValue("radius").jsonPrimitive.double
        val cleanCases = root.getValue("cases").jsonArray
            .map { it.jsonObject }
            .filter { it.string("variant") == "clean" && it["pdf"] != null && it["truth"] is JsonArray }
        assumeTrue("no PDF metadata in cases.json", cleanCases.isNotEmpty())

        val errors = mutableListOf<Double>()
        val bySheet = cleanCases.groupBy { it.string("pdf") to it.getValue("page").jsonPrimitive.int }
        for ((key, cases) in bySheet) {
            val (pdfRel, pageNumber) = key
            val pdfFile = File(library!!, pdfRel)
            if (!pdfFile.isFile) continue
            val map = extractVectorLineMap(pdfFile, pageNumber - 1)
            for (case in cases) {
                val scale = case.getValue("scale").jsonPrimitive.double
                val (tx, ty) = case.pair("tap")
                val (gx, gy) = case.pair("truth")
                val (dx, dy) = case.pair("direction")
                val snap = map.snap(tx / scale, ty / scale, radiusPx / scale, SnapTarget.CENTER)
                val errPx = if (snap == null) Double.POSITIVE_INFINITY else {
                    // Perpendicular distance to the true line in rendered pixels.
                    val ex = snap.x * scale - gx
                    val ey = snap.y * scale - gy
                    abs(-ex * dy + ey * dx)
                }
                errors += errPx
            }
        }
        assumeTrue("no reference PDFs found on disk", errors.isNotEmpty())
        val sorted = errors.sorted()
        val median = sorted[sorted.size / 2]
        val p90 = sorted[((sorted.size - 1) * .9).toInt()]
        val miss = errors.count { it > 2.0 } * 100.0 / errors.size
        println("Vector LineMap on real PDFs: n=${errors.size} median=%.4f px p90=%.4f px miss=%.1f%%".format(median, p90, miss))
        assertTrue("vector median error $median px", median < 0.15)
        assertTrue("vector misses $miss%", miss < 3.0)
    }

    @Test
    fun rasterLineMapSnapsOnCleanDegradedAndPhotoSheets() {
        val dir = folder
        assumeTrue("no reference cases; run scripts/snap_reference_cases.py", dir != null)
        val root = Json.parseToJsonElement(File(dir!!, "cases.json").readText()).jsonObject
        val radiusPx = root.getValue("radius").jsonPrimitive.double
        val cases = root.getValue("cases").jsonArray
            .map { it.jsonObject }
            .filter { it["truth"] is JsonArray }
        val errors = mutableMapOf<String, MutableList<Double>>()
        for ((imageName, group) in cases.groupBy { it.string("image") }) {
            val imageFile = File(dir, imageName)
            if (!imageFile.isFile) continue
            val scale = group.first().getValue("scale").jsonPrimitive.double
            val map = RasterLines.find(loadGray(imageFile), pointsPerPixel = 1.0 / scale)
            for (case in group) {
                val (tx, ty) = case.pair("tap")
                val (gx, gy) = case.pair("truth")
                val (dx, dy) = case.pair("direction")
                val snap = map.snap(tx / scale, ty / scale, radiusPx / scale, SnapTarget.CENTER)
                val errPx = if (snap == null) Double.POSITIVE_INFINITY else {
                    val ex = snap.x * scale - gx
                    val ey = snap.y * scale - gy
                    // Across-line error (distortion moves points along the line on photo cases).
                    abs(-ex * dy + ey * dx)
                }
                val variant = case.string("variant")
                val key = if (variant == "clean") "clean s${case.string("scale")} ${case.string("category")}" else "$variant ${case.string("category")}"
                errors.getOrPut(key) { mutableListOf() } += errPx
                errors.getOrPut("all") { mutableListOf() } += errPx
            }
        }
        val report = StringBuilder("Raster LineMap on real sheets (across-line px; miss = none or > 2 px)\n")
        report.append("%-22s %6s %8s %8s %8s %7s\n".format("cases", "count", "median", "p90", "p95", "miss"))
        for ((key, list) in errors.toSortedMap()) {
            val sorted = list.sorted()
            fun at(q: Double) = sorted[((sorted.size - 1) * q).toInt()]
            val miss = list.count { it > 2.0 } * 100.0 / list.size
            report.append("%-22s %6d %8.3f %8.3f %8.3f %6.1f%%\n".format(key, list.size, at(.5), at(.9), at(.95), miss))
        }
        println(report)
        File(dir, "raster_report.txt").writeText(report.toString())
        val all = errors.getValue("all").sorted()
        assertTrue("raster median error ${all[all.size / 2]} px", all[all.size / 2] < 0.25)
        assertTrue("raster misses ${all.count { it > 2.0 } * 100.0 / all.size}%", all.count { it > 2.0 } < all.size * 0.03)
    }

    private fun extractVectorLineMap(file: File, pageIndex: Int): LineMap =
        PDDocument.load(file).use { doc ->
            val page = doc.getPage(pageIndex)
            val box = page.cropBox
            val collector = VectorPathCollector(
                doubleArrayOf(box.lowerLeftX.toDouble(), box.lowerLeftY.toDouble(), box.upperRightX.toDouble(), box.upperRightY.toDouble()),
                page.rotation,
            )
            DesktopPdfPathEngine(page, collector).processPage(page)
            collector.lineMap()
        }

    private class DesktopPdfPathEngine(page: PDPage, private val collector: VectorPathCollector) : PDFGraphicsStreamEngine(page) {
        private var currentPoint: Point2D = Point2D.Double()

        private fun lineWidth(): Double {
            val lw = graphicsState.lineWidth.toDouble()
            val ctm = graphicsState.currentTransformationMatrix
            val sx = hypot(ctm.scaleX.toDouble(), ctm.shearY.toDouble())
            val sy = hypot(ctm.shearX.toDouble(), ctm.scaleY.toDouble())
            return lw * (sx + sy) / 2.0
        }

        override fun appendRectangle(p0: Point2D, p1: Point2D, p2: Point2D, p3: Point2D) {
            collector.rectangle(p0.x, p0.y, p1.x, p1.y, p2.x, p2.y, p3.x, p3.y)
            currentPoint = p0
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

        override fun clip(windingRule: Int) {}

        override fun moveTo(x: Float, y: Float) {
            collector.moveTo(x.toDouble(), y.toDouble())
            currentPoint = Point2D.Float(x, y)
        }

        override fun lineTo(x: Float, y: Float) {
            collector.lineTo(x.toDouble(), y.toDouble())
            currentPoint = Point2D.Float(x, y)
        }

        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            collector.curveTo(x1.toDouble(), y1.toDouble(), x2.toDouble(), y2.toDouble(), x3.toDouble(), y3.toDouble())
            currentPoint = Point2D.Float(x3, y3)
        }

        override fun getCurrentPoint(): Point2D = currentPoint
        override fun closePath() = collector.closePath()
        override fun endPath() = collector.endPath()
        override fun strokePath() = collector.stroke(lineWidth())
        override fun fillPath(windingRule: Int) = collector.fill()
        override fun fillAndStrokePath(windingRule: Int) = collector.fillAndStroke(lineWidth())
        override fun shadingFill(shadingName: COSName) {}
    }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

    private fun JsonObject.pair(key: String): Pair<Double, Double> {
        val values = getValue(key).jsonArray
        return values[0].jsonPrimitive.double to values[1].jsonPrimitive.double
    }

    private fun loadGray(file: File): GrayImage {
        val read = ImageIO.read(file)
        val gray = if (read.type == BufferedImage.TYPE_BYTE_GRAY) read else {
            BufferedImage(read.width, read.height, BufferedImage.TYPE_BYTE_GRAY).also {
                val g = it.createGraphics()
                g.drawImage(read, 0, 0, null)
                g.dispose()
            }
        }
        val pixels = (gray.raster.dataBuffer as DataBufferByte).data
        return GrayImage(gray.width, gray.height, pixels.copyOf(gray.width * gray.height))
    }
}

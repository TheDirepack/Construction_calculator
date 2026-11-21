package com.thedirepack.constructioncalc.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.hypot

/**
 * Line snapping against real plan sheets. The cases are built locally by
 * `scripts/snap_reference_cases.py` from the downloaded blueprint library (not in git); without
 * them this test is skipped. Exact answers come from the vector PDFs; scans and photos are written
 * to results.json for checking by eye.
 */
class LineSnapReferenceTest {
    private val folder = listOf(File("../../build/snap-reference"), File("build/snap-reference"))
        .firstOrNull { File(it, "cases.json").isFile }

    @Test
    fun snapsToTheCenterOfLinesOnRealSheets() {
        assumeTrue("no reference cases; run scripts/snap_reference_cases.py", folder != null)
        val root = Json.parseToJsonElement(File(folder, "cases.json").readText()).jsonObject
        val radius = root.getValue("radius").jsonPrimitive.double
        val cases = root.getValue("cases").jsonArray.map { it.jsonObject }
        val errors = mutableMapOf<String, MutableList<Double>>()
        val results = buildJsonArray {
            for ((name, group) in cases.groupBy { it.string("image") }) {
                val image = load(File(folder, name))
                for (case in group) {
                    val (x, y) = case.pair("tap")
                    val snap = LineSnapper.snap(image, x, y, radius)
                    val truth = (case["truth"] as? JsonArray)?.let { case.pair("truth") }
                    val error = if (truth == null) null else snap?.let { hypot(it.x - truth.first, it.y - truth.second) } ?: Double.POSITIVE_INFINITY
                    if (error != null) {
                        val variant = case.string("variant")
                        val key = if (variant == "clean") "clean s${case.string("scale")} ${case.string("category")}" else "$variant ${case.string("category")}"
                        errors.getOrPut(key) { mutableListOf() } += error
                        errors.getOrPut("all") { mutableListOf() } += error
                    }
                    add(buildJsonObject {
                        put("image", name)
                        put("sheet", case.string("sheet"))
                        put("variant", case.string("variant"))
                        put("tap", case.getValue("tap"))
                        truth?.let { put("truth", case.getValue("truth")) }
                        if (snap != null) {
                            put("snap", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(snap.x)); add(kotlinx.serialization.json.JsonPrimitive(snap.y)) })
                            put("kind", snap.kind.name)
                            put("angle", snap.angle)
                            put("width", snap.width)
                        }
                        error?.let { put("error", if (it.isFinite()) it else -1.0) }
                    })
                }
            }
        }
        File(folder, "results.json").writeText(results.toString())
        val report = StringBuilder("Line snapping on real sheets (pixels; miss = none or over 2 px off)\n")
        report.append("%-22s %6s %8s %8s %8s %7s\n".format("cases", "count", "median", "p90", "p95", "miss"))
        for ((key, list) in errors.toSortedMap()) {
            val sorted = list.sorted()
            fun at(q: Double) = sorted[((sorted.size - 1) * q).toInt()]
            val miss = list.count { it > 2.0 } * 100.0 / list.size
            report.append("%-22s %6d %8.3f %8.3f %8.3f %6.1f%%\n".format(key, list.size, at(.5), at(.9), at(.95), miss))
        }
        println(report)
        File(folder, "report.txt").writeText(report.toString())
        val all = errors.getValue("all").sorted()
        assertTrue("median error ${all[all.size / 2]}", all[all.size / 2] < .35)
        assertTrue("misses", all.count { it > 2.0 } < all.size * .05)
    }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

    private fun JsonObject.pair(key: String): Pair<Double, Double> {
        val values = getValue(key).jsonArray
        return values[0].jsonPrimitive.double to values[1].jsonPrimitive.double
    }

    private fun load(file: File): GrayImage {
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

package com.thedirepack.constructioncalc.core.data

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.util.AtomicFile
import android.util.LruCache
import android.util.Xml
import androidx.core.graphics.PathParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.util.UUID
import kotlin.math.min

data class PlanSymbol(val id: String, val name: String, val svg: String)

/** Stores SVGs independently of projects. A placed symbol carries its own SVG. */
class PlanSymbols(private val context: Context) {
    private val directory = File(context.filesDir, "plan-symbols")

    fun list(): List<PlanSymbol> = directory.listFiles().orEmpty().mapNotNull { file ->
        if (file.extension != "json") return@mapNotNull null
        runCatching {
            val data = JSONObject(AtomicFile(file).openRead().use { it.readBounded(MAX_SVG_BYTES * 2).decodeToString() })
            PlanSymbol(file.nameWithoutExtension, data.getString("name"), data.getString("svg"))
        }.getOrNull()
    }.sortedBy { it.name.lowercase() }

    suspend fun import(uri: Uri): PlanSymbol = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Couldn't open the SVG.")
        val svg = input.use { it.readBounded(MAX_SVG_BYTES).decodeToString().removePrefix("\uFEFF") }
        val valid = runCatching { parseSvg(svg) }
        if (valid.isFailure) throw IOException(valid.exceptionOrNull()?.message ?: "Couldn't read this SVG.")
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Couldn't save the symbol.")
        }
        val symbol = PlanSymbol(UUID.randomUUID().toString(), displayName(context, uri, "Symbol.svg"), svg)
        val data = JSONObject().put("name", symbol.name).put("svg", svg)
        val file = AtomicFile(File(directory, "${symbol.id}.json"))
        val output = file.startWrite()
        try {
            output.write(data.toString().encodeToByteArray())
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
        symbol
    }

    fun remove(id: String) {
        if (runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) {
            AtomicFile(File(directory, "$id.json")).delete()
        }
    }
}

private const val MAX_SVG_BYTES = 256 * 1024
private val svgCache = LruCache<String, SvgDrawing>(12)

/** Draws a restricted SVG using paths and paint. Imported files never run in a WebView. */
fun drawPlanSymbol(canvas: Canvas, svg: String, left: Float, top: Float, right: Float, bottom: Float): Boolean {
    val drawing = svgCache.get(svg) ?: runCatching { parseSvg(svg) }.getOrNull()?.also { svgCache.put(svg, it) }
        ?: return false
    val scale = min((right - left) / drawing.width, (bottom - top) / drawing.height)
    if (!scale.isFinite() || scale <= 0f) return false
    val saved = canvas.save()
    try {
        canvas.clipRect(left, top, right, bottom)
        canvas.translate(left + (right - left - drawing.width * scale) / 2, top + (bottom - top - drawing.height * scale) / 2)
        canvas.scale(scale, scale)
        canvas.translate(-drawing.x, -drawing.y)
        for (shape in drawing.shapes) {
            val shapeState = canvas.save()
            canvas.concat(shape.transform)
            canvas.drawPath(shape.path, shape.paint)
            canvas.restoreToCount(shapeState)
        }
    } finally {
        canvas.restoreToCount(saved)
    }
    return true
}

private data class SvgDrawing(val x: Float, val y: Float, val width: Float, val height: Float, val shapes: List<SvgShape>)
private data class SvgShape(val path: Path, val transform: Matrix, val paint: Paint)
private data class SvgStyle(
    val fill: Int? = Color.BLACK,
    val stroke: Int? = null,
    val width: Float = 1f,
    val fillAlpha: Float = 1f,
    val strokeAlpha: Float = 1f,
    val opacity: Float = 1f,
    val fillType: Path.FillType = Path.FillType.WINDING,
    val cap: Paint.Cap = Paint.Cap.BUTT,
    val join: Paint.Join = Paint.Join.MITER,
)
private data class SvgParent(val style: SvgStyle = SvgStyle(), val matrix: Matrix = Matrix())

private fun parseSvg(source: String): SvgDrawing {
    require(source.length <= MAX_SVG_BYTES) { "This SVG is too large." }
    val parser = Xml.newPullParser().apply { setInput(StringReader(source)) }
    var bounds: FloatArray? = null
    val parents = mutableListOf(SvgParent())
    val shapes = mutableListOf<SvgShape>()
    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.DOCDECL, XmlPullParser.ENTITY_REF ->
                throw IllegalArgumentException("SVG document types and entities are unsupported.")
            XmlPullParser.START_TAG -> {
                require(parser.depth <= 24 && shapes.size < 2_000) { "This SVG is too complex." }
                val attributes = readAttributes(parser)
                val name = parser.name
                require(parser.depth != 1 || name == "svg") { "This file must have an SVG root." }
                val parent = parents.last()
                val style = attributes.style(parent.style)
                val matrix = Matrix(parent.matrix).apply {
                    attributes["transform"]?.let { preConcat(parseTransform(it)) }
                }
                parents += SvgParent(style, matrix)
                when (name) {
                    "svg" -> {
                        require(parser.depth == 1) { "Nested SVG viewports are unsupported." }
                        bounds = attributes["viewBox"]?.let(::numbers)
                            ?: floatArrayOf(0f, 0f, attributes.number("width", 100f), attributes.number("height", 100f))
                        require(bounds.size == 4 && bounds[2] > 0f && bounds[3] > 0f) { "Invalid SVG viewBox." }
                    }
                    "g", "title", "desc" -> Unit
                    "path", "rect", "line", "circle", "ellipse", "polyline", "polygon" -> {
                        val path = attributes.path(name).apply { fillType = style.fillType }
                        addPaint(shapes, path, matrix, style.fill, style.opacity * style.fillAlpha, style, Paint.Style.FILL)
                        addPaint(shapes, path, matrix, style.stroke, style.opacity * style.strokeAlpha, style, Paint.Style.STROKE)
                    }
                    else -> throw IllegalArgumentException("SVG element <$name> is unsupported. Export as simple shapes and paths.")
                }
            }
            XmlPullParser.END_TAG -> parents.removeAt(parents.lastIndex)
        }
        parser.nextToken()
    }
    val viewBox = bounds ?: throw IllegalArgumentException("This file does not contain an SVG.")
    require(shapes.isNotEmpty()) { "This SVG has no visible vector shapes." }
    return SvgDrawing(viewBox[0], viewBox[1], viewBox[2], viewBox[3], shapes)
}

private fun readAttributes(parser: XmlPullParser): Map<String, String> {
    val attributes = buildMap {
        for (index in 0 until parser.attributeCount) {
            val key = parser.getAttributeName(index)
            val value = parser.getAttributeValue(index)
            require(!key.startsWith("on", ignoreCase = true) && key != "href") { "SVG links and scripts are unsupported." }
            put(key, value)
        }
    }.toMutableMap()
    attributes["style"]?.split(';')?.forEach { rule ->
        val separator = rule.indexOf(':')
        if (separator > 0) attributes[rule.substring(0, separator).trim()] = rule.substring(separator + 1).trim()
    }
    return attributes
}

private fun Map<String, String>.style(base: SvgStyle): SvgStyle = base.copy(
    fill = if ("fill" in this) paintColor(getValue("fill")) else base.fill,
    stroke = if ("stroke" in this) paintColor(getValue("stroke")) else base.stroke,
    width = number("stroke-width", base.width).coerceAtLeast(0f),
    fillAlpha = number("fill-opacity", base.fillAlpha).coerceIn(0f, 1f),
    strokeAlpha = number("stroke-opacity", base.strokeAlpha).coerceIn(0f, 1f),
    opacity = base.opacity * number("opacity", 1f).coerceIn(0f, 1f),
    fillType = if (this["fill-rule"] == "evenodd") Path.FillType.EVEN_ODD else base.fillType,
    cap = when (this["stroke-linecap"]) {
        "round" -> Paint.Cap.ROUND
        "square" -> Paint.Cap.SQUARE
        "butt" -> Paint.Cap.BUTT
        else -> base.cap
    },
    join = when (this["stroke-linejoin"]) {
        "round" -> Paint.Join.ROUND
        "bevel" -> Paint.Join.BEVEL
        "miter" -> Paint.Join.MITER
        else -> base.join
    },
)

private fun paintColor(value: String): Int? {
    if (value == "none") return null
    if (value == "currentColor") return Color.BLACK
    val expanded = if (value.startsWith('#') && value.length == 4) {
        "#" + value.drop(1).map { "$it$it" }.joinToString("")
    } else value
    return runCatching { Color.parseColor(expanded) }.getOrElse {
        throw IllegalArgumentException("Unsupported SVG paint '${value.take(40)}'. Use solid colors.")
    }
}

private fun addPaint(
    shapes: MutableList<SvgShape>, path: Path, matrix: Matrix, color: Int?, alpha: Float,
    style: SvgStyle, type: Paint.Style,
) {
    if (color == null || alpha == 0f || type == Paint.Style.STROKE && style.width == 0f) return
    shapes += SvgShape(path, matrix, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        this.alpha = (Color.alpha(color) * alpha).toInt()
        this.style = type
        strokeWidth = style.width
        strokeCap = style.cap
        strokeJoin = style.join
    })
}

private fun Map<String, String>.path(name: String): Path {
    if (name == "path") {
        val data = getValue("d")
        require(data.length <= 64_000 && numbersInPath(data)) { "This SVG path is invalid or too complex." }
        return PathParser.createPathFromPathData(data) ?: throw IllegalArgumentException("Invalid SVG path.")
    }
    return Path().apply {
        when (name) {
            "rect" -> {
                val x = number("x")
                val y = number("y")
                val width = number("width")
                val height = number("height")
                val rx = number("rx", number("ry")).coerceIn(0f, width.coerceAtLeast(0f) / 2)
                val ry = number("ry", rx).coerceIn(0f, height.coerceAtLeast(0f) / 2)
                addRoundRect(x, y, x + width, y + height, rx, ry, Path.Direction.CW)
            }
            "line" -> {
                moveTo(number("x1"), number("y1"))
                lineTo(number("x2"), number("y2"))
            }
            "circle", "ellipse" -> {
                val x = number("cx")
                val y = number("cy")
                val rx = number(if (name == "circle") "r" else "rx")
                val ry = if (name == "circle") rx else number("ry")
                addOval(x - rx, y - ry, x + rx, y + ry, Path.Direction.CW)
            }
            "polyline", "polygon" -> {
                val points = numbers(getValue("points"))
                require(points.size >= 4 && points.size % 2 == 0) { "Invalid SVG points." }
                moveTo(points[0], points[1])
                for (index in 2 until points.size step 2) lineTo(points[index], points[index + 1])
                if (name == "polygon") close()
            }
        }
    }
}

private val numberPattern = Regex("""[+-]?(?:\d*\.\d+|\d+\.?)(?:[eE][+-]?\d+)?""")
private val transformPattern = Regex("""([a-zA-Z]+)\s*\(([^()]*)\)""")
private fun numbersInPath(value: String) = numberPattern.findAll(value).all { it.value.toFloat().validSvgNumber() }
private fun Float.validSvgNumber() = isFinite() && kotlin.math.abs(this) <= 1_000_000f
private fun numbers(value: String): FloatArray {
    require(numberPattern.replace(value, "").all { it == ',' || it.isWhitespace() }) { "Invalid SVG coordinates." }
    return numberPattern.findAll(value).map { it.value.toFloat().also { number -> require(number.validSvgNumber()) } }
        .toList().toFloatArray()
}

private fun Map<String, String>.number(name: String, fallback: Float = 0f): Float =
    this[name]?.removeSuffix("px")?.trim()?.toFloat()?.also { require(it.validSvgNumber()) } ?: fallback

private fun parseTransform(value: String): Matrix {
    require(transformPattern.replace(value, "").all { it == ',' || it.isWhitespace() }) { "Invalid SVG transform." }
    return Matrix().apply {
        for (match in transformPattern.findAll(value)) {
            val values = numbers(match.groupValues[2])
            val transform = Matrix()
            when (match.groupValues[1]) {
                "matrix" -> {
                    require(values.size == 6)
                    transform.setValues(floatArrayOf(values[0], values[2], values[4], values[1], values[3], values[5], 0f, 0f, 1f))
                }
                "translate" -> {
                    require(values.size in 1..2)
                    transform.setTranslate(values[0], values.getOrElse(1) { 0f })
                }
                "scale" -> {
                    require(values.size in 1..2)
                    transform.setScale(values[0], values.getOrElse(1) { values[0] })
                }
                "rotate" -> {
                    require(values.size == 1 || values.size == 3)
                    if (values.size == 3) transform.setRotate(values[0], values[1], values[2])
                    else transform.setRotate(values[0])
                }
                else -> throw IllegalArgumentException("Unsupported SVG transform '${match.groupValues[1]}'.")
            }
            preConcat(transform)
        }
    }
}

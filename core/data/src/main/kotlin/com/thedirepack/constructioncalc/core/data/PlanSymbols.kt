package com.thedirepack.constructioncalc.core.data

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.util.LruCache
import android.util.Xml
import androidx.core.graphics.PathParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.util.UUID
import kotlin.math.min

data class PlanSymbol(val id: String, val name: String, val svg: String)

/** Offline symbol organization: user-defined named sets, favorites, and recently used symbol IDs. */
data class SymbolLibraryMeta(
    val sets: Map<String, List<String>> = emptyMap(),
    val favorites: Set<String> = emptySet(),
    val recents: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = sets.isEmpty() && favorites.isEmpty() && recents.isEmpty()
}

/** Stores SVGs independently of projects. A drawing keeps a copy of each one placed on it (PlanDrawing.customSymbols). */
class PlanSymbols internal constructor(
    private val directory: File,
    private val context: Context? = null,
    private val validateSvg: (String) -> Unit = { parseSvg(it) },
) {
    constructor(context: Context) : this(File(context.filesDir, "plan-symbols"), context.applicationContext)

    fun list(): List<PlanSymbol> = directory.listFiles().orEmpty().mapNotNull { file ->
        if (!file.isFile || file.extension != "json") return@mapNotNull null
        val id = file.nameWithoutExtension
        if (!isValidUuid(id)) return@mapNotNull null
        runCatching {
            val raw = file.inputStream().use { it.readBounded(MAX_SVG_BYTES * 2).decodeToString() }
            val data = Json.parseToJsonElement(raw) as? JsonObject ?: return@mapNotNull null
            val name = data["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val svg = data["svg"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PlanSymbol(id, name, svg)
        }.getOrNull()
    }.sortedBy { it.name.lowercase() }

    fun readMeta(): SymbolLibraryMeta {
        val file = File(directory, META_FILE_NAME)
        if (!file.isFile) return SymbolLibraryMeta()
        return runCatching {
            val raw = file.inputStream().use { it.readBounded(MAX_SVG_BYTES).decodeToString() }
            decodeMeta(Json.parseToJsonElement(raw))
        }.getOrDefault(SymbolLibraryMeta())
    }

    fun writeMeta(meta: SymbolLibraryMeta) {
        val clean = sanitizeMeta(meta)
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Couldn't save symbol settings.")
        }
        val target = File(directory, META_FILE_NAME)
        val payload = encodeMeta(clean).toString().encodeToByteArray()
        atomicWrite(target, "sym-meta-", payload)
    }

    suspend fun import(uri: Uri): PlanSymbol = withContext(Dispatchers.IO) {
        val appContext = requireNotNull(context) { "Context is required to import from a Uri." }
        val input = appContext.contentResolver.openInputStream(uri) ?: throw IOException("Couldn't open the SVG.")
        val svg = input.use { it.readBounded(MAX_SVG_BYTES).decodeToString().removePrefix("\uFEFF") }
        val valid = runCatching { validateSvg(svg) }
        if (valid.isFailure) throw IOException(valid.exceptionOrNull()?.message ?: "Couldn't read this SVG.")
        val symbol = PlanSymbol(UUID.randomUUID().toString(), displayName(appContext, uri, "Symbol.svg"), svg)
        writeSymbol(symbol)
        symbol
    }

    fun remove(id: String) {
        if (isValidUuid(id)) {
            File(directory, "$id.json").delete()
            if (File(directory, META_FILE_NAME).isFile) {
                val meta = readMeta()
                val updatedSets = meta.sets.mapValues { (_, ids) -> ids.filterNot { it == id } }
                val updated = meta.copy(
                    sets = updatedSets,
                    favorites = meta.favorites - id,
                    recents = meta.recents.filterNot { it == id },
                )
                if (updated != meta) writeMeta(updated)
            }
        }
    }

    /** Includes imported SVG symbols and organization metadata in full backups, validating every SVG on restore. */
    val backup: ToolBackup = object : ToolBackup {
        override fun export(): JsonElement? {
            val symbols = list()
            val meta = readMeta()
            if (symbols.isEmpty() && meta.isEmpty) return null
            return buildJsonObject {
                put("version", JsonPrimitive(1))
                put("symbols", buildJsonArray {
                    for (symbol in symbols) {
                        add(buildJsonObject {
                            put("id", JsonPrimitive(symbol.id))
                            put("name", JsonPrimitive(symbol.name))
                            put("svg", JsonPrimitive(symbol.svg))
                        })
                    }
                })
                if (!meta.isEmpty) put("meta", encodeMeta(meta))
            }
        }

        override fun import(data: JsonElement, replace: Boolean) {
            val array = when (data) {
                is JsonObject -> data["symbols"] as? JsonArray
                    ?: throw IllegalArgumentException("Missing symbols array in backup.")
                is JsonArray -> data
                else -> throw IllegalArgumentException("Unsupported symbols backup format.")
            }
            val importedMeta = (data as? JsonObject)?.get("meta")?.let {
                runCatching { decodeMeta(it) }.getOrNull()
            }
            val validated = mutableListOf<PlanSymbol>()
            for (item in array) {
                val obj = item as? JsonObject ?: continue
                val id = obj["id"]?.jsonPrimitive?.contentOrNull?.trim() ?: continue
                if (!isValidUuid(id)) continue
                val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim()?.take(240)?.ifBlank { null } ?: continue
                val svg = obj["svg"]?.jsonPrimitive?.contentOrNull?.removePrefix("\uFEFF") ?: continue
                if (svg.isBlank() || svg.encodeToByteArray().size > MAX_SVG_BYTES) continue
                if (runCatching { validateSvg(svg) }.isFailure) continue
                validated += PlanSymbol(id, name, svg)
            }
            if (replace) {
                directory.listFiles().orEmpty()
                    .filter { it.isFile && it.extension == "json" && isValidUuid(it.nameWithoutExtension) }
                    .forEach { it.delete() }
                if (importedMeta != null) writeMeta(importedMeta)
                else File(directory, META_FILE_NAME).delete()
            } else if (importedMeta != null && !importedMeta.isEmpty) {
                val existing = readMeta()
                val mergedSets = LinkedHashMap<String, List<String>>().apply {
                    putAll(existing.sets)
                    for ((name, ids) in importedMeta.sets) {
                        this[name] = (this[name].orEmpty() + ids).distinct()
                    }
                }
                writeMeta(
                    SymbolLibraryMeta(
                        sets = mergedSets,
                        favorites = existing.favorites + importedMeta.favorites,
                        recents = (importedMeta.recents + existing.recents).distinct(),
                    ),
                )
            }
            for (symbol in validated) {
                writeSymbol(symbol)
            }
        }
    }

    internal fun writeSymbol(symbol: PlanSymbol) {
        require(isValidUuid(symbol.id)) { "Invalid symbol id." }
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Couldn't save the symbol.")
        }
        val target = File(directory, "${symbol.id}.json")
        require(target.canonicalFile.parentFile == directory.canonicalFile) { "Invalid symbol path." }
        val payload = buildJsonObject {
            put("name", JsonPrimitive(symbol.name))
            put("svg", JsonPrimitive(symbol.svg))
        }.toString().encodeToByteArray()
        atomicWrite(target, "sym-", payload)
    }

    private fun atomicWrite(target: File, prefix: String, payload: ByteArray) =
        AtomicFiles.write(target, payload, prefix)

    private fun isValidUuid(id: String): Boolean =
        runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

    private fun isValidSymbolId(id: String): Boolean =
        id.isNotBlank() && id.length <= 120 && '/' !in id && '\\' !in id &&
            id != "." && id != ".." && id.none(Char::isISOControl)

    private fun sanitizeMeta(meta: SymbolLibraryMeta): SymbolLibraryMeta {
        val cleanSets = LinkedHashMap<String, List<String>>()
        for ((rawName, rawIds) in meta.sets) {
            if (cleanSets.size >= MAX_SETS) break
            val name = rawName.trim().take(80)
            if (name.isBlank() || name.any(Char::isISOControl) || '/' in name || '\\' in name || name == "." || name == "..") continue
            val ids = rawIds.asSequence().map(String::trim).filter(::isValidSymbolId).distinct().take(MAX_IDS_PER_SET).toList()
            cleanSets[name] = ids
        }
        val cleanFavorites = meta.favorites.asSequence().map(String::trim).filter(::isValidSymbolId).take(MAX_IDS_PER_SET).toSet()
        val cleanRecents = meta.recents.asSequence().map(String::trim).filter(::isValidSymbolId).distinct().take(MAX_RECENTS).toList()
        return SymbolLibraryMeta(cleanSets, cleanFavorites, cleanRecents)
    }

    private fun encodeMeta(meta: SymbolLibraryMeta): JsonObject = buildJsonObject {
        put("sets", buildJsonObject {
            for ((name, ids) in meta.sets) {
                put(name, buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
            }
        })
        put("favorites", buildJsonArray { meta.favorites.forEach { add(JsonPrimitive(it)) } })
        put("recents", buildJsonArray { meta.recents.forEach { add(JsonPrimitive(it)) } })
    }

    private fun decodeMeta(element: JsonElement): SymbolLibraryMeta {
        val obj = element.jsonObject
        val sets = obj["sets"]?.jsonObject?.entries?.associate { (k, v) ->
            k to v.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
        }.orEmpty()
        val favorites = obj["favorites"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet().orEmpty()
        val recents = obj["recents"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        return sanitizeMeta(SymbolLibraryMeta(sets, favorites, recents))
    }

    companion object {
        private const val META_FILE_NAME = "library-meta.json"
        private const val MAX_SETS = 50
        private const val MAX_IDS_PER_SET = 200
        private const val MAX_RECENTS = 24
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
            XmlPullParser.DOCDECL ->
                throw IllegalArgumentException("SVG document types are unsupported.")
            XmlPullParser.ENTITY_REF -> {
                if (parser.name !in PREDEFINED_XML_ENTITIES) {
                    throw IllegalArgumentException("SVG custom entities are unsupported.")
                }
            }
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
    val attrs = this
    if (name == "path") {
        val data = requireNotNull(attrs["d"]) { "SVG <path> is missing its d attribute." }
        require(data.length <= 64_000 && numbersInPath(data)) { "This SVG path is invalid or too complex." }
        return PathParser.createPathFromPathData(data)
    }
    return Path().apply {
        when (name) {
            "rect" -> {
                val x = attrs.number("x")
                val y = attrs.number("y")
                val width = attrs.number("width")
                val height = attrs.number("height")
                require(width >= 0f && height >= 0f) { "SVG <rect> dimensions must be non-negative." }
                val rx = attrs.number("rx", attrs.number("ry")).coerceIn(0f, width / 2)
                val ry = attrs.number("ry", rx).coerceIn(0f, height / 2)
                addRoundRect(x, y, x + width, y + height, rx, ry, Path.Direction.CW)
            }
            "line" -> {
                moveTo(attrs.number("x1"), attrs.number("y1"))
                lineTo(attrs.number("x2"), attrs.number("y2"))
            }
            "circle", "ellipse" -> {
                val x = attrs.number("cx")
                val y = attrs.number("cy")
                val rx = attrs.number(if (name == "circle") "r" else "rx")
                val ry = if (name == "circle") rx else attrs.number("ry")
                require(rx >= 0f && ry >= 0f) { "SVG <$name> radius must be non-negative." }
                addOval(x - rx, y - ry, x + rx, y + ry, Path.Direction.CW)
            }
            "polyline", "polygon" -> {
                val raw = requireNotNull(attrs["points"]) { "SVG <$name> is missing its points attribute." }
                val points = numbers(raw)
                require(points.size >= 4 && points.size % 2 == 0) { "Invalid SVG points." }
                moveTo(points[0], points[1])
                for (index in 2 until points.size step 2) lineTo(points[index], points[index + 1])
                if (name == "polygon") close()
            }
        }
    }
}

private val PREDEFINED_XML_ENTITIES = setOf("amp", "lt", "gt", "quot", "apos")

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

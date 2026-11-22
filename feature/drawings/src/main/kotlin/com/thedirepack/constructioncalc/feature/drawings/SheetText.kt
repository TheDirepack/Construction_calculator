package com.thedirepack.constructioncalc.feature.drawings

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.io.StringWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class SheetLabel(val number: String, val title: String = "") {
    fun matches(query: String) = "$number $title".contains(query, ignoreCase = true)
}

internal data class CachedPdfText(val hasText: Boolean, val pages: Map<Int, String>)

private val sheetNumber = Regex("""[A-Z]{1,4}[- .]?\d{1,3}(?:[.-]\d{1,2})?[A-Z]?""")
private val validSha256 = Regex("^[0-9a-f]{64}$")

private const val LABELS_CACHE_HEADER = "CC-SHEET-LABELS-V1"
private const val TEXT_CACHE_HEADER = "CC-PDF-TEXT-V1"
private const val MAX_PAGE_TEXT_CHARS = 65_536
private const val MAX_CACHE_READ_CHARS = 8 * 1024 * 1024

internal fun resolveSheetLabel(explicit: String?, repeatedLabels: Set<String>, inferred: SheetLabel?): SheetLabel? {
    val cleanExplicit = explicit?.takeIf { it.isNotBlank() && it !in repeatedLabels }?.take(80)
        ?: return inferred
    val inferredTitle = inferred?.title.orEmpty()
    val redundant = inferredTitle.isNotBlank() &&
        normalizeSheetText(cleanExplicit).contains(normalizeSheetText(inferredTitle), ignoreCase = true)
    return SheetLabel(cleanExplicit, if (redundant) "" else inferredTitle)
}

internal fun titleBlockLabel(text: String): SheetLabel? {
    val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    val index = lines.indexOfLast(sheetNumber::matches)
    if (index < 0) return null
    val above = lines.take(index)
    val titleStart = above.indexOfLast { it.startsWith("SHEET TITLE:", ignoreCase = true) }
    val titleLines = if (titleStart >= 0) {
        listOf(above[titleStart].substringAfter(':').trim()) + above.drop(titleStart + 1)
    } else above.takeLast(4)
    val title = titleLines.takeWhile { !it.startsWith("SHEET NUMBER:", ignoreCase = true) }
        .filter { line ->
            line.count(Char::isLetter) >= 3 && ':' !in line
        }
        .takeLast(3).joinToString(" ")
    return SheetLabel(lines[index], title.take(120))
}

private fun escapeCacheField(value: String): String = buildString(value.length) {
    for (ch in value) {
        when (ch) {
            '\\' -> append("\\\\")
            '\t' -> append("\\t")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(ch)
        }
    }
}

private fun unescapeCacheField(value: String): String = buildString(value.length) {
    var i = 0
    while (i < value.length) {
        val ch = value[i]
        if (ch == '\\' && i + 1 < value.length) {
            when (value[i + 1]) {
                '\\' -> { append('\\'); i += 2; continue }
                't' -> { append('\t'); i += 2; continue }
                'n' -> { append('\n'); i += 2; continue }
                'r' -> { append('\r'); i += 2; continue }
            }
        }
        append(ch)
        i++
    }
}

internal fun encodeSheetLabelsCache(labels: Map<Int, SheetLabel>): String = buildString {
    append(LABELS_CACHE_HEADER).append('\n')
    labels.toSortedMap().forEach { (page, label) ->
        if (page >= 0) {
            append(page).append('\t')
                .append(escapeCacheField(label.number.take(80))).append('\t')
                .append(escapeCacheField(label.title.take(120))).append('\n')
        }
    }
}

internal fun decodeSheetLabelsCache(raw: String): Map<Int, SheetLabel>? {
    if (raw.length > MAX_CACHE_READ_CHARS) return null
    val lines = raw.lineSequence().iterator()
    if (!lines.hasNext() || lines.next() != LABELS_CACHE_HEADER) return null
    val result = linkedMapOf<Int, SheetLabel>()
    while (lines.hasNext()) {
        val line = lines.next()
        if (line.isEmpty()) continue
        val parts = line.split('\t')
        if (parts.size != 3) return null
        val page = parts[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        result[page] = SheetLabel(unescapeCacheField(parts[1]), unescapeCacheField(parts[2]))
    }
    return result
}

internal fun encodePdfTextCache(data: CachedPdfText): String = buildString {
    append(TEXT_CACHE_HEADER).append('\t').append(if (data.hasText) 1 else 0).append('\n')
    var totalChars = length
    for ((page, text) in data.pages.toSortedMap()) {
        if (page < 0) continue
        val escaped = escapeCacheField(text.take(MAX_PAGE_TEXT_CHARS))
        val lineLen = 16 + escaped.length
        if (totalChars + lineLen > MAX_CACHE_READ_CHARS) break
        append(page).append('\t').append(escaped).append('\n')
        totalChars += lineLen
    }
}

internal fun decodePdfTextCache(raw: String): CachedPdfText? {
    if (raw.length > MAX_CACHE_READ_CHARS) return null
    val lines = raw.lineSequence().iterator()
    if (!lines.hasNext()) return null
    val header = lines.next().split('\t')
    if (header.size != 2 || header[0] != TEXT_CACHE_HEADER) return null
    val hasText = when (header[1]) {
        "1" -> true
        "0" -> false
        else -> return null
    }
    val pages = linkedMapOf<Int, String>()
    while (lines.hasNext()) {
        val line = lines.next()
        if (line.isEmpty()) continue
        val tab = line.indexOf('\t')
        if (tab <= 0) return null
        val page = line.substring(0, tab).toIntOrNull()?.takeIf { it >= 0 } ?: return null
        pages[page] = unescapeCacheField(line.substring(tab + 1))
    }
    return CachedPdfText(hasText, pages)
}

private fun cacheFile(context: Context, sha256: String, suffix: String): File? {
    if (!validSha256.matches(sha256)) return null
    return File(File(context.cacheDir, "sheet-index"), "$sha256.$suffix")
}

private fun writeCacheAtomic(target: File, content: String) {
    val dir = target.parentFile ?: return
    if (!dir.exists()) dir.mkdirs()
    val tmp = File(dir, "${target.name}.${System.nanoTime()}.tmp")
    try {
        tmp.writeText(content, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
    } catch (_: Exception) {
        tmp.delete()
    }
}

private fun readCacheText(target: File?): String? {
    if (target == null || !target.isFile || target.length() > MAX_CACHE_READ_CHARS * 2L) return null
    return runCatching { target.readText(Charsets.UTF_8) }.getOrNull()
}

internal suspend fun indexSheets(context: Context, sha256: String, file: File, update: (Int, SheetLabel) -> Unit) =
    withContext(Dispatchers.IO) {
        val cache = cacheFile(context, sha256, "labels.v1")
        val cached = readCacheText(cache)?.let(::decodeSheetLabelsCache)
        if (cached != null) {
            withContext(Dispatchers.Main) {
                cached.forEach { (index, label) -> update(index, label) }
            }
            return@withContext
        }

        PDFBoxResourceLoader.init(context)
        val resolved = linkedMapOf<Int, SheetLabel>()
        coroutineScope {
            val channel = Channel<Pair<Int, SheetLabel>>(Channel.UNLIMITED)
            val emitter = launch(Dispatchers.Main) {
                for ((index, label) in channel) update(index, label)
            }
            try {
                PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)).use { pdf ->
                    val labels = pdf.documentCatalog.pageLabels?.labelsByPageIndices
                    val repeated = labels.orEmpty().asIterable().groupingBy { it }.eachCount()
                        .filterValues { it > 1 }.keys
                    // Page labels also apply to image-only pages with no text stream.
                    labels?.forEachIndexed { index, value ->
                        currentCoroutineContext().ensureActive()
                        if (value.isNotBlank() && value !in repeated && value != (index + 1).toString()) {
                            val label = SheetLabel(value.take(80))
                            resolved[index] = label
                            channel.trySend(index to label)
                        }
                    }
                    extractPageText(pdf, titleBlockOnly = true) { index, text ->
                        val inferred = titleBlockLabel(text) ?: return@extractPageText
                        val explicit = labels?.getOrNull(index)?.takeIf { it.isNotBlank() && it != (index + 1).toString() }
                        resolveSheetLabel(explicit, repeated, inferred)?.let { label ->
                            resolved[index] = label
                            channel.trySend(index to label)
                        }
                    }
                }
            } finally {
                channel.close()
            }
            emitter.join()
        }
        cache?.let { writeCacheAtomic(it, encodeSheetLabelsCache(resolved)) }
    }

private suspend fun extractPageText(
    pdf: PDDocument,
    titleBlockOnly: Boolean = false,
    onText: (Int, String) -> Unit,
) {
    val jobContext = currentCoroutineContext()
    val output = StringWriter()
    val stripper = object : PDFTextStripper() {
        override fun processPage(page: PDPage) {
            jobContext.ensureActive()
            super.processPage(page)
            onText(currentPageNo - 1, output.toString())
            output.buffer.setLength(0)
        }

        override fun processTextPosition(text: TextPosition) {
            if (!titleBlockOnly || text.xDirAdj > text.pageWidth * .85f && text.yDirAdj > text.pageHeight * .80f) {
                super.processTextPosition(text)
            }
        }
    }.apply { sortByPosition = true }
    stripper.writeText(pdf, output)
}

internal data class PdfTextMatches(val pages: Set<Int> = emptySet(), val status: String? = null)

private const val NO_SEARCHABLE_TEXT = "This PDF has no searchable text. You can label scanned sheets manually."

internal suspend fun searchPdfText(context: Context, sha256: String, file: File, query: String): PdfTextMatches =
    withContext(Dispatchers.IO) {
        val cache = cacheFile(context, sha256, "text.v1")
        val cached = readCacheText(cache)?.let(::decodePdfTextCache)
        val index = cached ?: run {
            PDFBoxResourceLoader.init(context)
            val pagesMap = linkedMapOf<Int, String>()
            var hasText = false
            PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)).use { pdf ->
                extractPageText(pdf) { page, text ->
                    if (text.isNotBlank()) {
                        hasText = true
                        pagesMap[page] = normalizeSheetText(text).take(MAX_PAGE_TEXT_CHARS)
                    }
                }
            }
            CachedPdfText(hasText, pagesMap).also { built ->
                cache?.let { writeCacheAtomic(it, encodePdfTextCache(built)) }
            }
        }
        val matches = index.pages.mapNotNull { (page, normalized) ->
            if (normalized.contains(query, ignoreCase = true)) page else null
        }.toSet()
        PdfTextMatches(matches, if (index.hasText) null else NO_SEARCHABLE_TEXT)
    }

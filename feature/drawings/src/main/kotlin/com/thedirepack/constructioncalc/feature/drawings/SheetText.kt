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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class SheetLabel(val number: String, val title: String = "") {
    fun matches(query: String) = "$number $title".contains(query, ignoreCase = true)
}

private val sheetNumber = Regex("""[A-Z]{1,4}[- .]?\d{1,3}(?:[.-]\d{1,2})?[A-Z]?""")

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

internal suspend fun indexSheets(context: Context, file: File, update: (Int, SheetLabel) -> Unit) =
    withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context)
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)).use { pdf ->
            val labels = pdf.documentCatalog.pageLabels?.labelsByPageIndices
            // Page labels also apply to image-only pages with no text stream.
            labels?.forEachIndexed { index, value ->
                currentCoroutineContext().ensureActive()
                if (value.isNotBlank() && value != (index + 1).toString()) {
                    withContext(Dispatchers.Main) { update(index, SheetLabel(value.take(80))) }
                }
            }
            val inferredLabels = mutableMapOf<Int, SheetLabel>()
            extractPageText(pdf, titleBlockOnly = true) { index, text ->
                val inferred = titleBlockLabel(text) ?: return@extractPageText
                val explicit = labels?.getOrNull(index)?.takeIf { it.isNotBlank() && it != (index + 1).toString() }
                val label = if (explicit == null) inferred else SheetLabel(explicit.take(80), inferred.title)
                inferredLabels[index] = label
            }
            withContext(Dispatchers.Main) { inferredLabels.forEach(update) }
        }
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

internal suspend fun searchPdfText(context: Context, file: File, query: String): PdfTextMatches =
    withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context)
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)).use { pdf ->
            var hasText = false
            val pages = mutableSetOf<Int>()
            extractPageText(pdf) { page, text ->
                if (text.isNotBlank()) {
                    hasText = true
                    if (normalizeSheetText(text).contains(query, ignoreCase = true)) pages.add(page)
                }
            }
            PdfTextMatches(pages, if (hasText) null else "This PDF has no searchable text. You can label scanned sheets manually.")
        }
    }

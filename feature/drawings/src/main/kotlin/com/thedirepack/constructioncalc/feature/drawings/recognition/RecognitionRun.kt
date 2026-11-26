package com.thedirepack.constructioncalc.feature.drawings.recognition

import com.thedirepack.constructioncalc.core.model.PlanMark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable

/** Pages of one drawing to recognize; null [pages] means every page. */
data class RecognitionTarget(val drawingId: String, val drawingName: String, val pages: List<Int>? = null)

data class RecognitionProgress(
    val drawingName: String,
    val page: Int,
    val pagesDone: Int,
    val pagesTotal: Int,
    val tilesDone: Int,
    val tilesTotal: Int,
) {
    /** 0–1 over all pages, counting tiles of the page in progress. */
    val fraction: Float
        get() = if (pagesTotal == 0) 0f
        else ((pagesDone + if (tilesTotal == 0) 0f else tilesDone.toFloat() / tilesTotal) / pagesTotal).coerceIn(0f, 1f)
}

/**
 * Recognizes symbols over [targets] page by page, off the main thread and cancellable between
 * tiles. Each finished page is handed to [onPage] at once (on the caller's context), so a cancel
 * keeps what was already found. [existing] gives a drawing's current marks, so re-running a page
 * doesn't propose symbols already marked, accepted or rejected there.
 */
suspend fun runRecognition(
    targets: List<RecognitionTarget>,
    recognizer: SymbolRecognizer,
    openSource: (drawingId: String) -> PageRasterSource,
    existing: (drawingId: String) -> List<PlanMark>,
    onProgress: (RecognitionProgress) -> Unit,
    onPage: (drawingId: String, PageRecognition) -> Unit,
): RecognitionStats {
    var total = RecognitionStats()
    val opened = LinkedHashMap<RecognitionTarget, PageRasterSource>()
    try {
        for (target in targets) opened[target] = withContext(Dispatchers.IO) { openSource(target.drawingId) }
        val plan = opened.map { (target, source) ->
            val count = source.pageCount
            target to (target.pages?.filter { it in 0 until count }?.distinct() ?: (0 until count).toList())
        }
        val pagesTotal = plan.sumOf { it.second.size }
        var pagesDone = 0
        for ((target, pages) in plan) {
            val source = opened.getValue(target)
            for (page in pages) {
                currentCoroutineContext().ensureActive()
                onProgress(RecognitionProgress(target.drawingName, page, pagesDone, pagesTotal, 0, 0))
                val marks = existing(target.drawingId)
                val done = pagesDone
                val result = withContext(Dispatchers.Default) {
                    recognizer.recognizePage(source, page, marks) { tiles, tilesTotal ->
                        onProgress(RecognitionProgress(target.drawingName, page, done, pagesTotal, tiles, tilesTotal))
                    }
                }
                pagesDone++
                total += result.stats
                onPage(target.drawingId, result)
            }
        }
        onProgress(RecognitionProgress(plan.lastOrNull()?.first?.drawingName.orEmpty(), -1, pagesDone, pagesTotal, 0, 0))
    } finally {
        opened.values.forEach { (it as? Closeable)?.close() }
    }
    return total
}

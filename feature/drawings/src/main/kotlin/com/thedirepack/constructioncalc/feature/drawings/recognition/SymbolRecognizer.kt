package com.thedirepack.constructioncalc.feature.drawings.recognition

import com.thedirepack.constructioncalc.core.model.DeviceCatalog
import com.thedirepack.constructioncalc.core.model.PlanMark
import com.thedirepack.constructioncalc.core.model.PlanMarkKind
import com.thedirepack.constructioncalc.core.model.PlanPoint
import com.thedirepack.constructioncalc.core.model.PlanReviewState
import com.thedirepack.constructioncalc.core.model.PlanSymbolPlacement
import com.thedirepack.constructioncalc.core.model.PlanSymbolSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.max

/** Renders pages of one document into tiles. Blocking; called off the main thread. */
interface PageRasterSource {
    val pageCount: Int

    /** Page size in page points. */
    fun pageSize(page: Int): Pair<Double, Double>

    /** Renders [tile] of [grid] on [page]: the grid's region at the grid's scale, offset to the tile. */
    fun render(page: Int, grid: TileGrid, tile: TileSpec): TileImage
}

/** A detection in page points, its class snapped to a catalog device id. */
data class PageDetection(val deviceId: String, val confidence: Float, val box: PageBox)

data class RecognitionStats(
    val tiles: Int = 0,
    val blankTilesSkipped: Int = 0,
    val rawDetections: Int = 0,
    val lowConfidenceDropped: Int = 0,
    /** Detections whose class isn't a catalog device: dropped, counted by class name. */
    val unknownClasses: Map<String, Int> = emptyMap(),
    /** The same symbol seen in overlapping tiles (or as two classes), merged into one. */
    val duplicatesMerged: Int = 0,
    /** Detections where the page already has a symbol (placed, accepted, pending or rejected). */
    val alreadyMarked: Int = 0,
    val proposed: Int = 0,
) {
    val unknownClassDropped: Int get() = unknownClasses.values.sum()

    operator fun plus(other: RecognitionStats) = RecognitionStats(
        tiles + other.tiles,
        blankTilesSkipped + other.blankTilesSkipped,
        rawDetections + other.rawDetections,
        lowConfidenceDropped + other.lowConfidenceDropped,
        (unknownClasses.keys + other.unknownClasses.keys).associateWith { (unknownClasses[it] ?: 0) + (other.unknownClasses[it] ?: 0) },
        duplicatesMerged + other.duplicatesMerged,
        alreadyMarked + other.alreadyMarked,
        proposed + other.proposed,
    )
}

/** New pending symbol marks for one page, ready to add to the drawing for review. */
data class PageRecognition(val page: Int, val marks: List<PlanMark>, val stats: RecognitionStats)

/** Greedy non-maximum suppression across tiles. */
object DetectionMerge {
    data class Result(val kept: List<PageDetection>, val merged: Int)

    /**
     * Keeps the most confident of each group of overlapping detections. Same-class boxes merge when
     * they overlap by [iou] or one lies mostly inside the other ([containment]: a symbol cut by a
     * tile edge next to the whole one from the neighbouring tile); the kept box grows to cover
     * both. Different classes on the same spot ([crossClassIou]) keep only the more confident one.
     */
    fun merge(
        detections: List<PageDetection>,
        iou: Double = 0.45,
        containment: Double = 0.8,
        crossClassIou: Double = 0.7,
    ): Result {
        val kept = mutableListOf<PageDetection>()
        var merged = 0
        for (candidate in detections.sortedByDescending { it.confidence }) {
            val index = kept.indexOfFirst { k ->
                if (k.deviceId == candidate.deviceId) {
                    k.box.iou(candidate.box) >= iou || k.box.containment(candidate.box) >= containment
                } else {
                    k.box.iou(candidate.box) >= crossClassIou
                }
            }
            if (index < 0) {
                kept += candidate
            } else {
                merged++
                val k = kept[index]
                if (k.deviceId == candidate.deviceId) kept[index] = k.copy(box = k.box.union(candidate.box))
            }
        }
        return Result(kept, merged)
    }
}

/**
 * Finds symbols on a page: renders the page at the detector's DPI into overlapping tiles, skips
 * blank tiles, runs the detector, maps boxes back to page points, merges duplicates across tiles,
 * snaps classes to the catalog (unknown classes are dropped and counted) and leaves out spots
 * already marked. Results are pending marks; nothing counts until it is reviewed.
 */
class SymbolRecognizer(
    private val detector: SymbolDetector,
    private val catalog: DeviceCatalog,
    private val minInkFraction: Double = 0.002,
) {
    suspend fun recognizePage(
        source: PageRasterSource,
        page: Int,
        existing: List<PlanMark>,
        region: PageBox? = null,
        onTile: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): PageRecognition {
        val config = detector.config
        val (w, h) = source.pageSize(page)
        val grid = Tiling.plan(region ?: PageBox(0.0, 0.0, w, h), config)
        var blank = 0
        var raw = 0
        var lowConfidence = 0
        val found = mutableListOf<PageDetection>()
        grid.tiles.forEachIndexed { done, tile ->
            currentCoroutineContext().ensureActive()
            val image = source.render(page, grid, tile)
            if (Tiling.inkFraction(image) < minInkFraction) {
                blank++
            } else {
                for (detection in detector.detect(image)) {
                    raw++
                    if (!detection.confidence.isFinite() || detection.confidence < config.minConfidence) {
                        lowConfidence++
                        continue
                    }
                    val box = grid.toPage(tile, detection.box)
                    if (box.width <= 0 || box.height <= 0) continue
                    // Unknown classes go through the merge too, so one symbol seen in two tiles counts once.
                    val id = catalog.snap(detection.classId)?.id ?: (UNKNOWN + detection.classId)
                    found += PageDetection(id, detection.confidence.coerceIn(0f, 1f), box)
                }
            }
            onTile(done + 1, grid.tiles.size)
        }
        val merged = DetectionMerge.merge(found)
        val (unknownKept, known) = merged.kept.partition { it.deviceId.startsWith(UNKNOWN) }
        val unknown = unknownKept.groupingBy { it.deviceId.removePrefix(UNKNOWN) }.eachCount()
        val marked = existing.filter { it.page == page && it.kind == PlanMarkKind.SYMBOL }
        var alreadyMarked = 0
        val marks = known.mapNotNull { detection ->
            if (marked.any { overlapsExisting(detection.box, it) }) {
                alreadyMarked++
                null
            } else {
                detection.toMark(page)
            }
        }
        val stats = RecognitionStats(
            tiles = grid.tiles.size,
            blankTilesSkipped = blank,
            rawDetections = raw,
            lowConfidenceDropped = lowConfidence,
            unknownClasses = unknown,
            duplicatesMerged = merged.merged,
            alreadyMarked = alreadyMarked,
            proposed = marks.size,
        )
        return PageRecognition(page, marks, stats)
    }

    private companion object {
        /** Not a valid catalog id (ids never contain a space), so it can't collide with one. */
        const val UNKNOWN = "unknown class "
    }

    private fun overlapsExisting(box: PageBox, mark: PlanMark): Boolean {
        val center = mark.points.first()
        val half = (mark.symbol?.size ?: 0.0) / 2
        val markBox = PageBox(center.x - half, center.y - half, center.x + half, center.y + half)
        return box.contains(center.x, center.y) || markBox.contains(box.centerX, box.centerY)
    }
}

internal fun PageDetection.toMark(page: Int): PlanMark = PlanMark(
    page = page,
    kind = PlanMarkKind.SYMBOL,
    points = listOf(PlanPoint(box.centerX, box.centerY)),
    symbol = PlanSymbolPlacement(
        deviceId = deviceId,
        size = max(box.width, box.height).coerceIn(PlanSymbolPlacement.MIN_SIZE, PlanSymbolPlacement.MAX_SIZE),
        source = PlanSymbolSource.DETECTED,
        confidence = confidence,
        reviewState = PlanReviewState.PENDING,
    ),
)

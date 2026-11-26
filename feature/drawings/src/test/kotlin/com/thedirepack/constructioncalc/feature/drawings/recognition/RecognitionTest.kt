package com.thedirepack.constructioncalc.feature.drawings.recognition

import com.thedirepack.constructioncalc.core.model.DeviceCatalog
import com.thedirepack.constructioncalc.core.model.PlanMark
import com.thedirepack.constructioncalc.core.model.PlanMarkKind
import com.thedirepack.constructioncalc.core.model.PlanPoint
import com.thedirepack.constructioncalc.core.model.PlanReviewState
import com.thedirepack.constructioncalc.core.model.PlanSymbolPlacement
import com.thedirepack.constructioncalc.core.model.PlanSymbolSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** A filled square on a fake page: [rgb] tells the fake detector which class it is. */
private data class Glyph(val x: Double, val y: Double, val side: Double, val rgb: Int)

private const val DUPLEX = 0x800000 // dark red
private const val SWITCH = 0x000080 // dark blue
private const val MYSTERY = 0x404000 // dark olive: a class the catalog doesn't have

/** Renders glyph squares at native resolution and records every tile asked for. */
private class FakePages(private val pages: List<List<Glyph>>, private val w: Double = 600.0, private val h: Double = 400.0) : PageRasterSource {
    val rendered = mutableListOf<TileSpec>()
    override val pageCount: Int get() = pages.size
    override fun pageSize(page: Int) = w to h
    override fun render(page: Int, grid: TileGrid, tile: TileSpec): TileImage {
        rendered += tile
        val pixels = IntArray(tile.width * tile.height) { 0xffffffff.toInt() }
        for (g in pages[page]) {
            fun px(v: Double, origin: Double, offset: Int) = ((v - origin) * grid.scale).toInt() - offset
            val x0 = px(g.x, grid.region.left, tile.pxLeft).coerceIn(0, tile.width)
            val x1 = px(g.x + g.side, grid.region.left, tile.pxLeft).coerceIn(0, tile.width)
            val y0 = px(g.y, grid.region.top, tile.pxTop).coerceIn(0, tile.height)
            val y1 = px(g.y + g.side, grid.region.top, tile.pxTop).coerceIn(0, tile.height)
            for (y in y0 until y1) for (x in x0 until x1) pixels[y * tile.width + x] = (0xff shl 24) or g.rgb
        }
        return TileImage(tile.width, tile.height, pixels)
    }
}

/** Finds solid blobs; color picks the class, and a blob cut by the tile edge is less confident. */
private class FakeDetector(override val config: DetectorConfig, private val onTile: () -> Unit = {}) : SymbolDetector {
    var calls = 0
    override val status = DetectorStatus.Ready("fake")
    override fun detect(tile: TileImage): List<RawDetection> {
        calls++
        onTile()
        val seen = BooleanArray(tile.pixels.size)
        val out = mutableListOf<RawDetection>()
        for (start in tile.pixels.indices) {
            val color = tile.pixels[start] and 0xffffff
            if (seen[start] || color == 0xffffff) continue
            var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1
            val stack = ArrayDeque(listOf(start))
            seen[start] = true
            while (stack.isNotEmpty()) {
                val i = stack.removeLast()
                val x = i % tile.width
                val y = i / tile.width
                minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
                for ((nx, ny) in listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)) {
                    if (nx !in 0 until tile.width || ny !in 0 until tile.height) continue
                    val j = ny * tile.width + nx
                    if (!seen[j] && tile.pixels[j] and 0xffffff == color) { seen[j] = true; stack += j }
                }
            }
            val clipped = minX == 0 || minY == 0 || maxX == tile.width - 1 || maxY == tile.height - 1
            val classId = when (color) {
                DUPLEX -> "receptacle.duplex"
                SWITCH -> "Switch/Single Pole" // loose spelling: snapped to switch.single_pole
                else -> "mystery_widget"
            }
            out += RawDetection(classId, if (clipped) 0.5f else 0.9f, PixelBox(minX.toFloat(), minY.toFloat(), maxX + 1f, maxY + 1f))
        }
        return out
    }
}

class RecognitionTest {
    private val catalog = DeviceCatalog.bundled
    // 2 px per point; 256 px tiles overlapping by 64 px (32 pt).
    private val config = DetectorConfig(dpi = 144f, tileSizePx = 256, overlapPx = 64, minConfidence = 0.3f)

    @Test fun axisCoversEverythingWithFullSizeOverlappingTiles() {
        val tiles = Tiling.axis(1000, 256, 64)
        assertEquals(0, tiles.first().first)
        assertEquals(1000, tiles.last().first + tiles.last().second)
        assertTrue(tiles.all { it.second == 256 })
        tiles.zipWithNext().forEach { (a, b) -> assertTrue(a.first + a.second - b.first >= 64) }
        assertEquals(listOf(0 to 100), Tiling.axis(100, 256, 64))
    }

    @Test fun pagesAreTiledAtNativeResolutionNeverDownscaled() {
        // A 36" × 24" sheet at 200 DPI: 7200 × 4800 px, all in 512 px tiles.
        val grid = Tiling.plan(PageBox(0.0, 0.0, 36 * 72.0, 24 * 72.0), DetectorConfig())
        assertEquals(200.0 / 72.0, grid.scale, 1e-9)
        assertTrue(grid.tiles.all { it.width == 512 && it.height == 512 })
        assertEquals(7200, grid.tiles.maxOf { it.pxLeft + it.width })
        assertEquals(4800, grid.tiles.maxOf { it.pxTop + it.height })
    }

    @Test fun tileBoxesMapBackToPagePoints() {
        val grid = Tiling.plan(PageBox(100.0, 50.0, 400.0, 300.0), config)
        val tile = grid.tiles[1]
        val box = grid.toPage(tile, PixelBox(10f, 20f, 30f, 40f))
        assertEquals(100.0 + (tile.pxLeft + 10) / 2.0, box.left, 1e-9)
        assertEquals(50.0 + (tile.pxTop + 20) / 2.0, box.top, 1e-9)
        assertEquals(10.0, box.width, 1e-9)
    }

    @Test fun inkFractionTellsBlankFromDrawn() {
        val blank = TileImage(16, 16, IntArray(256) { 0xffffffff.toInt() })
        assertEquals(0.0, Tiling.inkFraction(blank), 0.0)
        val drawn = TileImage(16, 16, IntArray(256) { if (it % 4 == 0) 0xff000000.toInt() else 0xffffffff.toInt() })
        assertTrue(Tiling.inkFraction(drawn, stride = 1) > 0.2)
        val transparent = TileImage(4, 4, IntArray(16))
        assertEquals(0.0, Tiling.inkFraction(transparent), 0.0)
    }

    @Test fun mergeKeepsOneBoxPerSymbolAcrossTiles() {
        val whole = PageDetection("receptacle.duplex", 0.9f, PageBox(10.0, 10.0, 20.0, 20.0))
        val clipped = PageDetection("receptacle.duplex", 0.5f, PageBox(10.0, 10.0, 14.0, 20.0))
        val other = PageDetection("receptacle.duplex", 0.8f, PageBox(40.0, 10.0, 50.0, 20.0))
        val otherClass = PageDetection("switch.single_pole", 0.4f, PageBox(10.5, 10.0, 20.5, 20.0))
        val result = DetectionMerge.merge(listOf(clipped, other, whole, otherClass))
        assertEquals(listOf(whole, other), result.kept)
        assertEquals(2, result.merged)
    }

    @Test fun recognizesPageEndToEnd() = runBlocking {
        val glyphs = listOf(
            Glyph(40.0, 40.0, 10.0, DUPLEX),
            // Straddles the seam between the first two tile columns (tile 0 ends at 128 pt).
            Glyph(122.0, 40.0, 10.0, DUPLEX),
            Glyph(300.0, 200.0, 12.0, SWITCH),
            Glyph(500.0, 40.0, 10.0, MYSTERY),
            // Already marked by hand: not proposed again.
            Glyph(500.0, 300.0, 10.0, DUPLEX),
        )
        val pages = FakePages(listOf(glyphs))
        val detector = FakeDetector(config)
        val existing = listOf(
            PlanMark(page = 0, kind = PlanMarkKind.SYMBOL, points = listOf(PlanPoint(505.0, 305.0)), symbol = PlanSymbolPlacement("receptacle.duplex", size = 10.0)),
        )
        val result = SymbolRecognizer(detector, catalog).recognizePage(pages, 0, existing)

        assertTrue("Never renders more than one tile at a time", pages.rendered.all { it.width <= 256 && it.height <= 256 })
        val stats = result.stats
        assertTrue("Blank tiles are skipped", stats.blankTilesSkipped > 0)
        assertEquals(stats.tiles - stats.blankTilesSkipped, detector.calls)
        assertTrue("The seam symbol merged", stats.duplicatesMerged >= 1)
        assertEquals(mapOf("mystery_widget" to 1), stats.unknownClasses)
        assertEquals(1, stats.alreadyMarked)

        val proposed = result.marks.map { it.symbol!!.deviceId to it.points.single() }.sortedBy { it.second.x }
        assertEquals(listOf("receptacle.duplex", "receptacle.duplex", "switch.single_pole"), proposed.map { it.first })
        val expected = listOf(PlanPoint(45.0, 45.0), PlanPoint(127.0, 45.0), PlanPoint(306.0, 206.0))
        proposed.zip(expected).forEach { (got, want) ->
            assertTrue("$got vs $want", abs(got.second.x - want.x) <= 0.5 && abs(got.second.y - want.y) <= 0.5)
        }
        result.marks.forEach { mark ->
            val symbol = mark.symbol!!
            assertEquals(PlanReviewState.PENDING, symbol.reviewState)
            assertEquals(PlanSymbolSource.DETECTED, symbol.source)
            assertEquals(0.9f, symbol.confidence)
        }
        assertEquals(10.0, result.marks.first { abs(it.points[0].x - 127.0) < 1 }.symbol!!.size, 0.5)
    }

    @Test fun lowConfidenceDetectionsAreDropped() = runBlocking {
        val strict = config.copy(minConfidence = 0.95f)
        val result = SymbolRecognizer(FakeDetector(strict), catalog)
            .recognizePage(FakePages(listOf(listOf(Glyph(40.0, 40.0, 10.0, DUPLEX)))), 0, emptyList())
        assertTrue(result.marks.isEmpty())
        assertTrue(result.stats.lowConfidenceDropped >= 1)
    }

    @Test fun runCoversPagesAndDrawingsAndCanBeCancelled() = runBlocking {
        val pageWithSymbol = listOf(Glyph(40.0, 40.0, 10.0, DUPLEX))
        val sources = mapOf(
            "a" to FakePages(listOf(pageWithSymbol, pageWithSymbol)),
            "b" to FakePages(listOf(pageWithSymbol)),
        )
        val found = mutableListOf<Pair<String, Int>>()
        val progress = mutableListOf<RecognitionProgress>()
        val stats = runRecognition(
            targets = listOf(RecognitionTarget("a", "A"), RecognitionTarget("b", "B", pages = listOf(0, 7))),
            recognizer = SymbolRecognizer(FakeDetector(config), catalog),
            openSource = { sources.getValue(it) },
            existing = { emptyList() },
            onProgress = { progress += it },
            onPage = { id, page -> found += id to page.marks.size },
        )
        assertEquals(listOf("a" to 1, "a" to 1, "b" to 1), found)
        assertEquals(3, stats.proposed)
        assertEquals(3, progress.last().pagesDone)
        assertEquals(1f, progress.last().fraction)

        // Cancelling stops between tiles; pages finished before the cancel were already delivered.
        var job: Job? = null
        var tiles = 0
        val detector = FakeDetector(config) { if (++tiles == 3) job?.cancel() }
        val delivered = mutableListOf<Int>()
        job = launch {
            runRecognition(
                listOf(RecognitionTarget("a", "A")), SymbolRecognizer(detector, catalog),
                openSource = { FakePages(List(5) { pageWithSymbol }) }, existing = { emptyList() },
                onProgress = {}, onPage = { _, page -> delivered += page.page },
            )
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue("Stopped early: $tiles tiles", tiles < 5 * 6)
        assertTrue(delivered.size < 5)
    }

    @Test fun notInstalledDetectorSaysSo() {
        assertEquals(DetectorStatus.NotInstalled, NotInstalledDetector.status)
        val error = runCatching { NotInstalledDetector.detect(TileImage(1, 1, IntArray(1))) }.exceptionOrNull()
        assertTrue(error is IllegalStateException && error !is CancellationException)
    }
}

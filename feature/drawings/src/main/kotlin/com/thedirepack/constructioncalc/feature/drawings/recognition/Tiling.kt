package com.thedirepack.constructioncalc.feature.drawings.recognition

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A rectangle in page points (the drawing's coordinate space). */
data class PageBox(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())
        require(right >= left && bottom >= top)
    }

    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val area: Double get() = width * height
    val centerX: Double get() = (left + right) / 2
    val centerY: Double get() = (top + bottom) / 2

    fun intersection(other: PageBox): Double {
        val w = min(right, other.right) - max(left, other.left)
        val h = min(bottom, other.bottom) - max(top, other.top)
        return if (w <= 0 || h <= 0) 0.0 else w * h
    }

    fun iou(other: PageBox): Double {
        val inter = intersection(other)
        val union = area + other.area - inter
        return if (union <= 0) 0.0 else inter / union
    }

    /** Overlap as a share of the smaller box: 1 when one box lies inside the other. */
    fun containment(other: PageBox): Double {
        val smaller = min(area, other.area)
        return if (smaller <= 0) 0.0 else intersection(other) / smaller
    }

    fun union(other: PageBox) = PageBox(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))

    fun contains(x: Double, y: Double) = x in left..right && y in top..bottom
}

/** One tile of a page region: its pixel rectangle within the region rendered at the grid's scale. */
data class TileSpec(val index: Int, val pxLeft: Int, val pxTop: Int, val width: Int, val height: Int)

/**
 * A page region cut into tiles at native resolution: [scale] pixels per page point (dpi / 72).
 * The page is never downscaled to fit; a big sheet simply has more tiles.
 */
data class TileGrid(val region: PageBox, val scale: Double, val tiles: List<TileSpec>) {
    /** Maps a box in [tile]'s pixels back to page points. */
    fun toPage(tile: TileSpec, box: PixelBox): PageBox {
        fun x(px: Float) = region.left + (tile.pxLeft + px.toDouble().coerceIn(0.0, tile.width.toDouble())) / scale
        fun y(px: Float) = region.top + (tile.pxTop + px.toDouble().coerceIn(0.0, tile.height.toDouble())) / scale
        return PageBox(x(min(box.left, box.right)), y(min(box.top, box.bottom)), x(max(box.left, box.right)), y(max(box.top, box.bottom)))
    }
}

object Tiling {
    fun plan(region: PageBox, config: DetectorConfig): TileGrid {
        val scale = config.dpi / 72.0
        val widthPx = ceil(region.width * scale - 1e-6).toInt().coerceAtLeast(1)
        val heightPx = ceil(region.height * scale - 1e-6).toInt().coerceAtLeast(1)
        val xs = axis(widthPx, config.tileSizePx, config.overlapPx)
        val ys = axis(heightPx, config.tileSizePx, config.overlapPx)
        var index = 0
        val tiles = ys.flatMap { (top, h) -> xs.map { (left, w) -> TileSpec(index++, left, top, w, h) } }
        return TileGrid(region, scale, tiles)
    }

    /**
     * Tile starts along one axis of [total] pixels: steps of tile − overlap, with the last tile
     * pulled back to end on the edge so every tile is full size when the page allows it.
     */
    fun axis(total: Int, tile: Int, overlap: Int): List<Pair<Int, Int>> {
        require(total > 0 && tile > 0 && overlap in 0 until tile)
        if (total <= tile) return listOf(0 to total)
        val step = tile - overlap
        val starts = generateSequence(0) { it + step }.takeWhile { it + tile < total }.toMutableList()
        val last = total - tile
        if (starts.last() != last) starts += last
        return starts.map { it to tile }
    }

    /**
     * Share of sampled pixels dark enough to be ink. A tile below a small threshold (paper margin,
     * an empty room) is skipped without running the model.
     */
    fun inkFraction(image: TileImage, stride: Int = 2, darkLuminance: Int = 160): Double {
        var dark = 0
        var sampled = 0
        val px = image.pixels
        var y = 0
        while (y < image.height) {
            var x = 0
            val row = y * image.width
            while (x < image.width) {
                val c = px[row + x]
                val alpha = c ushr 24
                if (alpha > 0) {
                    val r = (c shr 16) and 0xff
                    val g = (c shr 8) and 0xff
                    val b = c and 0xff
                    // Composite on white for partly transparent pixels.
                    val lum = (r * 299 + g * 587 + b * 114) / 1000
                    val onWhite = (lum * alpha + 255 * (255 - alpha)) / 255
                    if (onWhite < darkLuminance) dark++
                }
                sampled++
                x += stride
            }
            y += stride
        }
        return if (sampled == 0) 0.0 else dark.toDouble() / sampled
    }
}

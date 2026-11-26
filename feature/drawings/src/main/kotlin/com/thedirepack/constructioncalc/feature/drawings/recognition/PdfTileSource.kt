package com.thedirepack.constructioncalc.feature.drawings.recognition

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File

/**
 * Renders tiles straight from the PDF with its own renderer, so a long recognition run never holds
 * the viewer's render queue. Each tile is drawn at the grid's scale (vector sheets stay sharp, scans
 * keep all their detail); one bitmap per tile size is reused.
 */
internal class PdfTileSource(file: File) : PageRasterSource, Closeable {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try {
        PdfRenderer(descriptor)
    } catch (error: Throwable) {
        descriptor.close()
        throw error
    }
    private val bitmaps = HashMap<Pair<Int, Int>, Bitmap>()

    override val pageCount: Int get() = renderer.pageCount

    override fun pageSize(page: Int): Pair<Double, Double> =
        renderer.openPage(page).use { it.width.toDouble() to it.height.toDouble() }

    override fun render(page: Int, grid: TileGrid, tile: TileSpec): TileImage {
        val bitmap = bitmaps.getOrPut(tile.width to tile.height) {
            Bitmap.createBitmap(tile.width, tile.height, Bitmap.Config.ARGB_8888)
        }
        bitmap.eraseColor(Color.WHITE)
        renderer.openPage(page).use { pdfPage ->
            val scale = grid.scale.toFloat()
            val transform = Matrix().apply {
                setTranslate(-grid.region.left.toFloat(), -grid.region.top.toFloat())
                postScale(scale, scale)
                postTranslate(-tile.pxLeft.toFloat(), -tile.pxTop.toFloat())
            }
            pdfPage.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
        val pixels = IntArray(tile.width * tile.height)
        bitmap.getPixels(pixels, 0, tile.width, 0, 0, tile.width, tile.height)
        return TileImage(tile.width, tile.height, pixels)
    }

    override fun close() {
        bitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
        bitmaps.clear()
        renderer.close()
        descriptor.close()
    }
}

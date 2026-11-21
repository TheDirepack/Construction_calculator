package com.thedirepack.constructioncalc.feature.drawings

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.thedirepack.constructioncalc.core.model.GrayImage
import com.thedirepack.constructioncalc.core.model.LineSnapper
import com.thedirepack.constructioncalc.core.model.PlanPoint
import com.thedirepack.constructioncalc.core.model.SnapKind
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Page size in PDF points. */
internal data class PageSize(val width: Float, val height: Float)

internal fun pdfPageSize(file: File, pageIndex: Int): PageSize =
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            renderer.openPage(pageIndex).use { PageSize(it.width.toFloat(), it.height.toFloat()) }
        }
    }

/**
 * Renders [region] of a page (in page points) at [scale] pixels per point, straight from the PDF:
 * vector sheets stay sharp at any zoom, and scans show all the detail they have.
 */
internal fun renderRegion(file: File, pageIndex: Int, region: RectF, scale: Float): Bitmap {
    val width = ceil(region.width() * scale).toInt().coerceIn(1, MAX_REGION_PIXELS)
    val height = ceil(region.height() * scale).toInt().coerceIn(1, MAX_REGION_PIXELS)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(android.graphics.Color.WHITE)
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            renderer.openPage(pageIndex).use { page ->
                val transform = Matrix().apply {
                    setTranslate(-region.left, -region.top)
                    postScale(scale, scale)
                }
                page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }
    return bitmap
}

internal data class SnappedPoint(val point: PlanPoint, val kind: SnapKind)

/**
 * Moves [point] to the center of the nearest drawn line within [radius] page points, or to the
 * corner where two lines meet. The area around the point is rendered fresh at a resolution where
 * the finger's reach is about [SNAP_PIXELS] pixels, which is detailed enough for sub-pixel
 * centers and quick to search. Null when no line is in reach.
 */
internal fun snapToLine(file: File, pageIndex: Int, page: PageSize, point: PlanPoint, radius: Float, screenScale: Float): SnappedPoint? {
    if (radius <= 0f) return null
    val scale = min(SNAP_PIXELS / radius, max(screenScale, 4f)).coerceAtMost(24f)
    // Zoomed far out, a finger covers too much of the sheet for a line to be told apart.
    if (scale < 1.5f) return null
    val reach = radius * 2.2f + 24f / scale
    val region = RectF(
        max(0f, point.x.toFloat() - reach),
        max(0f, point.y.toFloat() - reach),
        min(page.width, point.x.toFloat() + reach),
        min(page.height, point.y.toFloat() + reach),
    )
    if (region.width() <= 0f || region.height() <= 0f) return null
    val bitmap = renderRegion(file, pageIndex, region, scale)
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val image = GrayImage.fromArgb(bitmap.width, bitmap.height, pixels)
    bitmap.recycle()
    val snap = LineSnapper.snap(
        image,
        (point.x - region.left) * scale,
        (point.y - region.top) * scale,
        (radius * scale).toDouble().coerceAtLeast(2.0),
    ) ?: return null
    return SnappedPoint(PlanPoint(region.left + snap.x / scale, region.top + snap.y / scale), snap.kind)
}

/** Reach of a snap, in rendered pixels: what the snapper was checked against on real sheets. */
private const val SNAP_PIXELS = 16f

/** Largest side of a rendered region, to bound memory. */
private const val MAX_REGION_PIXELS = 4096

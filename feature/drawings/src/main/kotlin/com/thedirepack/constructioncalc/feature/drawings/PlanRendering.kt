package com.thedirepack.constructioncalc.feature.drawings

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.thedirepack.constructioncalc.core.model.PlanPoint
import com.thedirepack.constructioncalc.core.plans.SnapKind
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.ceil

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

/**
 * Heavy PDF work (page renders, line-map builds) can't be interrupted once it starts, so flipping
 * through pages used to start one job per page and run them all at once. Each kind of job now runs
 * one at a time, and a job whose screen has already moved on is dropped before it starts.
 */
internal object PdfWork {
    private val renders = Mutex()
    private val lineMaps = Mutex()

    suspend fun <T> render(block: () -> T): T = exclusive(renders, block)
    suspend fun <T> lineMap(block: () -> T): T = exclusive(lineMaps, block)

    private suspend fun <T> exclusive(lock: Mutex, block: () -> T): T = lock.withLock {
        currentCoroutineContext().ensureActive()
        withContext(Dispatchers.IO) { block() }
    }
}

internal data class SnappedPoint(val point: PlanPoint, val kind: SnapKind)

/** Largest side of a rendered region, to bound memory. */
private const val MAX_REGION_PIXELS = 4096
